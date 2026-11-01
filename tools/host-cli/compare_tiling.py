#!/usr/bin/env python3
"""End-to-end check of the app's tiled engine against whole-image PyTorch inference.

    python compare_tiling.py --cli build/cli/rescale_cli --image photo.jpg \
        --weights realesr-general-x4v3.pth --model app/src/main/assets/models/realesr-general-x4v3

Prints PSNR between the tiled ncnn output and an untiled PyTorch run. Tiling seams or
blending bugs show up as a PSNR drop (expect > 50 dB; typically ~60 dB with FP16).
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "model-conversion"))
from srvgg import SRVGGNetCompact, load_state  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cli", required=True)
    ap.add_argument("--image", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--model", required=True, help="ncnn model base path (no extension)")
    ap.add_argument("--tile", type=int, default=128)
    ap.add_argument("--max-side", type=int, default=384, help="crop input so the reference run stays fast")
    ap.add_argument("--save", help="write the tiled output here too")
    args = ap.parse_args()

    img = Image.open(args.image).convert("RGBA")
    img = img.crop((0, 0, min(img.width, args.max_side), min(img.height, args.max_side)))
    w, h = img.size
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "in.rgba")
        out = args.save or os.path.join(tmp, "out.png")
        open(raw, "wb").write(img.tobytes())
        subprocess.run([args.cli, args.model, "in0", "out0", "4", raw, str(w), str(h), out,
                        "--tile", str(args.tile)], check=True)
        tiled = np.asarray(Image.open(out).convert("RGB"), dtype=np.float32) / 255.0

    net = SRVGGNetCompact(num_conv=32, upscale=4)
    net.load_state_dict(load_state(args.weights))
    net.eval()
    x = torch.from_numpy(np.asarray(img.convert("RGB"), dtype=np.float32) / 255.0).permute(2, 0, 1)[None]
    # The engine reflect-pads image borders, so the reference does too (zero padding would
    # differ at the edges for reasons unrelated to tiling).
    p = 16
    xp = torch.nn.functional.pad(x, (p, p, p, p), mode="reflect")
    with torch.no_grad():
        ref = net(xp)[0, :, 4 * p:-4 * p, 4 * p:-4 * p].clamp(0, 1).permute(1, 2, 0).numpy()
    ref = np.round(ref * 255) / 255

    mse = float(np.mean((tiled - ref) ** 2))
    psnr = 10 * np.log10(1.0 / max(mse, 1e-12))
    worst = float(np.abs(tiled - ref).max() * 255)
    print(f"{w}x{h} -> {tiled.shape[1]}x{tiled.shape[0]}  PSNR vs untiled PyTorch: {psnr:.2f} dB  max diff: {worst:.0f}/255")
    if psnr < 50:
        sys.exit("FAIL: tiled output differs too much from the reference")


if __name__ == "__main__":
    main()
