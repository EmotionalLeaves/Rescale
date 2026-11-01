#!/usr/bin/env python3
"""Convert AdcSR (one-step diffusion super-resolution, CVPR 2025) to a single ncnn FP16 network.

AdcSR is OSEDiff distilled into a pruned Stable Diffusion 2.1 UNet plus a half-width VAE decoder:
one forward pass, no prompt, no noise. Code: Apache-2.0 (github.com/Guaishou74851/AdcSR).
Weights: derived from Stable Diffusion 2.1-base, CreativeML Open RAIL++-M (use-based restrictions).

    git clone --depth 1 https://github.com/Guaishou74851/AdcSR adcsr
    curl -LO https://huggingface.co/Guaishou74851/AdcSR/resolve/main/weight/net_params_200.pkl
    curl -LO https://huggingface.co/Guaishou74851/AdcSR/resolve/main/weight/pretrained/halfDecoder.ckpt
    curl -L -o unet_config.json https://huggingface.co/sd2-community/stable-diffusion-2-1-base/resolve/main/unet/config.json
    python export_adcsr.py --repo adcsr --weights net_params_200.pkl --decoder halfDecoder.ckpt \
        --unet-config unet_config.json --out out/adcsr-x4

in0: RGB in [-1,1], H and W multiples of 16 (tiles of 128 are what it was trained on).
out0: RGB in [-1,1], 4× the size. The app matches each tile's mean/contrast to its input, as
AdcSR's own test script does per image.

Two things make this convertible and safe on phone GPUs:

* FP16 range. The last two VAE decoder blocks reach ~69,000 on flat tiles, past FP16's 65,504
  (NaN on device). Their residual stream is scaled down by K = 32: the upsampler conv feeding them,
  every resnet's conv2 and shortcut bias, and the upsampler biases are divided by K, and the
  GroupNorm eps values are divided by K² (GroupNorm is scale-invariant otherwise). The output is
  bit-identical in FP32; peaks drop to ~2,200.
* Memory. pnnx can't hold the whole graph in 7 GB of RAM, so the network is traced as six pieces
  with explicit skip connections and the ncnn files are merged into one network afterwards.
  Parity of the merged network against PyTorch is checked at two tile shapes.
"""
import argparse
import copy
import gc
import json
import os
import pickle
import shutil
import subprocess
import sys
import tempfile
import types
from collections import Counter

import numpy as np
import torch
from torch import nn

K = 32  # fp16 scale for the decoder tail


# ---------------------------------------------------------------- building the network

class _Stub:
    def __init__(self, *a, **k):
        pass

    def __setstate__(self, s):
        pass


class _Unpickler(pickle.Unpickler):
    """halfDecoder.ckpt pickles PyTorch Lightning objects next to its state_dict."""

    def find_class(self, mod, name):
        try:
            return super().find_class(mod, name)
        except Exception:
            return _Stub


_pickle = types.ModuleType("pickle_stub")
_pickle.Unpickler = _Unpickler
_pickle.load = pickle.load


def build(repo, weights, decoder_ckpt, unet_config):
    sys.path.insert(0, repo)
    import forward  # noqa: F401  (AdcSR's slimmed forward methods)
    from diffusers import UNet2DConditionModel
    from diffusers.models.autoencoders.vae import Decoder
    from model import Net

    unet = UNet2DConditionModel.from_config(json.load(open(unet_config)))
    decoder = Decoder(in_channels=4, out_channels=3, up_block_types=["UpDecoderBlock2D"] * 4,
                      block_out_channels=[64, 128, 256, 256], layers_per_block=2, norm_num_groups=32, act_fn="silu",
                      norm_type="group", mid_block_add_attention=True)
    hd = torch.load(decoder_ckpt, map_location="cpu", weights_only=False, pickle_module=_pickle)["state_dict"]
    decoder.load_state_dict({k.replace("decoder.", ""): v for k, v in hd.items() if "decoder" in k}, strict=True)
    del hd
    net = Net(unet, copy.deepcopy(decoder))
    sd = torch.load(weights, map_location="cpu", weights_only=False)
    net.load_state_dict({k.replace("module.", ""): v for k, v in sd.items()}, strict=True)
    del sd
    gc.collect()
    return nn.Sequential(net, *decoder.up_blocks, decoder.conv_norm_out, decoder.conv_act, decoder.conv_out).eval()


def rescale_decoder_tail(model, k):
    """Divide the residual stream of the last two decoder up blocks by k (exact up to GroupNorm eps)."""
    from diffusers.models.resnet import ResnetBlock2D

    up1, up2, up3, norm_out = model[2], model[3], model[4], model[5]
    with torch.no_grad():
        conv = up1.upsamplers[0].conv
        conv.weight /= k
        conv.bias /= k
        for blk in (up2, up3):
            for r in blk.resnets:
                assert isinstance(r, ResnetBlock2D)
                r.norm1.eps /= k * k
                r.conv2.weight /= k
                r.conv2.bias /= k
                if r.conv_shortcut is not None:
                    r.conv_shortcut.bias /= k
            if blk.upsamplers is not None:
                blk.upsamplers[0].conv.bias /= k
        norm_out.eps /= k * k


# ---------------------------------------------------------------- splitting

def _down(blk, x, skips):
    for i in range(len(blk.resnets)):
        x = blk.resnets[i](x)
        if hasattr(blk, "attentions"):
            x = blk.attentions[i](x)
        skips.append(x)
    if blk.downsamplers is not None:
        x = blk.downsamplers[0](x)
        skips.append(x)
    return x


def _up(blk, x, skips):
    for i in range(len(blk.resnets)):
        x = blk.resnets[i](torch.cat([x, skips.pop()], 1))
        if hasattr(blk, "attentions"):
            x = blk.attentions[i](x)
    if blk.upsamplers is not None:
        x = blk.upsamplers[0](x)
    return x


class Down(nn.Module):  # x -> (h, s0..s9)
    def __init__(self, unshuffle, conv_in, downs):
        super().__init__()
        self.u, self.ci, self.d = unshuffle, conv_in, nn.ModuleList(downs)

    def forward(self, x):
        x = self.ci(self.u(x))
        sk = [x]
        for b in self.d:
            x = _down(b, x, sk)
        return (x, *sk)


class Mid(nn.Module):  # h -> (h, s10, s11)
    def __init__(self, d3, mid):
        super().__init__()
        self.d3, self.mid = d3, mid

    def forward(self, h):
        sk = []
        h = _down(self.d3, h, sk)
        h = self.mid.resnets[1](self.mid.attentions[0](self.mid.resnets[0](h)))
        return (h, *sk)


class Up(nn.Module):  # (h, sa, sb, sc) -> h; pops sc, sb, sa
    def __init__(self, blk, tail=None):
        super().__init__()
        self.b, self.tail = blk, tail

    def forward(self, h, sa, sb, sc):
        h = _up(self.b, h, [sa, sb, sc])
        return self.tail(h) if self.tail is not None else h


def pieces(model):
    unshuffle, unet, dmid = model[0].body
    downs, ups = list(unet.down_blocks), list(unet.up_blocks)
    tail = nn.Sequential(unet.conv_norm_out, unet.conv_act, unet.conv_out, dmid, *list(model)[1:])
    return [Down(unshuffle, unet.conv_in, downs[:3]), Mid(downs[3], unet.mid_block),
            Up(ups[0]), Up(ups[1]), Up(ups[2]), Up(ups[3], tail)]


# (inputs, outputs) of each piece; "x" is the network input, "out" its output.
WIRING = [
    (["x"], ["h0"] + [f"s{i}" for i in range(10)]),
    (["h0"], ["h1", "s10", "s11"]),
    (["h1", "s9", "s10", "s11"], ["h2"]),
    (["h2", "s6", "s7", "s8"], ["h3"]),
    (["h3", "s3", "s4", "s5"], ["h4"]),
    (["h4", "s0", "s1", "s2"], ["out"]),
]


def run_pieces(ps, x):
    env = {"x": x}
    for p, (ins, outs) in zip(ps, WIRING):
        r = p(*[env[i] for i in ins])
        env.update(zip(outs, r if isinstance(r, tuple) else (r,)))
    return env["out"]


# ---------------------------------------------------------------- merging ncnn networks

def _parse(path):
    lines = open(path).read().split("\n")
    assert lines[0].strip() == "7767517", path
    layers = []
    for ln in lines[2:]:
        t = ln.split()
        if not t:
            continue
        nb, nt = int(t[2]), int(t[3])
        layers.append([t[0], t[1], t[4:4 + nb], t[4 + nb:4 + nb + nt], t[4 + nb + nt:]])
    return layers


def merge(params, bins, wiring, out_param, out_bin):
    """Concatenate ncnn networks, connecting piece outputs to later piece inputs by name.
    ncnn's .bin is the layers' weights in .param order, so the bins concatenate as they are."""
    glob = lambda n: {"x": "in0", "out": "out0"}.get(n, n)  # noqa: E731
    layers = []
    for i, (path, (ins, outs)) in enumerate(zip(params, wiring)):
        ren = {f"in{k}": glob(n) for k, n in enumerate(ins)}
        ren.update({f"out{k}": glob(n) for k, n in enumerate(outs)})
        r = lambda b, i=i, ren=ren: ren.get(b, f"p{i}_{b}")  # noqa: E731
        for typ, name, bottoms, tops, prm in _parse(path):
            if typ == "Input":
                if i == 0:
                    layers.append(["Input", "in0", [], ["in0"], prm])
                continue
            layers.append([typ, f"p{i}_{name}", [r(b) for b in bottoms], [r(t) for t in tops], prm])
    consumers = Counter(b for lay in layers for b in lay[2])
    assert all(n == 1 for n in consumers.values()), "a blob would have two consumers"
    blobs = {b for lay in layers for b in lay[2] + lay[3]}
    with open(out_param, "w") as f:
        f.write(f"7767517\n{len(layers)} {len(blobs)}\n")
        for typ, name, bottoms, tops, prm in layers:
            f.write(" ".join([f"{typ:<24}", f"{name:<24}", str(len(bottoms)), str(len(tops)), *bottoms, *tops, *prm]) + "\n")
    with open(out_bin, "wb") as o:
        for b in bins:
            with open(b, "rb") as f:
                shutil.copyfileobj(f, o)


# ---------------------------------------------------------------- main

def test_inputs():
    """Public-domain photos (scikit-image) at two tile shapes, plus a flat tile (the FP16 trap)."""
    from PIL import Image
    from skimage import data

    out = []
    for name, im, (h, w) in [("astronaut", data.astronaut(), (128, 128)), ("cat", data.chelsea(), (96, 160)),
                             ("flat", np.full((64, 64, 3), 240, np.uint8), (128, 128))]:
        lr = Image.fromarray(im).resize((w, h), Image.BICUBIC)
        out.append((name, torch.from_numpy(np.asarray(lr, np.float32) / 127.5 - 1).permute(2, 0, 1)[None]))
    return out


def stage_trace(args, work):
    """Child process: build, fix, check, trace pieces, save reference outputs."""
    torch.set_num_threads(os.cpu_count() or 2)
    m = build(args.repo, args.weights, args.decoder, args.unet_config)
    tests = test_inputs()
    with torch.no_grad():
        before = [m(x) for _, x in tests]
    rescale_decoder_tail(m, K)
    ps = pieces(m)
    ref = {}
    with torch.no_grad():
        for (name, x), b in zip(tests, before):
            y = m(x)
            split = run_pieces(ps, x)
            print(f"{name}: fp16 rescale max diff {float((y - b).abs().max()):.2e}, split max diff "
                  f"{float((split - y).abs().max()):.2e}", flush=True)
            assert float((y - b).abs().max()) < 1e-3 and float((split - y).abs().max()) < 1e-5
            ref[name + "_x"], ref[name + "_y"] = x[0].numpy(), y[0].numpy()
    np.savez(os.path.join(work, "ref.npz"), **ref)

    # Trace each piece and record example input shapes at two tile sizes for dynamic shapes.
    # The decoder piece is traced smaller: its 512² activations are what exhaust pnnx's memory.
    shapes = []
    for i, sz in enumerate([(128, 128), (96, 160)]):
        env, per = {"x": torch.rand(1, 3, *sz) * 2 - 1}, []
        small = {"x": torch.rand(1, 3, sz[0] // 2, sz[1] // 2) * 2 - 1}
        with torch.no_grad():
            for j, (p, (ins, outs)) in enumerate(zip(ps, WIRING)):
                e = small if j == len(ps) - 1 else env
                a = [e[n] for n in ins]
                per.append([list(t.shape) for t in a])
                if i == 0:
                    torch.jit.trace(p, tuple(a)).save(os.path.join(work, f"piece{j}.pt"))
                for d in (env, small):
                    r = p(*[d[n] for n in ins])
                    d.update(zip(outs, r if isinstance(r, tuple) else (r,)))
        shapes.append(per)
    json.dump(shapes, open(os.path.join(work, "shapes.json"), "w"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="clone of Guaishou74851/AdcSR")
    ap.add_argument("--weights", required=True, help="net_params_200.pkl")
    ap.add_argument("--decoder", required=True, help="halfDecoder.ckpt")
    ap.add_argument("--unet-config", required=True, help="stable-diffusion-2-1-base unet/config.json")
    ap.add_argument("--out", required=True, help="output base path, e.g. out/adcsr-x4")
    ap.add_argument("--stage", help=argparse.SUPPRESS)
    ap.add_argument("--work", help=argparse.SUPPRESS)
    args = ap.parse_args()
    for k in ("repo", "weights", "decoder", "unet_config"):
        setattr(args, k, os.path.abspath(getattr(args, k)))
    if args.stage == "trace":
        stage_trace(args, args.work)
        return

    import ncnn
    import pnnx as pnnx_mod

    work = tempfile.mkdtemp()
    try:
        # Each step in its own process so PyTorch's memory is gone before pnnx runs.
        subprocess.run([sys.executable, os.path.abspath(__file__), "--repo", args.repo, "--weights", args.weights,
                        "--decoder", args.decoder, "--unet-config", args.unet_config, "--out", args.out,
                        "--stage", "trace", "--work", work], check=True)
        shapes = json.load(open(os.path.join(work, "shapes.json")))
        exe = os.path.join(os.path.dirname(pnnx_mod.__file__), "pnnx")
        fmt = lambda ss: ",".join("[" + ",".join(map(str, s)) + "]" for s in ss)  # noqa: E731
        for j in range(len(WIRING)):
            pt = os.path.join(work, f"piece{j}.pt")
            r = subprocess.run([exe, pt, f"inputshape={fmt(shapes[0][j])}", f"inputshape2={fmt(shapes[1][j])}",
                                "fp16=1"], cwd=work, capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(f"pnnx failed on piece {j}:\n{r.stdout[-1500:]}{r.stderr[-1500:]}")
            os.remove(pt)
            for ext in (".pnnx.bin", ".pnnx.onnx"):
                if os.path.exists(os.path.join(work, f"piece{j}{ext}")):
                    os.remove(os.path.join(work, f"piece{j}{ext}"))
            print(f"piece {j} converted", flush=True)
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        merge([os.path.join(work, f"piece{j}.ncnn.param") for j in range(len(WIRING))],
              [os.path.join(work, f"piece{j}.ncnn.bin") for j in range(len(WIRING))],
              WIRING, args.out + ".param", args.out + ".bin")

        # Parity of the merged network (CPU, FP16 storage, no winograd: what the app does).
        ref = np.load(os.path.join(work, "ref.npz"))
        net = ncnn.Net()
        net.opt.use_fp16_storage = True
        net.opt.use_fp16_packed = True
        net.opt.use_winograd_convolution = False
        net.opt.use_sgemm_convolution = False
        net.load_param(args.out + ".param")
        net.load_model(args.out + ".bin")
        worst = 1e9
        for name in ("astronaut", "cat", "flat"):
            x = np.ascontiguousarray(ref[name + "_x"])
            ex = net.create_extractor()
            ex.input("in0", ncnn.Mat(x))
            _, o = ex.extract("out0")
            got, want = np.array(o), ref[name + "_y"]
            a, b = (np.clip(got, -1, 1) + 1) / 2, (np.clip(want, -1, 1) + 1) / 2
            p = 10 * np.log10(1 / max(float(np.mean((a - b) ** 2)), 1e-12)) if np.isfinite(got).all() else -1
            print(f"parity ({name}, {x.shape[1]}x{x.shape[2]}) PSNR={p:.2f} dB", flush=True)
            worst = min(worst, p)
        print(f"wrote {args.out}.param/.bin")
        if worst < 50:
            sys.exit("parity check failed")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
