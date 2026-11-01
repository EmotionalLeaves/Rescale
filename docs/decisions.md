# Decisions taken for v0.1

The outline's open decisions, as resolved for the MVP. Each one is easy to revisit.

| # | Question | v0.1 choice | Why |
|---|---|---|---|
| 1 | App license | **GPL-3.0** | Keeps forks open, fine for F-Droid, compatible with the BSD/Apache weights |
| 2 | CodeFormer | **Skip** | Non-commercial license |
| 3 | minSdk | **26** | Wider reach; CPU fallback covers devices without good Vulkan |
| 4 | Runtime | **ncnn only** (Vulkan + its own CPU path) | One runtime covers both backends for CNN models; ONNX Runtime comes with transformer models |
| 5 | Transformer models | **Not yet** | CNN-only until parity and speed tests pass on device |
| 6 | INTERNET permission | **Single build with INTERNET** (from v0.4) | Used only for user-initiated pack downloads; packs are also importable from a zip. No offline flavor, by the owner's call |
| 7 | Bundled models | general-x4v3, animevideov3 x2 + x4 (~5 MB) | YuNet arrives with face restoration |
| 8 | Output format | **PNG** | Lossless and streamable to any size; JPEG/WebP export in v1.0 |
| 9 | Face restoration default | n/a | Not in v0.1 |
| 10 | Chained passes UX | **Fixed presets 2/4/8/16/32** (v0.4) | Each maps to a known-good pass plan (4× then 2×); warnings beyond 8× |
| 11 | ABIs | arm64-v8a, armeabi-v7a, x86_64 + universal | v7a costs little and covers old phones |
| 12 | Distribution | GitHub Releases (+ Obtainium); F-Droid later | F-Droid needs ncnn built from source instead of the prebuilt |
| 13 | Name | **Rescale** | Working name from the outline |

## Deviations from the outline's stack

- **No Hilt/Room/DataStore yet.** With one screen flow and one job, a ViewModel, WorkManager's own database and SharedPreferences are enough. These come in when batch and history land.
- **Fewer modules.** `feature:*` and `core:ui/data` are folded into `:app` until there is enough UI to split.
- **Streaming PNG encoder instead of libpng.** About 150 lines on top of zlib (which the NDK ships), with no extra native dependency.
- **ncnn is a pinned prebuilt** downloaded at build time for speed. `-PncnnSource=` builds it from source instead (`scripts/fetch-ncnn-source.sh`), which is what F-Droid will use. A weekly workflow keeps that path green, and the draft metadata is in `docs/fdroid/`.

## v0.7: filling in missing information

- **Inpainting: MI-GAN, not LaMa.** LaMa's fast Fourier convolutions have no ncnn layer (they'd need DFT-as-matmul at a fixed size) and big-lama is ~200 MB. MI-GAN (ICCV 2023, MIT) was designed for phones: 6 M parameters, plain convolutions, 512² windows, 73 dB ncnn parity.
- **Damage detection from "Bringing Old Photos Back to Life"** (MIT). It also fires on thin real structures (a lattice tower: up to 11% of a clean photo with the two-scale pass), so it's never applied silently: turning Repair on opens the mask editor showing the detections, and Auto only *suggests* Repair (fine-pass score ≥ 5%; our clean test photos peak at 4.6%, scratched ones start at 9%).
- **Large holes** are filled coarse-to-fine: the image is downscaled until the hole is at most ~40 px from known pixels, filled, and the result upsampled into the hole; the full-resolution pass then redoes only the hole's 40-px rim and thin damage.
- **Generative upscaler: AdcSR**, OSEDiff distilled into a pruned SD 2.1 UNet + half-width VAE decoder (456 M parameters, one step, no text encoder, no prompt). Prompt-free one-step models are the only diffusion SR that's practical on a phone; AdcSR is the smallest with competitive quality, and its licence (Open RAIL++-M) permits redistribution with use restrictions, unlike SD-Turbo-based models.
- **One network file, converted in six pieces.** pnnx needs more than 7 GB of RAM for the whole graph (mostly the decoder's 512² activations), so the network is traced as six pieces with explicit skip tensors and the ncnn files are merged (ncnn's `.bin` is the layers' weights in `.param` order, so bins concatenate). Parity 74–85 dB at two tile shapes.
- **FP16 on phone GPUs.** The decoder's last blocks reach ~69,000 on flat tiles (FP16 max 65,504). Their residual stream is divided by 32 (conv2 weights and biases, shortcut biases, upsampler biases; GroupNorm eps ÷ 1024), which is exact in FP32. Checked by running the whole network in half precision on a flat tile: NaN before, 79 dB after.
- **No CPU/GPU self-test for the 900 MB model** (it would hold a second copy in RAM). It follows the verdict of the self-tested built-in model, and any NaN/inf tile fails the job and switches the app to CPU.
- **Tiles of 96 + 2×16 px** (128 in, what AdcSR was trained on). Each output tile's per-channel mean is matched to its input tile and its contrast pulled back by up to 20% (AdcSR's own colour normalisation, per tile and without amplifying flat tiles).
