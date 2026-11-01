#!/usr/bin/env python3
"""Convert realesr-general-x4v3 (optionally blended with its wdn twin) to NCNN FP16.

    python export_ncnn.py --weights realesr-general-x4v3.pth \
        [--wdn realesr-general-wdn-x4v3.pth --denoise 0.5] \
        --out ../../app/src/main/assets/models/general-x4v3

Produces <out>.param and <out>.bin with input blob "in0" and output blob "out0",
then checks parity between PyTorch and NCNN on a random tile.
"""
import argparse
import os
import shutil
import sys
import tempfile

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from srvgg import SRVGGNetCompact, interpolate_state, load_state  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--wdn", help="realesr-general-wdn-x4v3.pth for denoise interpolation")
    ap.add_argument("--denoise", type=float, default=0.5,
                    help="0 = no denoise (plain x4v3), 1 = full wdn. Only used with --wdn")
    ap.add_argument("--num-conv", type=int, default=32)
    ap.add_argument("--scale", type=int, default=4)
    ap.add_argument("--out", required=True, help="output path without extension")
    ap.add_argument("--fp32", action="store_true")
    args = ap.parse_args()

    state = load_state(args.weights)
    if args.wdn:
        # Real-ESRGAN's convention: dni weight [1-d, d] over [x4v3, wdn]
        state = interpolate_state(load_state(args.wdn), state, args.denoise)

    net = SRVGGNetCompact(num_feat=64, num_conv=args.num_conv, upscale=args.scale)
    net.load_state_dict(state, strict=True)
    net.eval()

    import pnnx

    work = tempfile.mkdtemp()
    try:
        x = torch.rand(1, 3, 64, 64)
        x2 = torch.rand(1, 3, 96, 80)
        pt = os.path.join(work, "model.pt")
        pnnx.export(net, pt, (x,), (x2,), fp16=not args.fp32)
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        shutil.copy(os.path.join(work, "model.ncnn.param"), args.out + ".param")
        shutil.copy(os.path.join(work, "model.ncnn.bin"), args.out + ".bin")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    psnr = parity(net, args.out)
    print(f"wrote {args.out}.param/.bin  parity PSNR={psnr:.2f} dB")
    if psnr < 40:
        sys.exit("parity check failed (< 40 dB)")


def parity(net, base):
    try:
        import ncnn
    except ImportError:
        print("ncnn python package missing; skipping parity check")
        return float("inf")
    rng = np.random.default_rng(0)
    img = rng.random((3, 72, 72), dtype=np.float32)
    with torch.no_grad():
        ref = net(torch.from_numpy(img)[None]).numpy()[0]
    n = ncnn.Net()
    n.load_param(base + ".param")
    n.load_model(base + ".bin")
    ex = n.create_extractor()
    ex.input("in0", ncnn.Mat(img))
    _, out = ex.extract("out0")
    got = np.array(out)
    mse = float(np.mean((np.clip(got, 0, 1) - np.clip(ref, 0, 1)) ** 2))
    return 10 * np.log10(1.0 / max(mse, 1e-12))


if __name__ == "__main__":
    main()
