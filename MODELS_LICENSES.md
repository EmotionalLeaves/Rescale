# Model licenses

Model weights are data, but their licenses still apply. Every model shipped or offered by Rescale is listed here.

## Bundled in the APK

| File | Origin | License | Notes |
|---|---|---|---|
| `realesr-general-x4v3.{param,bin}` | [`realesr-general-x4v3.pth`](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0), Real-ESRGAN v0.2.5.0 | BSD-3-Clause, © 2021 Xintao Wang | Converted to ncnn FP16 with `tools/model-conversion/export_ncnn.py` |
| `realesr-animevideov3-x2.{param,bin}` | `realesrgan-ncnn-vulkan-20220424` release, Real-ESRGAN v0.2.5.0 | BSD-3-Clause, © 2021 Xintao Wang | Used as published |
| `realesr-animevideov3-x4.{param,bin}` | same | BSD-3-Clause, © 2021 Xintao Wang | Used as published |

## Downloadable packs (release `models-v1`)

Built by `.github/workflows/models.yml` with `tools/model-conversion/build_packs.py`. Each zip contains a `LICENSE.txt`.

| Pack | Files | Origin | License |
|---|---|---|---|
| `jpeg` | `fbcnn-color` | [FBCNN](https://github.com/jiaxi-jiang/FBCNN) `fbcnn_color.pth` v1.0, converted to ncnn FP16 with the quality factor as a second input (`export_fbcnn.py`) | Apache-2.0 |
| `denoise` | `realesr-general-dn50`, `realesr-general-dn100` | `realesr-general-x4v3` interpolated with `realesr-general-wdn-x4v3` at 50% and 100% | BSD-3-Clause |
| `photo-pro` | `realesrgan-x4plus` | `realesrgan-ncnn-vulkan-20220424` | BSD-3-Clause |
| `anime-pro` | `realesrgan-x4plus-anime` | `realesrgan-ncnn-vulkan-20220424` | BSD-3-Clause |

## Not shipped

- **CodeFormer** (S-Lab License 1.0, non-commercial) isn't GPL/OSI-compatible and won't be bundled. If ever offered, it will only be an opt-in download with a clear notice.
- Community ESRGAN models (e.g. OpenModelDB) need a per-model license check before inclusion.

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
