#!/usr/bin/env python3
"""Automated quality gates for the engine, run in CI on every push (see ci.yml, job "quality").

Uses the desktop build of the app's exact engine (rescale_cli, CPU ncnn) and the bundled
models. Images are public domain (scikit-image's NASA/USGS sample images) or synthetic.

Checks (all must pass):
  parity      tiled ncnn output vs untiled PyTorch (reflect-padded)          PSNR >= 50 dB
  tiling      small tiles vs one big tile, each post-processing combo  PSNR >= 55 dB, max diff <= 4
  seams       worst row/col excess curvature on a smooth gradient             <= 0.8 levels
  checker     flat image: amplitude at the 4x grid frequencies <= 0.25 levels, range <= 4
  color       |mean(downscaled output) - mean(input)| per channel, color fix on  <= 1.5 levels
Also records CPU throughput per model (MP of output per second).

    python quality_suite.py --cli build/cli/rescale_cli --models app/src/main/assets/models \
        --weights realesr-general-x4v3.pth --out benchmark/results/latest.json
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "model-conversion"))
from srvgg import SRVGGNetCompact, load_state  # noqa: E402

FAILS = []


def check(name, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}", flush=True)
    if not ok:
        FAILS.append(name)


def psnr(a, b):
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    return 10 * np.log10(255.0 ** 2 / max(mse, 1e-12))


class Cli:
    def __init__(self, exe, models, tmp):
        self.exe, self.models, self.tmp = exe, models, tmp

    def run(self, img, model="realesr-general-x4v3", blobs=("in0", "out0"), scale=4, tile=128, extra=()):
        rgba = img.convert("RGBA")
        raw = os.path.join(self.tmp, "in.rgba")
        out = os.path.join(self.tmp, "out.png")
        open(raw, "wb").write(rgba.tobytes())
        t = time.time()
        subprocess.run([self.exe, os.path.join(self.models, model), *blobs, str(scale), raw,
                        str(rgba.width), str(rgba.height), out, "--tile", str(tile), *extra],
                       check=True, capture_output=True)
        dt = time.time() - t
        return np.asarray(Image.open(out).convert("RGB")), dt


def seam_score(img):
    """Max over rows/columns of |2nd difference| minus the median of nearby lines."""
    lum = img.astype(np.float64).mean(axis=2)
    best = 0.0
    for axis in (0, 1):
        prof = np.abs(np.diff(np.diff(lum, axis=axis), axis=axis)).mean(axis=1 - axis)
        for i in range(8, len(prof) - 8):
            nb = np.median(np.r_[prof[i - 8:i - 1], prof[i + 2:i + 9]])
            best = max(best, prof[i] - nb)
    return float(best)


def sample_images():
    from skimage import data
    imgs = {
        "astronaut": Image.fromarray(data.astronaut()),
        "coffee": Image.fromarray(data.coffee()),
        "chelsea": Image.fromarray(data.chelsea()),
    }
    return {k: v.crop((0, 0, min(v.width, 288), min(v.height, 288))) for k, v in imgs.items()}


def gradient(w=256, h=256):
    x = np.linspace(0, 1, w)[None, :, None]
    y = np.linspace(0, 1, h)[:, None, None]
    rgb = np.concatenate([0.2 + 0.6 * x + 0 * y, 0.3 + 0.5 * y + 0 * x, 0.6 - 0.3 * x * y], axis=2)
    return Image.fromarray((rgb * 255).round().astype(np.uint8))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cli", required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--weights", required=True, help="realesr-general-x4v3.pth")
    ap.add_argument("--out", help="write results JSON here")
    args = ap.parse_args()
    tmp = tempfile.mkdtemp()
    cli = Cli(args.cli, args.models, tmp)
    results = {"checks": {}, "throughput_mp_per_s": {}}
    imgs = sample_images()

    # 1. Parity with untiled PyTorch.
    net = SRVGGNetCompact(num_conv=32, upscale=4)
    net.load_state_dict(load_state(args.weights))
    net.eval()
    for name, img in imgs.items():
        got, _ = cli.run(img, tile=96)
        x = torch.from_numpy(np.asarray(img, dtype=np.float32) / 255).permute(2, 0, 1)[None]
        xp = torch.nn.functional.pad(x, (16, 16, 16, 16), mode="reflect")
        with torch.no_grad():
            ref = net(xp)[0, :, 64:-64, 64:-64].clamp(0, 1).permute(1, 2, 0).numpy()
        ref = (ref * 255).round()
        p = psnr(got, ref)
        results["checks"][f"parity_{name}"] = round(p, 2)
        check(f"parity/{name}", p >= 50, f"{p:.1f} dB vs untiled PyTorch")

    # 2. Tiling consistency incl. post-processing.
    img = imgs["astronaut"]
    combos = {"default": [], "colorfix": ["--colorfix", "1"],
              "all": ["--detail", "0.6", "--luma", "1", "--colorfix", "1"]}
    for cname, extra in combos.items():
        a, _ = cli.run(img, tile=64, extra=extra)
        b, _ = cli.run(img, tile=512, extra=extra)
        ai, bi = a[32:-32, 32:-32], b[32:-32, 32:-32]
        p = psnr(ai, bi)
        worst = int(np.abs(ai.astype(int) - bi.astype(int)).max())
        results["checks"][f"tiling_{cname}"] = {"psnr": round(p, 2), "max_diff": worst}
        check(f"tiling/{cname}", p >= 55 and worst <= 4, f"{p:.1f} dB, worst pixel {worst} levels, small tiles vs one tile")

    # 3. Seams: on a smooth gradient, no row/column may have much more curvature than its
    #    neighbours. A 2-level tile seam scores ~1.4, clean output ~0.35 (validated by injection).
    g = gradient()
    out, _ = cli.run(g, tile=64)
    score = seam_score(out)
    results["checks"]["seam_score"] = round(score, 3)
    check("seams/gradient", score <= 0.8, f"worst line excess curvature {score:.2f} levels")

    # 4. Checkerboard: a flat image must stay flat, with no periodic pattern at the 4x
    #    upsampling grid (periods 4 and 2 output px). Amplitude measured in grey levels.
    flat = Image.new("RGB", (96, 96), (128, 120, 110))
    out, _ = cli.run(flat, tile=64)
    l = out.astype(np.float64).mean(axis=2)
    l = l - l.mean()
    n = l.shape[0]
    amp = np.abs(np.fft.fft2(l)) / (n * n)
    freqs = [0, n // 4, n // 2, 3 * n // 4]
    grid_amp = max(amp[i, j] for i in freqs for j in freqs if (i, j) != (0, 0))
    spread = int(max(np.ptp(out[..., c]) for c in range(3)))
    results["checks"]["checker_grid_amplitude"] = round(float(grid_amp), 4)
    check("checker/flat", grid_amp <= 0.25 and spread <= 4,
          f"grid amplitude {grid_amp:.3f} levels, per-channel range {spread}")

    # 5. Colour drift with colour fix on.
    for name, img in imgs.items():
        out, _ = cli.run(img, extra=["--colorfix", "1"])
        small = np.asarray(Image.fromarray(out).resize(img.size, Image.BOX), dtype=np.float64)
        drift = np.abs(small.mean(axis=(0, 1)) - np.asarray(img, dtype=np.float64).mean(axis=(0, 1)))
        results["checks"][f"color_{name}"] = [round(v, 2) for v in drift]
        check(f"color/{name}", drift.max() <= 1.5, f"channel mean drift {drift.round(2).tolist()}")

    # Throughput (informational).
    bench = imgs["astronaut"].crop((0, 0, 256, 256))
    for model, blobs, scale in [("realesr-general-x4v3", ("in0", "out0"), 4),
                                ("realesr-animevideov3-x4", ("data", "output"), 4)]:
        _, dt = cli.run(bench, model=model, blobs=blobs, scale=scale, tile=128)
        mp = (256 * scale) ** 2 / 1e6
        results["throughput_mp_per_s"][model] = round(mp / dt, 3)
        print(f"INFO  {model}: {mp / dt:.2f} MP/s output on this CPU")

    results["failed"] = FAILS
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        json.dump(results, open(args.out, "w"), indent=2)
    if FAILS:
        sys.exit(f"{len(FAILS)} quality check(s) failed: {', '.join(FAILS)}")
    print("all quality checks passed")


if __name__ == "__main__":
    main()
