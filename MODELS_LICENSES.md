# Model licenses

Model weights are data, but their licenses still apply. Every model shipped or offered by Rescale is listed here.

## Bundled in the APK

| File | Origin | License | Notes |
|---|---|---|---|
| `realesr-general-x4v3.{param,bin}` | [`realesr-general-x4v3.pth`](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0), Real-ESRGAN v0.2.5.0 | BSD-3-Clause, © 2021 Xintao Wang | Converted to ncnn FP16 with `tools/model-conversion/export_ncnn.py` |
| `realesr-animevideov3-x2.{param,bin}` | `realesrgan-ncnn-vulkan-20220424` release, Real-ESRGAN v0.2.5.0 | BSD-3-Clause, © 2021 Xintao Wang | Used as published |
| `realesr-animevideov3-x4.{param,bin}` | same | BSD-3-Clause, © 2021 Xintao Wang | Used as published |

## Downloadable packs (releases `models-v1`, `models-v2`, `models-v3`, `models-v4`)

Built by `.github/workflows/models.yml` with `tools/model-conversion/build_packs.py`. Each zip contains a `LICENSE.txt`.

| Pack | Files | Origin | License |
|---|---|---|---|
| `jpeg` | `fbcnn-color` | [FBCNN](https://github.com/jiaxi-jiang/FBCNN) `fbcnn_color.pth` v1.0, converted to ncnn FP16 with the quality factor as a second input (`export_fbcnn.py`) | Apache-2.0 |
| `denoise` | `realesr-general-dn50`, `realesr-general-dn100` | `realesr-general-x4v3` interpolated with `realesr-general-wdn-x4v3` at 50% and 100% | BSD-3-Clause |
| `photo-pro` | `realesrgan-x4plus` | `realesrgan-ncnn-vulkan-20220424` | BSD-3-Clause |
| `anime-pro` | `realesrgan-x4plus-anime` | `realesrgan-ncnn-vulkan-20220424` | BSD-3-Clause |
| `text` (v4) | `realesrnet-x4plus` | [`RealESRNet_x4plus.pth`](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.1.1), Real-ESRGAN v0.1.1 (the non-GAN generator), written into the published `realesrgan-x4plus.param` layout (`export_rrdb.py`) | BSD-3-Clause |
| `repair` (v3) | `scratch-detect`, `migan-512` | Damage detector: the scratch-detection UNet from [Bringing Old Photos Back to Life](https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life) (`checkpoints/detection/FT_Epoch_latest.pt` in `global_checkpoints.zip`); inpainter: [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN) 512 Places2, from the TorchScript trace published with IOPaint ([Sanster/models](https://github.com/Sanster/models/releases/tag/migan)). Both converted to ncnn FP16 (`export_repair.py`) | MIT / MIT |
| `generative` (v3) | `adcsr-x4` | [AdcSR](https://github.com/Guaishou74851/AdcSR) `net_params_200.pkl` + `halfDecoder.ckpt` ([Hugging Face](https://huggingface.co/Guaishou74851/AdcSR)), converted to one ncnn FP16 network; the last two VAE decoder blocks rescaled by 1/32 for FP16 range (`export_adcsr.py`) | Code Apache-2.0; **weights CreativeML Open RAIL++-M** (see below) |
| `faces` (v2) | `gfpgan-v1.4`, `retinaface-mnet` | [GFPGAN](https://github.com/TencentARC/GFPGAN) `GFPGANv1.4.pth` (clean architecture: no StyleGAN2 CUDA ops), modulated convs rewritten into an equivalent fp16-safe form (`export_gfpgan.py`); [facexlib](https://github.com/xinntao/facexlib) RetinaFace MobileNet-0.25 | Apache-2.0 / MIT |

### About the generative pack's licence

AdcSR's weights are distilled from Stable Diffusion 2.1-base, so they are a "Derivative of the Model" under the [CreativeML Open RAIL++-M License](tools/model-conversion/licenses/CreativeML-OpenRAIL++-M.txt). It allows redistribution and commercial use but attaches **use-based restrictions** (Attachment A: no unlawful use, no harming minors, no defamation or harassment, no harmful disinformation, no fully automated decisions affecting legal rights, no discrimination, no medical advice, no law-enforcement or immigration profiling…), which must be passed on to users. It is not an OSI licence. That's why this model is an opt-in download, never bundled: the app shows a summary of the restrictions and asks the user to agree before downloading, and the pack's `LICENSE.txt` contains the full licence text. The app's own code stays GPL-3.0.

## Not shipped

- **CodeFormer** (S-Lab License 1.0, non-commercial) isn't GPL/OSI-compatible and won't be bundled. If ever offered, it will only be an opt-in download with a clear notice.
- Community ESRGAN models (e.g. OpenModelDB) need a per-model license check before inclusion.
- **LaMa** (Apache-2.0) was the outline's inpainting pick. Its Fourier convolutions have no ncnn equivalent and the big model is ~200 MB; MI-GAN is MIT, made for phones (6 M parameters, 15 MB) and converts cleanly.
- **SD-Turbo / SDXL-Turbo** based upscalers: non-commercial licences. **OSEDiff / S3Diff**: too heavy for phones (≥ 1.3 B parameters with the text encoder); AdcSR is OSEDiff compressed by 74%.

## BSD-3-Clause (Real-ESRGAN)

```
BSD 3-Clause License

Copyright (c) 2021, Xintao Wang
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Other third-party code

- `engine/native/src/main/cpp/third_party/jpge`: jpge JPEG encoder by Rich Geldreich, public domain (or Apache-2.0)

## Runtime

ncnn (Tencent), BSD-3-Clause. Its prebuilt Android libraries also contain glslang (BSD-3-Clause / Apache-2.0 components).
