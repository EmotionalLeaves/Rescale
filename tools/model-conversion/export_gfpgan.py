#!/usr/bin/env python3
"""Convert GFPGAN v1.4 (face restoration, Apache-2.0) to ncnn FP16.

    git clone --depth 1 https://github.com/TencentARC/GFPGAN
    curl -LO https://github.com/TencentARC/GFPGAN/releases/download/v1.3.4/GFPGANv1.4.pth
    python export_gfpgan.py --repo GFPGAN --weights GFPGANv1.4.pth --out out/gfpgan-v1.4

Input in0: aligned 512×512 face, RGB in [-1, 1]. Output out0: restored face, RGB in [-1, 1].

StyleGAN2's modulated convolution builds per-image weights, which ncnn can't run. It's
rewritten here into the equivalent "modulate activations" form: scale the input channels by
the style, convolve with the fixed weight, then demodulate the output with
rsqrt(W² · style² + eps), where W² is constant. Noise uses the model's fixed buffers.
"""
import argparse
import os
import shutil
import sys
import tempfile
import types

import numpy as np
import torch
from torch.nn import functional as F


def stub_basicsr():
    """GFPGAN's arch files only need two tiny things from basicsr."""
    class Registry:
        def register(self, *a, **k):
            return (lambda f: f) if not a or not callable(a[0]) else a[0]

    reg = types.ModuleType("basicsr.utils.registry")
    reg.ARCH_REGISTRY = Registry()
    util = types.ModuleType("basicsr.archs.arch_util")
    util.default_init_weights = lambda *a, **k: None
    for name, mod in {
        "basicsr": types.ModuleType("basicsr"), "basicsr.utils": types.ModuleType("basicsr.utils"),
        "basicsr.utils.registry": reg, "basicsr.archs": types.ModuleType("basicsr.archs"),
        "basicsr.archs.arch_util": util,
    }.items():
        sys.modules[name] = mod


def modulated_forward(self, x, style):
    # FP16-safe: styles reach ~1000 and W²·s² ~3e6 on real faces, far past fp16's 65504.
    # Scale s down by a per-layer power of two K and the demodulation up by K; the two cancel
    # exactly (demodulated layers are scale-invariant in s). Non-demodulated layers keep K = 1.
    k = getattr(self, "fp16_k", 1.0)
    s = self.modulation(style) / k  # (1, c_in)
    if self.sample_mode == "upsample":
        x = F.interpolate(x, scale_factor=2, mode="bilinear", align_corners=False)
    elif self.sample_mode == "downsample":
        x = F.interpolate(x, scale_factor=0.5, mode="bilinear", align_corners=False)
    x = x * s.view(1, -1, 1, 1)
    out = F.conv2d(x, self.weight[0], padding=self.padding)
    if self.demodulate:
        w2 = self.weight[0].pow(2).sum([2, 3])  # (c_out, c_in), constant
        demod = torch.rsqrt(F.linear(s.pow(2), w2) + self.eps / (k * k))
        out = out * demod.view(1, -1, 1, 1)
    elif k != 1.0:
        out = out * k
    return out


def calibrate(net, faces):
    """Pick K per demodulated layer so max|s|/K <= ~0.5 on calibration faces."""
    import math

    peaks = {}
    hooks = []
    for m in net.modules():
        if type(m).__name__ == "ModulatedConv2d" and m.demodulate:
            def hook(mod, inp, out, m=m):
                peaks[m] = max(peaks.get(m, 0.0), float(out.abs().max()))
            hooks.append(m.modulation.register_forward_hook(hook))
    with torch.no_grad():
        for f in faces:
            net(f, return_rgb=False, randomize_noise=False)
    for h in hooks:
        h.remove()
    for m, peak in peaks.items():
        m.fp16_k = float(2 ** math.ceil(math.log2(max(peak, 1e-3) * 2)))
    return {round(p, 1): m.fp16_k for m, p in peaks.items()}


def calibration_faces():
    """Real faces at several sizes/qualities (public domain NASA portrait) + flips + noise."""
    from PIL import Image, ImageFilter
    from skimage import data

    base = Image.fromarray(data.astronaut()).crop((150, 30, 330, 210))
    out = []
    for size in (32, 64, 128, 180):
        for blur in (0, 2):
            im = base.resize((size, size), Image.BICUBIC).resize((512, 512), Image.BICUBIC)
            if blur:
                im = im.filter(ImageFilter.GaussianBlur(blur))
            for flip in (False, True):
                arr = np.asarray(im.transpose(Image.FLIP_LEFT_RIGHT) if flip else im, dtype=np.float32)
                out.append(torch.from_numpy(arr / 127.5 - 1).permute(2, 0, 1)[None])
    out.append(torch.rand(1, 3, 512, 512) * 2 - 1)
    return out


def build(repo, weights, verbose=True):
    """GFPGANv1Clean with the fp16-safe modulated conv, calibrated. Deterministic."""
    stub_basicsr()
    import importlib.util

    if "gfpgan_archs" not in sys.modules:
        pkg = types.ModuleType("gfpgan_archs")
        pkg.__path__ = [os.path.join(repo, "gfpgan", "archs")]
        sys.modules["gfpgan_archs"] = pkg
        for name in ("stylegan2_clean_arch", "gfpganv1_clean_arch"):
            spec = importlib.util.spec_from_file_location(
                f"gfpgan_archs.{name}", os.path.join(repo, "gfpgan", "archs", f"{name}.py"))
            mod = importlib.util.module_from_spec(spec)
            sys.modules[f"gfpgan_archs.{name}"] = mod
            spec.loader.exec_module(mod)
    gf = sys.modules["gfpgan_archs.gfpganv1_clean_arch"]
    net = gf.GFPGANv1Clean(out_size=512, num_style_feat=512, channel_multiplier=2, decoder_load_path=None,
                           fix_decoder=False, num_mlp=8, input_is_latent=True, different_w=True, narrow=1,
                           sft_half=True)
    ckpt = torch.load(weights, map_location="cpu", weights_only=True)
    net.load_state_dict(ckpt.get("params_ema", ckpt), strict=True)
    net.eval()

    torch.manual_seed(0)
    x = torch.rand(1, 3, 512, 512) * 2 - 1
    with torch.no_grad():
        ref = net(x, return_rgb=False, randomize_noise=False)[0]
    # Patch the class the network actually instantiated (import paths can duplicate modules).
    patched = {type(m) for m in net.modules() if type(m).__name__ == "ModulatedConv2d"}
    for cls in patched:
        cls.forward = modulated_forward
    faces = calibration_faces()
    ks = calibrate(net, faces)
    with torch.no_grad():
        new = net(x, return_rgb=False, randomize_noise=False)[0]
    err = float((ref - new).abs().max())
    if verbose:
        print(f"patched {sum(type(m) in patched for m in net.modules())} modulated layers; fp16 K: {sorted(set(ks.values()))}")
        print(f"rewrite max abs diff vs original: {err:.2e}")
    if err > 1e-3:
        sys.exit("modulated-conv rewrite doesn't match")
    return net, faces, x


class Wrapper(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        return self.m(x, return_rgb=False, randomize_noise=False)[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="clone of TencentARC/GFPGAN")
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--check-only", action="store_true", help="only verify the rewrite in PyTorch")
    ap.add_argument("--trace-to", help=argparse.SUPPRESS)  # internal: child process step
    args = ap.parse_args()
    repo = os.path.abspath(args.repo)

    if args.trace_to:
        net, _, x = build(repo, args.weights)
        with torch.no_grad():
            torch.jit.trace(Wrapper(net).eval(), (x,)).save(args.trace_to)
        return
    if args.check_only:
        build(repo, args.weights)
        return

    import subprocess

    import pnnx

    work = tempfile.mkdtemp()
    try:
        pt = os.path.join(work, "model.pt")
        # Trace in a child process so its memory is gone before pnnx (which needs a lot) runs.
        subprocess.run([sys.executable, os.path.abspath(__file__), "--repo", repo, "--weights", args.weights,
                        "--out", args.out, "--trace-to", pt], check=True)
        # pnnx's Python wrapper chokes on this graph; the CLI it ships handles it fine.
        exe = os.path.join(os.path.dirname(pnnx.__file__), "pnnx")
        subprocess.run([exe, pt, "inputshape=[1,3,512,512]", "fp16=1"], cwd=work, check=True,
                       stdout=subprocess.DEVNULL)
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        shutil.copy(os.path.join(work, "model.ncnn.param"), args.out + ".param")
        shutil.copy(os.path.join(work, "model.ncnn.bin"), args.out + ".bin")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    import ncnn

    net, faces, x = build(repo, args.weights, verbose=False)
    n = ncnn.Net()
    n.load_param(args.out + ".param")
    n.load_model(args.out + ".bin")
    worst = 1e9
    for name, inp in (("small face", faces[2]), ("sharp face", faces[12]), ("noise", x)):
        with torch.no_grad():
            want = net(inp, return_rgb=False, randomize_noise=False)[0][0].numpy()
        arr = np.ascontiguousarray(inp[0].numpy())  # keep alive: ncnn.Mat wraps the buffer
        ex = n.create_extractor()
        ex.input("in0", ncnn.Mat(arr))
        _, out = ex.extract("out0")
        a = (np.clip(np.array(out), -1, 1) + 1) / 2
        b = (np.clip(want, -1, 1) + 1) / 2
        psnr = 10 * np.log10(1 / max(float(np.mean((a - b) ** 2)), 1e-12))
        print(f"parity ({name}) PSNR={psnr:.2f} dB")
        worst = min(worst, psnr) if np.isfinite(psnr) else -1
    print(f"wrote {args.out}.param/.bin")
    if worst < 35:
        sys.exit("parity check failed")


if __name__ == "__main__":
    main()
