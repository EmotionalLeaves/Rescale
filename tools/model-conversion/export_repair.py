#!/usr/bin/env python3
"""Convert the damage-repair models to ncnn FP16 and check parity against PyTorch.

* Scratch/tear detector: the UNet from Microsoft's "Bringing Old Photos Back to Life" (MIT),
  checkpoints/detection/FT_Epoch_latest.pt inside global_checkpoints.zip.
* Inpainter: MI-GAN 512 Places2 (Picsart AI Research, MIT code and weights), as the TorchScript
  trace published with IOPaint (github.com/Sanster/models, release "migan").

    git clone --depth 1 https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life bopbtl
    curl -LO https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life/releases/download/v1.0/global_checkpoints.zip
    curl -LO https://github.com/Sanster/models/releases/download/migan/migan_traced.pt
    python export_repair.py --repo bopbtl --checkpoints global_checkpoints.zip --migan migan_traced.pt --out out/

Outputs out/scratch-detect.{param,bin} (in0: 1×H×W gray in [-1,1], H and W multiples of 16;
out0: logits, damage where sigmoid >= 0.4) and out/migan-512.{param,bin} (in0: 4×512×512 =
[0.5 - mask, rgb * (1 - mask)] with rgb in [-1,1]; out0: rgb in [-1,1]).

Both stay well inside FP16 range (checked here by running the networks in half precision).
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import types
import zipfile

import numpy as np
import torch
from torch import nn


def scratch_unet(repo, checkpoints):
    sys.path.insert(0, os.path.join(repo, "Global"))
    sb = types.ModuleType("detection_models.sync_batchnorm")  # only needed for multi-GPU training
    sb.DataParallelWithCallback = nn.DataParallel
    sb.SynchronizedBatchNorm2d = nn.BatchNorm2d
    sys.modules["detection_models.sync_batchnorm"] = sb
    from detection_models import networks

    net = networks.UNet(in_channels=1, out_channels=1, depth=4, conv_num=2, wf=6, padding=True, batch_norm=True,
                        up_mode="upsample", with_tanh=False, sync_bn=False, antialiasing=True)
    name = "checkpoints/detection/FT_Epoch_latest.pt"
    if checkpoints.endswith(".zip"):
        with zipfile.ZipFile(checkpoints) as z, z.open(name) as f:
            ck = torch.load(f, map_location="cpu", weights_only=False)
    else:
        ck = torch.load(checkpoints, map_location="cpu", weights_only=False)
    net.load_state_dict(ck["model_state"])
    return net.eval()


def pnnx(pt, workdir, *shapes):
    import pnnx as p

    exe = os.path.join(os.path.dirname(p.__file__), "pnnx")
    args = [exe, pt, *shapes, "fp16=1"]
    r = subprocess.run(args, cwd=workdir, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"pnnx failed on {pt}:\n{r.stdout[-2000:]}{r.stderr[-2000:]}")
    base = os.path.splitext(pt)[0]
    return base + ".ncnn.param", base + ".ncnn.bin"


def ncnn_run(param, bin_, x):
    import ncnn

    n = ncnn.Net()
    n.load_param(param)
    n.load_model(bin_)
    arr = np.ascontiguousarray(x[0].numpy())
    ex = n.create_extractor()
    ex.input("in0", ncnn.Mat(arr))
    _, out = ex.extract("out0")
    return np.array(out)


def psnr(a, b):
    return 10 * np.log10(1 / max(float(np.mean((a - b) ** 2)), 1e-12))


def test_photo(h, w):
    """Public-domain photo (NASA, via scikit-image) with synthetic scratches, gray in [-1,1] and rgb."""
    from PIL import Image, ImageDraw
    from skimage import data

    im = Image.fromarray(data.astronaut()).resize((w, h), Image.BICUBIC)
    d = ImageDraw.Draw(im)
    rng = np.random.RandomState(0)
    for _ in range(12):
        x0, y0 = rng.randint(0, w), rng.randint(0, h)
        d.line([x0, y0, x0 + rng.randint(-80, 80), y0 + rng.randint(-80, 80)], fill=(235, 230, 220), width=2)
    rgb = np.asarray(im, np.float32) / 127.5 - 1
    gray = np.asarray(im.convert("L"), np.float32) / 127.5 - 1
    return torch.from_numpy(gray)[None, None], torch.from_numpy(rgb).permute(2, 0, 1)[None]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="clone of microsoft/Bringing-Old-Photos-Back-to-Life")
    ap.add_argument("--checkpoints", required=True, help="global_checkpoints.zip (or FT_Epoch_latest.pt)")
    ap.add_argument("--migan", required=True, help="migan_traced.pt")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    work = tempfile.mkdtemp()
    fails = []
    try:
        # Scratch detector: dynamic H×W (multiples of 16).
        det = scratch_unet(os.path.abspath(args.repo), args.checkpoints)
        pt = os.path.join(work, "det.pt")
        with torch.no_grad():
            torch.jit.trace(det, torch.rand(1, 1, 256, 256) * 2 - 1).save(pt)
        p, b = pnnx(pt, work, "inputshape=[1,1,256,256]", "inputshape2=[1,1,320,384]")
        shutil.copy(p, os.path.join(args.out, "scratch-detect.param"))
        shutil.copy(b, os.path.join(args.out, "scratch-detect.bin"))
        gray, rgb = test_photo(240, 384)
        with torch.no_grad():
            want = torch.sigmoid(det(gray))[0, 0].numpy()
            half = torch.sigmoid(det.half()(gray.half()).float())[0, 0].numpy()
            det.float()
        got = 1 / (1 + np.exp(-ncnn_run(p, b, gray)[0]))
        dis = float(((want >= 0.4) != (got >= 0.4)).mean())
        dis16 = float(((want >= 0.4) != (half >= 0.4)).mean())
        print(f"scratch-detect: {float((want >= 0.4).mean()) * 100:.2f}% flagged, ncnn mask disagreement "
              f"{dis * 100:.3f}%, fp16 {dis16 * 100:.3f}%")
        if dis > 0.002 or dis16 > 0.002 or not np.isfinite(half).all():
            fails.append("scratch-detect")

        # MI-GAN: fixed 512×512.
        mig = torch.jit.load(args.migan, map_location="cpu").eval()
        pt = os.path.join(work, "migan.pt")
        shutil.copy(args.migan, pt)
        p, b = pnnx(pt, work, "inputshape=[1,4,512,512]")
        shutil.copy(p, os.path.join(args.out, "migan-512.param"))
        shutil.copy(b, os.path.join(args.out, "migan-512.bin"))
        _, rgb = test_photo(512, 512)
        mask = torch.zeros(1, 1, 512, 512)
        mask[..., 300:420, 330:460] = 1
        mask[..., 100:106, :] = 1
        x = torch.cat([0.5 - mask, rgb * (1 - mask)], 1)
        with torch.no_grad():
            want = mig(x)[0].numpy()
            half = mig.half()(x.half()).float()[0].numpy()
        got = ncnn_run(p, b, x)
        to01 = lambda a: (np.clip(a, -1, 1) + 1) / 2  # noqa: E731
        ps, ps16 = psnr(to01(want), to01(got)), psnr(to01(want), to01(half))
        print(f"migan-512: ncnn parity PSNR={ps:.2f} dB, fp16 PSNR={ps16:.2f} dB")
        if ps < 50 or ps16 < 45 or not np.isfinite(half).all():
            fails.append("migan-512")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if fails:
        sys.exit("parity check failed: " + ", ".join(fails))
    print("wrote", os.path.join(args.out, "scratch-detect.*"), "and", os.path.join(args.out, "migan-512.*"))


if __name__ == "__main__":
    main()
