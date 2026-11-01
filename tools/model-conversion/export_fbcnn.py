#!/usr/bin/env python3
"""Convert FBCNN (JPEG artifact removal, Apache-2.0) to ncnn FP16.

    git clone --depth 1 https://github.com/jiaxi-jiang/FBCNN
    curl -LO https://github.com/jiaxi-jiang/FBCNN/releases/download/v1.0/fbcnn_color.pth
    python export_fbcnn.py --repo FBCNN --weights fbcnn_color.pth --out out/fbcnn-color

Inputs:  in0 = RGB image in [0,1] (H, W multiples of 8)
         in1 = 1-element tensor, 1 - quality/100 (the "QF" control; 0.9 = very compressed)
Output:  out0 = restored RGB in [0,1]

The quality input replaces FBCNN's blind per-image QF predictor, so every tile of a
large image uses the same strength (taken from the JPEG header or the analysis).
"""
import argparse
import os
import shutil
import sys
import tempfile

import numpy as np
import torch
from torch import nn


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="path to a clone of jiaxi-jiang/FBCNN")
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    sys.path.insert(0, os.path.abspath(args.repo))
    from models.network_fbcnn import FBCNN  # noqa: E402

    net = FBCNN(in_nc=3, out_nc=3, nc=[64, 128, 256, 512], nb=4, act_mode="R")
    net.load_state_dict(torch.load(args.weights, map_location="cpu", weights_only=True), strict=True)
    net.eval()

    class Fixed(nn.Module):
        """FBCNN.forward without the shape-dependent padding and with QF as an input."""

        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x, qf):
            m = self.m
            x1 = m.m_head(x)
            x2 = m.m_down1(x1)
            x3 = m.m_down2(x2)
            x4 = m.m_down3(x3)
            h = m.m_body_encoder(x4)
            h = m.m_body_decoder(h)
            e = m.qf_embed(qf)
            g3, b3 = m.to_gamma_3(e), m.to_beta_3(e)
            g2, b2 = m.to_gamma_2(e), m.to_beta_2(e)
            g1, b1 = m.to_gamma_1(e), m.to_beta_1(e)
            h = h + x4
            h = m.m_up3[0](h)
            for i in range(m.nb):
                h = m.m_up3[i + 1](h, g3, b3)
            h = h + x3
            h = m.m_up2[0](h)
            for i in range(m.nb):
                h = m.m_up2[i + 1](h, g2, b2)
            h = h + x2
            h = m.m_up1[0](h)
            for i in range(m.nb):
                h = m.m_up1[i + 1](h, g1, b1)
            h = h + x1
            return m.m_tail(h)

    fixed = Fixed(net).eval()
    import pnnx

    work = tempfile.mkdtemp()
    try:
        x = torch.rand(1, 3, 64, 64)
        x2 = torch.rand(1, 3, 96, 80)
        q = torch.tensor([[0.7]])
        pt = os.path.join(work, "model.pt")
        pnnx.export(fixed, pt, (x, q), (x2, q), fp16=True)
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        shutil.copy(os.path.join(work, "model.ncnn.param"), args.out + ".param")
        shutil.copy(os.path.join(work, "model.ncnn.bin"), args.out + ".bin")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    # Parity vs the original module (with its own padding) at a fixed QF.
    import ncnn

    rng = np.random.default_rng(0)
    img = rng.random((3, 72, 88), dtype=np.float32)
    with torch.no_grad():
        ref, _ = net(torch.from_numpy(img)[None], torch.tensor([[0.7]]))
    n = ncnn.Net()
    n.load_param(args.out + ".param")
    n.load_model(args.out + ".bin")
    ex = n.create_extractor()
    ex.input("in0", ncnn.Mat(img))
    ex.input("in1", ncnn.Mat(np.array([0.7], dtype=np.float32)))
    _, out = ex.extract("out0")
    got = np.array(out)
    mse = float(np.mean((np.clip(got, 0, 1) - np.clip(ref[0].numpy(), 0, 1)) ** 2))
    psnr = 10 * np.log10(1 / max(mse, 1e-12))
    print(f"wrote {args.out}.param/.bin  parity PSNR={psnr:.2f} dB")
    if psnr < 40:
        sys.exit("parity check failed")


if __name__ == "__main__":
    main()
