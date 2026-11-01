"""SRVGGNetCompact, the architecture behind realesr-general-x4v3 and realesr-animevideov3.

Re-implemented here (BSD-3-Clause, from xinntao/Real-ESRGAN) so the conversion
tools don't need basicsr installed.
"""
import torch
from torch import nn
from torch.nn import functional as F


class SRVGGNetCompact(nn.Module):
    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16, upscale=4, act_type="prelu"):
        super().__init__()
        self.upscale = upscale
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(self._act(act_type, num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(self._act(act_type, num_feat))
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    @staticmethod
    def _act(kind, n):
        if kind == "relu":
            return nn.ReLU(inplace=True)
        if kind == "leakyrelu":
            return nn.LeakyReLU(0.1, inplace=True)
        return nn.PReLU(num_parameters=n)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        return out + F.interpolate(x, scale_factor=self.upscale, mode="nearest")


def load_state(path):
    sd = torch.load(path, map_location="cpu", weights_only=True)
    for key in ("params_ema", "params"):
        if key in sd:
            return sd[key]
    return sd


def interpolate_state(a, b, alpha):
    """Network interpolation (DNI): alpha*a + (1-alpha)*b, key by key."""
    return {k: alpha * a[k] + (1 - alpha) * b[k] for k in a}
