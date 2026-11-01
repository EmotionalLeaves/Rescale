#!/usr/bin/env python3
"""Convert facexlib's RetinaFace (MobileNet-0.25, MIT) face detector to ncnn FP16.

    git clone --depth 1 https://github.com/xinntao/facexlib
    curl -LO https://github.com/xinntao/facexlib/releases/download/v0.1.0/detection_mobilenet0.25_Final.pth
    python export_retinaface.py --repo facexlib --weights detection_mobilenet0.25_Final.pth --out out/retinaface-mnet

Input  in0: 640×640 RGB in [0,1] (letterboxed). BGR conversion and mean subtraction are baked in.
Outputs out0: (16800, 4) box deltas, out1: (16800, 2) softmax scores, out2: (16800, 10) landmarks.
Priors: steps [8,16,32], min sizes [[16,32],[64,128],[256,512]], variance [0.1,0.2]
(decoded in engine/native/src/main/cpp/core/faces.cpp).
"""
import argparse
import os
import shutil
import sys
import tempfile

import numpy as np
import torch
from torch import nn

SIZE = 640


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    sys.path.insert(0, os.path.abspath(args.repo))
    # Skip facexlib/__init__ (it needs a generated version.py and unrelated deps).
    import types

    pkg = types.ModuleType("facexlib")
    pkg.__path__ = [os.path.join(os.path.abspath(args.repo), "facexlib")]
    sys.modules["facexlib"] = pkg
    from facexlib.detection.retinaface import RetinaFace

    net = RetinaFace(network_name="mobile0.25", half=False, phase="test", device="cpu")
    sd = torch.load(args.weights, map_location="cpu", weights_only=True)
    sd = {k.replace("module.", "", 1): v for k, v in sd.items()}
    net.load_state_dict(sd, strict=True)
    net.eval()

    class Wrapper(nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m
            self.register_buffer("mean", torch.tensor([104.0, 117.0, 123.0]).view(1, 3, 1, 1))

        def forward(self, x):  # RGB [0,1] -> BGR [0,255] - mean
            x = x.flip(1) * 255.0 - self.mean
            loc, conf, landm = self.m(x)
            return loc.reshape(-1, 4), conf.reshape(-1, 2), landm.reshape(-1, 10)

    w = Wrapper(net).eval()
    import pnnx

    x = torch.rand(1, 3, SIZE, SIZE)
    work = tempfile.mkdtemp()
    try:
        pt = os.path.join(work, "model.pt")
        pnnx.export(w, pt, (x,), fp16=True)
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        shutil.copy(os.path.join(work, "model.ncnn.param"), args.out + ".param")
        shutil.copy(os.path.join(work, "model.ncnn.bin"), args.out + ".bin")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    # Parity where it matters: decoded score and landmarks of a real face (NASA portrait,
    # public domain). Raw deltas on random noise differ more under FP16 and mean nothing.
    import itertools

    import ncnn
    from PIL import Image
    from skimage import data

    img = Image.fromarray(data.astronaut())
    lb = Image.new("RGB", (SIZE, SIZE))
    lb.paste(img, (0, 0))
    xr = torch.from_numpy(np.asarray(lb, dtype=np.float32) / 255).permute(2, 0, 1)[None].contiguous()
    pri = []
    for step, ms in zip([8, 16, 32], [[16, 32], [64, 128], [256, 512]]):
        f = SIZE // step
        for i, j in itertools.product(range(f), range(f)):
            for mm in ms:
                pri.append([(j + 0.5) * step / SIZE, (i + 0.5) * step / SIZE, mm / SIZE, mm / SIZE])
    pri = np.array(pri)

    def best(conf, lm):
        k = int(conf[:, 1].argmax())
        return float(conf[k, 1]), (pri[k, :2] + lm[k].reshape(5, 2) * 0.1 * pri[k, 2:]) * SIZE

    with torch.no_grad():
        _, c1, l1 = [t.numpy() for t in w(xr)]
    arr = np.ascontiguousarray(xr[0].numpy())  # keep alive: ncnn.Mat wraps the buffer
    n = ncnn.Net()
    n.load_param(args.out + ".param")
    n.load_model(args.out + ".bin")
    ex = n.create_extractor()
    ex.input("in0", ncnn.Mat(arr))
    c2 = np.array(ex.extract("out1")[1])
    l2 = np.array(ex.extract("out2")[1])
    s1, p1 = best(c1, l1)
    s2, p2 = best(c2, l2)
    err = float(np.abs(p1 - p2).max())
    print(f"wrote {args.out}.param/.bin  face score {s1:.3f}/{s2:.3f}, landmark error {err:.2f} px")
    if s2 < 0.97 or err > 1.0:
        sys.exit("parity check failed")


if __name__ == "__main__":
    main()
