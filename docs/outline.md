# On-Device Image Upscaler & Restorer for Android — Project Outline

> Working name: **Rescale** (alternatives: *Enhancer*, *Sharpwell*, *Pixelmend*, *Resurface*, *Loupe*)
> Open-source, offline, APK distributed via GitHub Releases.

---

## 1. Project Overview

### Feature list
- Upscale 2×, 4×, 8×, plus **chained passes** (16×, 32×+) with no output cap
- Restoration stages: JPEG de-artifacting, denoise, deblur-lite, optional face restoration
- **Auto mode**: analyzes the image and picks the pipeline
- **Fidelity ↔ Detail slider** (blends a fidelity model with a GAN model)
- Tiled processing streamed to disk, so huge outputs never OOM
- Before/after slider with synced pinch-zoom
- Batch queue, background processing with progress notification, cancel/resume
- Export PNG / JPEG / WebP (lossless or quality slider), EXIF keep/strip
- Share-sheet target ("Upscale with Rescale") and share-out
- Optional downloadable model packs with SHA-256 verification
- No accounts, ads, watermarks, telemetry, or limits; works fully offline
- Honest-output banner: "Extreme upscales invent plausible detail. They don't recover it."

### Target users
- People restoring old or scanned family photos
- Anyone rescuing tiny or compressed images from chats and social media
- Artists and anime fans (anime-specific models)
- Privacy-conscious users who won't upload photos to cloud upscalers

### License
- **App code: GPL-3.0** (keeps forks open, F-Droid-friendly) *or* Apache-2.0 (more permissive). See Open Decisions.
- **Model licenses must be checked separately.** Weights ship as data, but their terms still apply:

| Model | License | OK to bundle in GPL/Apache app? |
|---|---|---|
| Real-ESRGAN (x4plus, x2plus, anime_6B, animevideov3) | BSD-3-Clause | ✅ Yes |
| realesr-general-x4v3 (+ wdn denoise variant) | BSD-3-Clause | ✅ Yes, and small |
| SwinIR (classical/real/JPEG-CAR) | Apache-2.0 | ✅ Yes |
| HAT / HAT-L | Apache-2.0 | ✅ Yes (heavy) |
| BSRGAN | Apache-2.0 | ✅ Yes |
| SCUNet (denoise) | Apache-2.0 | ✅ Yes |
| FBCNN (JPEG artifact removal, quality-aware) | Apache-2.0 | ✅ Yes |
| GFPGAN v1.3/v1.4 | Apache-2.0 (verify bundled StyleGAN2 op notices) | ✅ Generally yes |
| RetinaFace / YuNet (face detection) | MIT / Apache-2.0 | ✅ Yes |
| **CodeFormer** | **S-Lab License 1.0 (non-commercial)** | ⚠️ **Not GPL/OSI-compatible.** Offer only as an optional user-downloaded pack with a clear notice, or skip it |

- Add a `MODELS_LICENSES.md` and an in-app "Model licenses" screen.

---

## 2. Model Strategy

### 2.1 Candidate comparison (approximate; benchmark on your own devices)

| Model | Role | Params | Quality | Mobile speed | Notes |
|---|---|---|---|---|---|
| realesr-general-x4v3 | General upscale | ~1.2M | Good | ⚡ Fast | **Best bundled default.** Has a denoise-strength twin (wdn) you can interpolate |
| realesr-animevideov3 | Anime upscale | ~0.6M | Good (anime) | ⚡⚡ Very fast | Tiny, great for art and line work |
| RealESRGAN_x4plus | General upscale | ~16.7M | Very good | 🐢 Slow | Download pack. Strong texture, can hallucinate |
| RealESRGAN_x4plus_anime_6B | Anime | ~4.5M | Very good | Medium | Download pack |
| BSRGAN | General upscale | ~16.7M | Good, smoother | 🐢 Slow | Fewer fake textures than ESRGAN |
| SwinIR-M (PSNR/classical) | **Fidelity** upscale | ~12M | High fidelity, soft | 🐢🐢 | Transformer; attention is hard on NNAPI/NCNN. Use for blending |
| SwinIR-lightweight | Fidelity upscale | ~0.9M | Decent | Medium | Practical mobile fidelity model |
| HAT / HAT-L | SOTA SR | 20–40M | Best | 🐌 Very slow | "Max quality" pack for flagships; may be impractical |
| SCUNet | Blind denoise | ~9M | Excellent | Slow | Download pack |
| FBCNN | JPEG de-artifact | ~70M | Excellent, quality-aware | Slow | Can be quantized or replaced by SwinIR-CAR-light |
| GFPGAN v1.4 | Face restore | ~85M | Very good | Medium (512² crop only) | Run only on face crops |
| YuNet | Face detect | ~75K | Good | ⚡⚡ | OpenCV Zoo, tiny |

### 2.2 Multi-stage pipeline

```
Input
 └─► [0] Decode + color management (to sRGB, honor EXIF orientation, 16-bit aware)
 └─► [1] Degradation analysis (cheap, CPU)
 └─► [2] JPEG artifact removal   (if blockiness > threshold)
 └─► [3] Denoise                 (if noise sigma > threshold)
 └─► [4] Upscale                 (GAN model, optionally blended with fidelity model)
 └─► [5] Face restoration        (optional: detect → crop/align → restore → paste with feathered mask)
 └─► [6] Artifact suppression    (color correction to source, halo/ringing clamp)
 └─► [7] Detail-strength blend   (user slider: fidelity ↔ GAN)
 └─► [8] Encode + metadata
```

### 2.3 Degradation analysis → Auto mode
Cheap heuristics first, with an optional tiny classifier later:
- **Resolution**: short side < 256 px means "tiny"
- **JPEG blockiness**: 8×8 boundary discontinuity ratio, plus quantization-table estimate from the JPEG header (very reliable)
- **Noise σ**: median absolute deviation of the high-pass residual in flat regions
- **Blur**: variance of Laplacian, normalized by resolution
- **Content type**: anime/illustration vs photo (flat-color histogram ratio, edge sharpness; later a ~200 KB MobileNet classifier)
- **Grayscale/scan detection**: channel correlation and a paper-grain pattern

Auto-mode decision table (example):
| Detected | Pipeline |
|---|---|
| Clean photo, small | general-x4v3 (detail 0.6) |
| Heavy JPEG (Q < 50) | FBCNN/SwinIR-CAR → general-x4v3 (detail 0.4) |
| Noisy/old scan | SCUNet → general-x4v3-wdn → optional faces |
| Anime/illustration | animevideov3 or anime_6B |
| Faces detected + tiny | + GFPGAN at 0.5–0.7 blend |

Always show the chosen pipeline as editable chips ("Auto picked: De-JPEG → Upscale 4× → Faces"), so the user can override it.

### 2.4 Conversion path to mobile

```
PyTorch (.pth) ──► ONNX (opset 17, dynamic H/W or fixed tile size)
                     ├─► NCNN (.param/.bin) via pnnx       ← primary (Vulkan GPU)
                     ├─► ONNX Runtime Mobile (.ort)        ← fallback / NNAPI / XNNPACK
                     └─► LiteRT/TFLite via ai-edge-torch   ← optional (GPU delegate)
```

| Runtime | Pros | Cons | Use |
|---|---|---|---|
| **NCNN + Vulkan** | Proven by Real-ESRGAN-ncnn-vulkan, fast on Adreno/Mali, small, FP16 native | C++/JNI work; transformer ops are patchy | **Primary for CNN models** |
| **ONNX Runtime Mobile** | Widest op coverage (SwinIR/HAT), XNNPACK CPU, NNAPI EP | NNAPI is deprecated in Android 15+ and unreliable; GPU support is weak | Transformers + CPU fallback |
| **LiteRT (TFLite) GPU delegate** | Official Google path, good GPU delegate | Conversion friction (ai-edge-torch), dynamic shapes awkward | Optional experiment |

**Recommendation:** NCNN-Vulkan for all CNN models, ONNX Runtime (XNNPACK) for CPU fallback and transformer models. Use fixed tile shapes (e.g. 128×128 or 192×192 input) to avoid dynamic-shape headaches.

### 2.5 Quantization trade-offs
- **FP32**: reference quality, 2× the size, slow. Use only for CPU validation.
- **FP16**: ~half the size, visually lossless for SR. **Default for everything.**
- **INT8 (PTQ)**: smallest and fastest on NPUs/DSPs, but SR models suffer. Expect **banding in gradients, color shifts, blocky textures, and amplified GAN noise**. Only use with QAT or per-channel quantization plus a calibration set, and only for detectors and classifiers (YuNet, content classifier), not SR backbones.
- **Dynamic-range INT8 weights / FP16 activations**: a middle ground for download-size savings. Validate with LPIPS and gradient-banding tests.

---

## 3. Artifact-Minimization Techniques

### Tiling
- **Overlap**: 16–32 px of input overlap per side (scales to 64–128 px at output for 4×)
- **Feathered blending**: raised-cosine (Hann) weight masks in overlap zones, with accumulation buffers `sum(w·x)/sum(w)`. Never use hard cuts.
- **Padding**: reflect-pad image borders (not zero-pad) by ≥ the model's receptive margin, then crop
- **Tile size alignment**: multiple of 8 (or the window size for Swin/HAT, e.g. 8 or 16) to avoid internal padding artifacts
- **Deterministic tile order** and identical normalization per tile. Never per-tile auto-contrast.

### Color drift prevention
- **Color correction to source**: downscale the output to input size, compute a per-channel mean/std or low-frequency difference against the input, and correct the output's low frequencies (wavelet- or Gaussian-pyramid color fix, as in StableSR's "wavelet color fix")
- **Luminance-only option**: run the model on Y (YCbCr) and upscale chroma with Lanczos. Kills color shifts, and is a good option for scans.
- Clamp to [0,1] in float before quantizing to 8-bit. Dither when writing 8-bit from 16-bit buffers.

### Controlling GAN hallucination
- **Fidelity blend**: `out = α·GAN + (1-α)·Fidelity` (fidelity = SwinIR-PSNR, or just Lanczos for cheap mode). This is the **Detail slider**.
- **Network interpolation** where supported (general-x4v3 ↔ wdn weights, interpolated at load time) for denoise strength
- **Frequency-aware blend**: take low frequencies from the fidelity output and high frequencies from GAN, so structure stays honest
- Face pasting: Poisson or feathered alpha with a soft elliptical mask, plus color matching to the surrounding region

### Halos, ringing, over-sharpening
- Avoid post-sharpening by default
- Optional **halo clamp**: limit output to a local min/max envelope of the bicubic-upscaled input ± tolerance
- Avoid bicubic pre-upscaling before SR models (they expect LR input)

### Checkerboard patterns
- Choose models using pixel-shuffle/nearest+conv upsampling (Real-ESRGAN family is fine)
- Detect via an FFT peak test in the QA suite

### Iterative-pass limits
- Chained passes: **prefer 4× then 2×** over 2×→2×→2×
- Before pass N > 1, apply a light Gaussian/denoise (σ ≈ 0.3–0.5) so the model doesn't "upscale its own textures"
- Lower the detail strength automatically each pass (e.g. 0.7 → 0.5 → 0.35)
- Warn after 2 passes ("Beyond 8×, detail is mostly invented"). Allow continuing, since there's no hard cap.
- After 3+ passes, offer "finish with Lanczos" for the final factor as a low-artifact option

---

## 4. Android Architecture

### Stack
- **Kotlin**, **Jetpack Compose** (Material 3), **Coroutines/Flow**
- **Hilt** for DI, **Room** for job queue and history, **DataStore** for settings
- **WorkManager** + **foreground service** (`dataSync` or `specialUse` FGS type, see Risks) for long jobs
- **NDK/C++** with CMake for NCNN + tiling engine; **JNI** bridge
- **Coil 3** for thumbnails, **Telephoto** (`me.saket.telephoto`) for zoomable before/after
- **ExifInterface** (AndroidX) for metadata
- **libjpeg-turbo / libpng / libwebp** (native) for streaming encode of huge images

### Clean architecture / modules
```
:app                       – Application, navigation, DI wiring
:core:common               – Result types, dispatchers, logging
:core:ui                   – Theme, shared Compose components
:core:data                 – Room, DataStore, repositories
:core:model                – Domain models (Job, PipelineConfig, ModelInfo)
:feature:editor            – Pick image, mode/scale/strength, preview
:feature:compare           – Before/after viewer
:feature:batch             – Queue screen
:feature:models            – Model manager (download, verify, delete)
:feature:settings
:engine:pipeline           – Kotlin orchestration of stages, auto mode
:engine:analysis           – Degradation analysis (Kotlin + native helpers)
:engine:native             – C++: NCNN runtime, tiler, blender, color fix, encoders
:engine:onnx               – ONNX Runtime backend (CPU/XNNPACK)
:worker                    – WorkManager workers, FGS, notifications
```
- **Backend interface**: `InferenceBackend { fun supports(model); fun run(tile): Tile }` with `NcnnVulkanBackend`, `OrtCpuBackend`. Pick at runtime via a startup self-test.

### Acceleration & fallback
1. Vulkan available + device not blocklisted → NCNN Vulkan FP16
2. Else ONNX Runtime + XNNPACK (multi-threaded CPU)
3. NNAPI: skip (deprecated, inconsistent). Revisit vendor NPU paths (Qualcomm QNN via ORT EP) later.
- Run a **first-launch benchmark** (one 128² tile per model) to cache backend choice and estimate times
- Keep a **GPU blocklist** in a remote-free bundled JSON, updated through app releases

### Background processing
- Each job becomes a `CoroutineWorker` with `setForeground()` → progress notification (tile i/N, ETA, Cancel action)
- **Checkpointing**: completed output tiles are saved to disk, so a killed job resumes from the last tile
- **Cancellation**: cooperative check between tiles; native side polls an atomic flag
- Constraints: optional "only while charging" for batch

### Memory management for huge images
- **Never hold the full output Bitmap.** Output can be 30,000 × 40,000 px (~4.8 GB RGBA8).
- Decode input with `BitmapRegionDecoder` (or native libjpeg-turbo scanline decode) **per tile**
- Write output with a **row-band streaming encoder**: process tiles row-band by row-band, keep only one band plus overlap in RAM, and stream rows to libpng / libjpeg-turbo / libwebp
  - WebP max dimension is 16383 px. Auto-fallback to PNG/JPEG above that and tell the user.
  - JPEG max is 65535 px. PNG has no practical limit.
- Scratch tiles in app cache as raw float16 or PNG, cleared after the job
- Preview = a downscaled output written in parallel (≤ 4096 px) for the compare viewer, plus region tiles loaded on zoom (deep zoom via Telephoto's subsampling)
- Respect `ActivityManager.memoryClass`; size the tile and the band to a fraction of available heap and native memory

---

## 5. Model Delivery

- **Bundled in APK (FP16 NCNN)**: realesr-general-x4v3 (~2.5 MB), realesr-animevideov3 (~1.3 MB), YuNet (~0.3 MB). APK target **≤ 30 MB**.
- **Downloadable packs** (GitHub Releases assets, tagged `models-vX`):
  - `pack-photo-pro` (x4plus, BSRGAN), `pack-anime-pro`, `pack-denoise` (SCUNet), `pack-jpeg` (FBCNN), `pack-faces` (GFPGAN), `pack-fidelity` (SwinIR), `pack-max` (HAT)
- **Manifest** `models.json` (bundled + optionally refreshed): id, version, file URLs, **SHA-256**, size, license, min RAM, supported backends, scale, tile requirements
- Downloads via WorkManager with resume (HTTP Range), SHA-256 verification before activation, atomic move into `filesDir/models/`
- **Offline by default**: the network is touched only when the user taps "Download". Show size and license before download. Allow **sideload import** of a pack zip (for no-internet devices and the F-Droid crowd).
- Optional: Android **Play Asset Delivery** isn't relevant (GitHub/F-Droid distribution), so skip it

---

## 6. UI/UX

### Screens
1. **Home**: big "Pick image" (Photo Picker), recent jobs, "Batch" button
2. **Editor**:
   - Auto-analysis card: "Small, heavily compressed photo, 2 faces detected"
   - Mode chips: Auto · Photo · Anime/Art · Old Photo/Scan · Custom
   - Scale: 2× · 4× · 8× · Custom (any factor; ≥ 16× shows the honesty note)
   - Sliders: **Detail** (fidelity ↔ generative), **Denoise**, **Face restore strength**
   - Toggles: Luminance-only, Color-match source, Keep EXIF
   - Output estimate: "12,288 × 16,384 · ~4 min · ~180 MB PNG"
   - **Preview tile**: process a user-chosen 256² region instantly before committing
3. **Compare**: draggable before/after divider, synced zoom/pan, toggle-hold to flip, 1:1 pixel view, side-by-side mode
4. **Batch queue**: add many images, apply one preset, reorder, pause/resume
5. **Export sheet**: PNG / JPEG (quality) / WebP (lossless / quality), EXIF keep/strip (always strip GPS by default), filename pattern, save via **MediaStore** to `Pictures/Rescale`, or share
6. **Models**: installed/available, size, license, delete
7. **Settings**: backend override, tile size, thermal mode, theme, about/licenses

### Integration
- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filter for `image/*`, so it appears in the share sheet
- Quick Settings tile? (later)
- Accessibility: content descriptions, slider value announcements, large-touch compare handle

---

## 7. Performance Targets

Rough targets for **512×512 → 2048×2048 (4×)**, general-x4v3 FP16:

| Tier | Example SoC | Vulkan NCNN | CPU (XNNPACK) |
|---|---|---|---|
| Low | Helio G85 / SD 680 | 15–40 s | 60–150 s |
| Mid | SD 7 Gen 1–3 / Dimensity 7xxx / Tensor G1 | 4–12 s | 20–50 s |
| High | SD 8 Gen 2–4 / Dimensity 9xxx | 1.5–5 s | 8–20 s |

- Heavy models (x4plus/BSRGAN) run **~8–15× slower**; HAT is minutes per megapixel even on flagships
- Treat these as goals to verify. Publish real numbers from a community benchmark screen.

### Thermal & battery
- `PowerManager.getThermalHeadroom()` and `OnThermalStatusChangedListener` (API 29+/30+)
- At `THERMAL_STATUS_MODERATE`, insert pauses between tiles. At `SEVERE`, drop to CPU with fewer threads or pause the job and notify.
- "Battery saver" profile: smaller tiles, 2 CPU threads, pause below 20% unless charging
- Show ETA re-estimated from a moving average of tile times

---

## 8. Repository Structure

```
rescale/
├── app/
├── core/{common,ui,data,model}/
├── feature/{editor,compare,batch,models,settings}/
├── engine/
│   ├── pipeline/
│   ├── analysis/
│   ├── onnx/
│   └── native/
│       ├── CMakeLists.txt
│       ├── src/ (tiler.cpp, blend.cpp, colorfix.cpp, ncnn_backend.cpp, encoders/)
│       └── third_party/ (ncnn as prebuilt or submodule, libpng, libwebp, libjpeg-turbo)
├── worker/
├── models/
│   ├── bundled/            # small FP16 NCNN models (Git LFS)
│   ├── models.json         # manifest
│   └── MODELS_LICENSES.md
├── tools/
│   └── model-conversion/   # Python
│       ├── requirements.txt
│       ├── export_onnx.py
│       ├── onnx_to_ncnn.sh     # pnnx
│       ├── onnx_to_ort.py
│       ├── quantize.py
│       ├── verify_parity.py    # compares PyTorch vs converted outputs (PSNR/LPIPS)
│       └── README.md
├── benchmark/
│   ├── images/             # terrible-input set (see §10), license-clean
│   ├── run_eval.py         # PSNR/SSIM/LPIPS/NIQE/MUSIQ
│   └── results/
├── samples/                # README before/after images
├── docs/ (architecture.md, pipeline.md, adding-a-model.md, performance.md)
├── .github/
│   ├── workflows/ (ci.yml, release.yml, models-release.yml)
│   ├── ISSUE_TEMPLATE/ (bug_report.yml, artifact_report.yml, device_compat.yml, feature_request.yml)
│   └── PULL_REQUEST_TEMPLATE.md
├── fastlane/metadata/android/   # F-Droid metadata
├── README.md
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── SECURITY.md
├── LICENSE
└── CHANGELOG.md
```

### README contents
Hero before/after GIF · features · honesty statement · download (Releases badge, Obtainium, F-Droid) · supported devices · model table with licenses · build-from-source steps · conversion-tools guide · benchmarks · privacy statement · contributing · credits (xinntao/Real-ESRGAN, JingyunLiang/SwinIR, Tencent NCNN, etc.)

### CONTRIBUTING
Code style (ktlint/detekt, clang-format), module boundaries, how to add a model (convert → parity check → manifest entry → license check), artifact-report expectations (include the original image when shareable).

---

## 9. Build & Release

- **Gradle**: Kotlin DSL, **version catalog** (`libs.versions.toml`), convention plugins in `build-logic/`, AGP 8.x, minSdk **26** (Vulkan 1.0 widely available on 28+; CPU fallback below), targetSdk = latest
- **ABI splits**: `arm64-v8a` (primary), `armeabi-v7a` (optional), `x86_64` (emulators). Also publish a universal APK.
- **R8** enabled, with keep rules for JNI
- **Signing**: release keystore base64-encoded in GitHub Secrets (`KEYSTORE_B64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`). Decode in CI; never commit.
- **CI (`ci.yml`)**: on PR → lint, detekt, unit tests, assembleDebug, native build, small model parity test
- **Release (`release.yml`)**: on tag `v*` → build signed release APKs per ABI + universal → `apksigner verify` → SHA-256 sums file → `softprops/action-gh-release` attaches APKs + checksums + changelog section
- **Models (`models-release.yml`)**: on tag `models-v*` → attach model packs + manifest
- **Versioning**: SemVer `vMAJOR.MINOR.PATCH`; `versionCode = MAJOR*10000 + MINOR*100 + PATCH` (+ ABI offset for splits)
- **F-Droid compatibility**:
  - No proprietary dependencies (no Firebase, no Play Services, no Crashlytics)
  - Build NCNN and native libraries from source in Gradle/CMake (F-Droid rejects opaque prebuilt binaries); model weights are data, documented with their source
  - Reproducible builds: pin versions, avoid build timestamps
  - Fastlane metadata folder
  - Also support **Obtainium** (GitHub Releases updates) and an in-app "check GitHub for updates" toggle that is **off by default**

---

## 10. Testing & Quality Evaluation

### Automated tests
- **Unit (JVM)**: tile planner math (overlaps, edges, odd sizes), blend weights sum to 1, auto-mode decision table, manifest parsing, SHA-256 verify, filename/EXIF logic
- **Native (GoogleTest)**: tiler/blender produce **no seams** on a constant-color image and on a gradient (max error < 1/255); color-fix correctness
- **Instrumented (device)**: end-to-end on a 64² image per backend; cancellation; resume after process death; huge-output streaming (e.g. 20k × 20k) without OOM; MediaStore save
- **Parity tests**: PyTorch vs NCNN/ORT outputs on fixed tiles, PSNR > 45 dB (FP16)
- **Compose UI tests**: editor flow, compare slider

### Benchmark set of terrible inputs (license-clean: own photos, CC0, public domain)
- Tiny thumbnails (32–128 px), JPEG Q = 5/10/20/40, double-compressed social-media images
- Heavy Gaussian and real sensor noise (low-light phone shots)
- Motion and defocus blur
- Old scanned prints (dust, grain, fading), halftone newspaper scans
- Anime/line art with JPEG artifacts; screenshots with text
- Faces: small faces in group shots, profile faces, glasses
- Adversarial: fine repeating patterns (fabric, brick, moiré), smooth gradients (sky banding), pure text, high-saturation colors

### Metrics
- **Full-reference** (synthetic degradations of clean HR images): PSNR, SSIM (on Y channel), **LPIPS** (perceptual), DISTS
- **No-reference** (real-world inputs): NIQE, **MUSIQ**, MANIQA, CLIP-IQA (via the `pyiqa` package)
- Track per model/quantization in `benchmark/results/` and fail CI if LPIPS regresses > threshold

### Visual artifact checklist (per release, per model)
- [ ] Tile seams at 100% and 400% zoom
- [ ] Halos/ringing along high-contrast edges
- [ ] Checkerboard/grid (FFT check)
- [ ] Color shift vs source (ΔE on downscaled output)
- [ ] Waxy/plastic skin; over-smoothed hair
- [ ] Hallucinated textures (fake text, fur on smooth surfaces, invented patterns)
- [ ] Gradient banding (sky)
- [ ] Face paste boundaries; mismatched face sharpness vs body
- [ ] Border artifacts at image edges
- [ ] Compounding artifacts after 2 and 3 chained passes

---

## 11. Privacy & Permissions

- **Permissions**:
  - None for reading images: use the **Photo Picker** (`PickVisualMedia`, backported via Play Services-free AndroidX fallback to `ACTION_OPEN_DOCUMENT` on old devices)
  - Saving: **MediaStore** inserts need no permission on API 29+ (`WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28` only)
  - `POST_NOTIFICATIONS` (API 33+) for progress, requested only at first job
  - `FOREGROUND_SERVICE` + the type-specific FGS permission
  - `INTERNET`: needed only for optional model downloads. For a hard guarantee, ship an **"offline" build flavor without INTERNET** (models via sideload).
- **No data leaves the device**: no analytics, no crash reporting (offer "copy crash log" to clipboard for manual issue reports instead)
- Strip GPS from EXIF by default on export
- Clear the scratch cache after jobs and on app start
- Document all of this in `PRIVACY.md` and the README

---

## 12. Roadmap

### MVP (v0.1–0.3)
- Single image, Photo/Anime modes, 2×/4×, general-x4v3 + animevideov3 bundled
- NCNN Vulkan + CPU fallback, tiling with feathered blend, streaming PNG output
- Basic before/after, save/share, WorkManager + notification + cancel
- GitHub Actions signed release

### v1.0
- Auto mode with degradation analysis
- Restoration stages: JPEG (FBCNN or SwinIR-CAR-light), denoise (SCUNet), faces (GFPGAN)
- Detail slider (fidelity blend), color fix, luminance-only mode
- Chained passes (8×, 16×+) with honesty warnings
- Model manager with downloadable verified packs
- Batch queue, resume after process death, thermal management
- JPEG/WebP export, EXIF options, share-sheet target
- F-Droid submission

### Future
- **Video upscaling** (MediaCodec decode → per-frame SR with temporal consistency via animevideov3 / flow-guided blending → MediaCodec encode)
- **Custom model import** (user-provided ONNX/NCNN with a manifest wizard)
- Vendor NPU backends (Qualcomm QNN, MediaTek NeuroPilot via ORT EPs)
- ~~Inpainting for scratches/tears on old photos (LaMa, Apache-2.0)~~ Done in v0.7 with MI-GAN (MIT) + the BOPBTL damage detector
- Colorization for B&W photos (DDColor, Apache-2.0; clearly labeled as invented color)
- Desktop companion (Compose Multiplatform or a CLI sharing the C++ engine), plus LAN offload to a PC GPU, still with no cloud
- ~~Diffusion-based SR (e.g. a distilled one-step model) for flagships, labeled "generative"~~ Done in v0.7 with AdcSR

---

## 13. Risks & Limitations

- **What can't be fixed**: information truly lost (tiny faces become *invented* faces that can look like a different person; text that's unreadable can't be recovered, only guessed; extreme blur). **Never market it as forensic or evidential.** Add a disclaimer on face restoration.
- **Device compatibility**: Vulkan driver bugs (older Mali, some PowerVR) cause black or garbage output. Mitigate with a self-test output checksum on first run, a blocklist, and automatic CPU fallback.
- **NNAPI deprecation** (Android 15): don't depend on it
- **Foreground service policy**: Android 14+ requires a declared FGS type. `dataSync` gains time limits in Android 15 (~6h/day). Consider `specialUse` with justification, or chunk jobs via WorkManager with checkpointing (which you need anyway).
- **Transformer models on mobile**: SwinIR/HAT may be too slow or unsupported in NCNN. Ship them only if parity and speed tests pass.
- **APK size creep**: keep bundled ≤ 3 small models
- **Model licensing pitfalls**: CodeFormer is non-commercial. Some "community" ESRGAN models (OpenModelDB) have mixed or unclear licenses, so check each. GPL app + Apache weights is fine; weights from papers that say "research only" are not.
- **Encoding limits**: WebP 16383 px, JPEG 65535 px, and many gallery apps fail to open > 100 MP images. Warn the user.
- **Storage**: a 16× PNG output can be gigabytes. Pre-check free space.
- **Thermal/battery complaints** on long jobs: default to conservative profiles.
- **Solo-dev scope**: native code (JNI/CMake/NCNN) is the hardest part. Starting from Real-ESRGAN-ncnn-vulkan's approach reduces risk.

---

## Step-by-Step Build Order (Solo Developer)

1. **Model sanity on desktop (Python)**: run realesr-general-x4v3 in PyTorch, export ONNX, convert to NCNN with pnnx, run `realesrgan-ncnn-vulkan` on your PC, and verify parity. *Exit: identical-looking output from PyTorch and NCNN.*
2. **Repo skeleton**: Gradle modules, version catalog, Compose app shell, CI building a debug APK.
3. **Native engine v0**: CMake + NCNN (Vulkan) in `:engine:native`, JNI method `upscaleTile(input, out)`. Run one 128² tile on your phone. *Exit: correct tile on device.*
4. **Tiler + blender**: overlap, reflect pad, Hann feathering, row-band streaming to PNG. Add GoogleTests for seams. *Exit: 4000×4000 → 16000×16000 output with no OOM and no seams.*
5. **Minimal UI**: Photo Picker → scale choice → run → save via MediaStore → simple before/after.
6. **Background jobs**: WorkManager + foreground notification + progress + cancel + checkpoint resume.
7. **CPU fallback**: ONNX Runtime + XNNPACK backend, startup self-test, and backend selection.
8. **First public release (MVP)**: signing secrets, `release.yml`, README with before/after samples. Tag `v0.1.0`.
9. **Artifact controls**: color fix to source, luminance-only mode, Lanczos fidelity blend + Detail slider.
10. **Degradation analysis + Auto mode** (heuristics first).
11. **Model manager**: manifest, downloads with SHA-256, sideload import. Publish the first model packs.
12. **Restoration stages**: JPEG de-artifact → denoise → face detect + GFPGAN + feathered paste.
13. **Chained passes** with inter-pass softening, decaying detail, and warnings.
14. **Compare viewer polish**: deep zoom, synced pan, 1:1.
15. **Batch queue, export options, EXIF handling, share-sheet target.**
16. **Thermal/battery management** + ETA.
17. **Benchmark suite + metrics in CI**, then the artifact-checklist pass.
18. **v1.0 release** + F-Droid submission (reproducible build, metadata).
19. **Future work**: video, custom model import, NPU backends, desktop companion.

---

## Open Decisions

1. **App license**: GPL-3.0 (keeps forks open) or Apache-2.0 (maximally permissive)?
2. **CodeFormer**: skip entirely, or offer as an optional, clearly labeled non-commercial download?
3. **minSdk**: 26 (wider reach, more CPU fallback) or 28/29 (simpler Vulkan + MediaStore)?
4. **Primary runtime**: NCNN-Vulkan (recommended) vs ONNX Runtime-only (simpler build, slower)?
5. **Ship transformer models** (SwinIR/HAT) at all, or keep the lineup CNN-only for speed?
6. **INTERNET permission**: single build with optional downloads, or also an offline flavor?
7. **Bundled models**: which 2–3 go in the APK (APK size vs out-of-box quality)?
8. **Default output format**: PNG (lossless, huge) or high-quality WebP/JPEG (smaller, size limits)?
9. **Face restoration default**: off, or on at a moderate blend when faces are detected?
10. **Chained-pass UX**: free "custom factor" input, or fixed presets (2/4/8/16/32) with warnings?
11. **ABI support**: arm64-only, or also armeabi-v7a for very old phones?
12. **Distribution channels**: GitHub Releases only, or also F-Droid / IzzyOnDroid / Obtainium docs from day one?
13. **Name & branding**.
