#!/usr/bin/env python3
"""Convert RealESRNet_x4plus (BSD-3-Clause) to ncnn FP16 for the Text pack.

RealESRNet is Real-ESRGAN's generator before GAN training: same real-world degradations, pixel
loss only, so it sharpens edges without inventing texture, which is what text wants.

It has exactly the RRDBNet architecture of realesrgan-x4plus, whose ncnn files Real-ESRGAN
publishes. pnnx needs more than 7 GB of RAM to convert RRDBNet, so instead the published .param
is reused and a new .bin is written in its layer order (ncnn Convolution: fp16 tag + fp16
weights, then fp32 bias). Parity vs PyTorch is checked.

    curl -LO https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.1/RealESRNet_x4plus.pth
    python export_rrdb.py --weights RealESRNet_x4plus.pth \
        --param realesrgan-ncnn-vulkan/models/realesrgan-x4plus.param --out out/realesrnet-x4plus
"""
import argparse
import shutil
import struct
import sys

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

FP16_TAG = 0x01306B47


class RDB(nn.Module):
    def __init__(self, nf=64, gc=32):
        super().__init__()
        self.conv1 = nn.Conv2d(nf, gc, 3, 1, 1)
        self.conv2 = nn.Conv2d(nf + gc, gc, 3, 1, 1)
        self.conv3 = nn.Conv2d(nf + 2 * gc, gc, 3, 1, 1)
        self.conv4 = nn.Conv2d(nf + 3 * gc, gc, 3, 1, 1)
        self.conv5 = nn.Conv2d(nf + 4 * gc, nf, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, True)

    def forward(self, x):
        x1 = self.lrelu(self.conv1(x))
        x2 = self.lrelu(self.conv2(torch.cat((x, x1), 1)))
        x3 = self.lrelu(self.conv3(torch.cat((x, x1, x2), 1)))
        x4 = self.lrelu(self.conv4(torch.cat((x, x1, x2, x3), 1)))
        return self.conv5(torch.cat((x, x1, x2, x3, x4), 1)) * 0.2 + x


class RRDB(nn.Module):
    def __init__(self, nf=64):
        super().__init__()
        self.rdb1, self.rdb2, self.rdb3 = RDB(nf), RDB(nf), RDB(nf)

    def forward(self, x):
        return self.rdb3(self.rdb2(self.rdb1(x))) * 0.2 + x


class RRDBNet(nn.Module):
    def __init__(self, nb=23, nf=64):
        super().__init__()
        self.conv_first = nn.Conv2d(3, nf, 3, 1, 1)
        self.body = nn.Sequential(*[RRDB(nf) for _ in range(nb)])
        self.conv_body = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up1 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up2 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_hr = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_last = nn.Conv2d(nf, 3, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, True)

    def forward(self, x):
        feat = self.conv_first(x)
        feat = feat + self.conv_body(self.body(feat))
        feat = self.lrelu(self.conv_up1(F.interpolate(feat, scale_factor=2, mode="nearest")))
        feat = self.lrelu(self.conv_up2(F.interpolate(feat, scale_factor=2, mode="nearest")))
        return self.conv_last(self.lrelu(self.conv_hr(feat)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--param", required=True, help="published realesrgan-x4plus.param (same architecture)")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    ck = torch.load(args.weights, map_location="cpu", weights_only=True)
    sd = ck.get("params_ema", ck.get("params", ck))
    net = RRDBNet()
    net.load_state_dict(sd, strict=True)
    net.eval()

    convs = [ln.split() for ln in open(args.param).read().split("\n")[2:] if ln.startswith("Convolution")]
    keys = [k[:-len(".weight")] for k in sd if k.endswith(".weight")]
    if len(convs) != len(keys):
        sys.exit(f"layer count mismatch: {len(convs)} convs vs {len(keys)} weights")
    for ln, k in zip(convs, keys):
        n = int(next(p for p in ln if p.startswith("6="))[2:])
        if n != sd[k + ".weight"].numel():
            sys.exit(f"layer {ln[1]} size {n} != {k} {sd[k + '.weight'].numel()}")
    shutil.copy(args.param, args.out + ".param")
    with open(args.out + ".bin", "wb") as f:
        for k in keys:
            w = sd[k + ".weight"].numpy().astype(np.float16).tobytes()
            f.write(struct.pack("<I", FP16_TAG))
            f.write(w)
            if len(w) % 4:
                f.write(b"\0" * (4 - len(w) % 4))
            f.write(sd[k + ".bias"].numpy().astype(np.float32).tobytes())

    import ncnn

    torch.manual_seed(0)
    x = torch.rand(1, 3, 40, 56)
    with torch.no_grad():
        want = net(x)[0].numpy()
    n = ncnn.Net()
    n.load_param(args.out + ".param")
    n.load_model(args.out + ".bin")
    ex = n.create_extractor()
    ex.input("data", ncnn.Mat(np.ascontiguousarray(x[0].numpy())))
    _, o = ex.extract("output")
    got = np.array(o)
    psnr = 10 * np.log10(1 / max(float(np.mean((np.clip(got, 0, 1) - np.clip(want, 0, 1)) ** 2)), 1e-12))
    print(f"parity PSNR={psnr:.2f} dB")
    if psnr < 45:
        sys.exit("parity check failed")


if __name__ == "__main__":
    main()
