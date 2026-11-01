#!/usr/bin/env python3
"""Checks for the generative pack (AdcSR) through the real engine. Too heavy for CI (900 MB model,
~9 s per tile on 2 CPU cores), so it runs locally before publishing a pack:

    python generative_check.py --cli build/cli/rescale_cli --model out/adcsr-x4

  flat       a flat 200×150 image stays flat (range <= 6 levels) and keeps its colour (<= 2 levels)
  seams      at each tile-overlap centre of a photo: (a) gradient energy in a 4-px band vs the
             ±40 px around it within 0.7–1.3 (a blurred or ghosted overlap drops to ~0.45),
             (b) mean signed step across the band <= 3 sigma of all columns/rows (a 4-level
             offset scores ~5). Both verified by injecting those defects.
  colour     mean colour of the downscaled output vs the input <= 2 levels per channel
  detail     high-frequency energy well above the bicubic baseline (it's meant to add detail)
"""
import argparse
import math
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

FAILS = []
TILE, PAD, OVERLAP = 96, 16, 16


def check(name, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}", flush=True)
    if not ok:
        FAILS.append(name)


def run(cli, model, img, tmp):
    raw, out = os.path.join(tmp, "in.rgba"), os.path.join(tmp, "out.png")
    rgba = img.convert("RGBA")
    open(raw, "wb").write(rgba.tobytes())
    subprocess.run([cli, model, "in0", "out0", "4", raw, str(rgba.width), str(rgba.height), out,
                    "--tile", str(TILE), "--pad", str(PAD), "--align", "16", "--norm", "2", "-1",
                    "--statmatch", "1", "--lowmem", "1", "--detail", "1", "--colorfix", "1",
                    "--threads", str(os.cpu_count() or 2)], check=True, capture_output=True)
    return np.asarray(Image.open(out).convert("RGB"), np.float64)


def starts(n):
    if n <= TILE:
        return [0]
    k = 1 + math.ceil((n - TILE) / (TILE - OVERLAP))
    return [round(i * (n - TILE) / (k - 1)) for i in range(k)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cli", required=True)
    ap.add_argument("--model", required=True, help="model base path (adcsr-x4)")
    args = ap.parse_args()
    from skimage import data

    with tempfile.TemporaryDirectory() as tmp:
        flat = Image.new("RGB", (200, 150), (214, 220, 228))  # a pale sky: the FP16 trap on GPUs
        o = run(args.cli, args.model, flat, tmp)
        rng = float((o.max(axis=(0, 1)) - o.min(axis=(0, 1))).max())
        drift = float(np.abs(o.mean(axis=(0, 1)) - np.array([214, 220, 228])).max())
        check("flat", np.isfinite(o).all() and rng <= 6 and drift <= 2, f"range {rng:.0f} levels, colour drift {drift:.2f}")

        src = Image.fromarray(data.chelsea()).resize((300, 200), Image.BICUBIC)
        o = run(args.cli, args.model, src, tmp)
        # Tile-overlap centres in output columns/rows.
        cx = [4 * (a + TILE + b) // 2 for a, b in zip(starts(300)[:-1], starts(300)[1:])]
        cy = [4 * (a + TILE + b) // 2 for a, b in zip(starts(200)[:-1], starts(200)[1:])]
        dx, dy = np.diff(o, axis=1), np.diff(o, axis=0)
        gx, gy = np.abs(dx).mean(axis=(0, 2)), np.abs(dy).mean(axis=(1, 2))
        sx, sy = dx.mean(axis=(0, 2)), dy.mean(axis=(1, 2))
        energy = [g[c - 2:c + 2].mean() / g[c - 40:c + 40].mean() for g, cs in ((gx, cx), (gy, cy)) for c in cs]
        steps = [abs(sg[c - 2:c + 2]).max() / sg.std() for sg, cs in ((sx, cx), (sy, cy)) for c in cs]
        ok = all(0.7 <= e <= 1.3 for e in energy) and max(steps) <= 3
        check("seams", ok, f"band energy {min(energy):.2f}–{max(energy):.2f}, worst step {max(steps):.2f} sigma")

        down = np.asarray(Image.fromarray(o.astype(np.uint8)).resize(src.size, Image.BOX), np.float64)
        drift = np.abs(down.mean(axis=(0, 1)) - np.asarray(src, np.float64).mean(axis=(0, 1)))
        check("colour", float(drift.max()) <= 2, f"channel mean drift {np.round(drift, 2).tolist()}")

        bic = np.asarray(src.resize((1200, 800), Image.BICUBIC), np.float64)
        hf = lambda a: float(np.abs(np.diff(a, axis=1)).mean() + np.abs(np.diff(a, axis=0)).mean())  # noqa: E731
        check("detail", hf(o) > 1.5 * hf(bic), f"gradient energy {hf(o):.2f} vs bicubic {hf(bic):.2f}")
    if FAILS:
        sys.exit(f"{len(FAILS)} check(s) failed: {', '.join(FAILS)}")
    print("all generative checks passed")


if __name__ == "__main__":
    main()
