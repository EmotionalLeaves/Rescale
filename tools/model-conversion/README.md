# Model conversion

Converts Real-ESRGAN SRVGG-family weights (`realesr-general-x4v3`, the wdn denoise twin, `realesr-animevideov3`) to ncnn FP16 and checks parity against PyTorch.

```sh
python -m venv .venv && . .venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt

curl -LO https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth
python export_ncnn.py --weights realesr-general-x4v3.pth \
    --out ../../app/src/main/assets/models/realesr-general-x4v3
# -> parity PSNR=64.5 dB
```

Denoise-strength variant (network interpolation with the wdn weights, 0 = none, 1 = full):

```sh
curl -LO https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-wdn-x4v3.pth
python export_ncnn.py --weights realesr-general-x4v3.pth --wdn realesr-general-wdn-x4v3.pth \
    --denoise 0.5 --out /tmp/general-x4v3-dn50
```

Exported models use input blob `in0` and output blob `out0`, RGB in [0, 1]. Keep everything FP16: INT8 causes banding and colour shifts on SR backbones.
