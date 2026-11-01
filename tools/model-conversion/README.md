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

## Repair pack (damage detector + MI-GAN)

```sh
git clone --depth 1 https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life bopbtl
curl -LO https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life/releases/download/v1.0/global_checkpoints.zip
curl -LO https://github.com/Sanster/models/releases/download/migan/migan_traced.pt
python export_repair.py --repo bopbtl --checkpoints global_checkpoints.zip --migan migan_traced.pt --out out/
# -> scratch-detect: mask disagreement 0.000% (fp16 0.004%); migan-512: parity 72.8 dB (fp16 67.7 dB)
```

## Generative pack (AdcSR)

Needs `diffusers` and ~7 GB of RAM; takes ~10 minutes on 2 CPU cores.

```sh
git clone --depth 1 https://github.com/Guaishou74851/AdcSR adcsr
curl -LO https://huggingface.co/Guaishou74851/AdcSR/resolve/main/weight/net_params_200.pkl
curl -LO https://huggingface.co/Guaishou74851/AdcSR/resolve/main/weight/pretrained/halfDecoder.ckpt
curl -L -o unet_config.json https://huggingface.co/sd2-community/stable-diffusion-2-1-base/resolve/main/unet/config.json
python export_adcsr.py --repo adcsr --weights net_params_200.pkl --decoder halfDecoder.ckpt \
    --unet-config unet_config.json --out out/adcsr-x4
# -> parity 73.8 dB (128×128), 75.0 dB (96×160), 85.1 dB (flat tile)
```

See the docstring in `export_adcsr.py` for the FP16 rescaling and why the network is converted in pieces and merged. Run it through the real engine with the host CLI:

```sh
rescale_cli out/adcsr-x4 in0 out0 4 in.rgba W H out.png --tile 96 --pad 16 --align 16 --norm 2 -1 --statmatch 1 --lowmem 1
```
