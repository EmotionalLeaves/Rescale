# Rescale

**On-device image upscaler for Android.** Make small, compressed or old images 2× or 4× bigger, entirely on your phone. No uploads, no accounts, no ads, no watermarks, and no internet permission at all.

![Bicubic vs Rescale Photo vs Rescale Anime, 128 px input upscaled 4×](samples/astronaut-128px-4x.jpg)
<sub>128 × 128 JPEG (q60) → 512 × 512. Left: bicubic. Middle: Photo mode. Right: Anime/Art mode. Source: NASA, public domain.</sub>

> **Honesty note:** upscaling *invents* plausible detail. It doesn't recover detail that was never captured. Don't treat results as evidence, especially faces and text.

## Features (v0.2)

- **Photo** mode (Real-ESRGAN general-x4v3) and **Anime / Art** mode (Real-ESRGAN animevideov3), at 2× or 4×
- **GPU (Vulkan) via ncnn**, with automatic CPU fallback. On first use, a self-test compares GPU and CPU output and switches to CPU on devices whose drivers return garbage
- **Tiled processing streamed to disk.** Outputs are written row band by row band straight into a PNG, so the full output is never held in memory. Tiles overlap and are cross-faded with Hann ramps, and borders are reflect-padded, so there are no seams (verified at < 1/255 vs whole-image inference)
- **Detail slider** (faithful ↔ generated), **Match source colors** and **Keep original colors** controls to tame GAN artifacts
- Runs in the **background** with a progress notification (tile *i/N*, ETA, Cancel)
- **Before/after viewer** with draggable divider, synced pinch-zoom and pan, and double-tap zoom
- Save to `Pictures/Rescale`, or share. Rescale also appears in the share sheet ("Upscale with Rescale")
- Respects EXIF orientation, keeps transparency (alpha is upscaled separately)

Planned next (see [the outline](docs/outline.md)): Auto mode, JPEG/noise/face restoration stages, chained 8×/16×+ passes, model packs, batch queue, JPEG/WebP export.

## Download

Grab the APK from [Releases](https://github.com/EmotionalLeaves/Rescale/releases). Most phones want `app-arm64-v8a-release.apk`; `app-universal-release.apk` works everywhere. Each release lists SHA-256 checksums. You can also track releases with [Obtainium](https://github.com/ImranR98/Obtainium).

Requires Android 8.0+ (API 26). Vulkan is used where available.

## Models

| Mode | Model | Size (FP16) | License |
|---|---|---|---|
| Photo | realesr-general-x4v3 | 2.4 MB | BSD-3-Clause |
| Anime / Art | realesr-animevideov3 x2, x4 | 1.2 MB each | BSD-3-Clause |

See [MODELS_LICENSES.md](MODELS_LICENSES.md). The Photo model was converted with [`tools/model-conversion`](tools/model-conversion) (PyTorch → pnnx → ncnn, 64 dB parity).

## Building from source

Requirements: JDK 17+, Android SDK (platform 37), NDK **30.0.16248370**, CMake 3.31.6.

```sh
./gradlew assembleDebug        # downloads the pinned ncnn prebuilt into engine/native/third_party/ on first run
./gradlew :core:model:test     # JVM unit tests
```

Native engine tests (host, no Android needed):

```sh
cmake -S engine/native/src/test/cpp -B build/host && cmake --build build/host && ctest --test-dir build/host
```

Run the real engine on a PC and compare it to untiled PyTorch output: see [`tools/host-cli`](tools/host-cli).

### Release signing

Adding a new `## vX.Y.Z` heading at the top of CHANGELOG.md and pushing to `main` (or pushing a tag `vX.Y.Z`) runs `release.yml`, which builds signed per-ABI and universal APKs with SHA-256 sums and publishes a GitHub Release. By default it signs with `keystore/rescale-public.jks`, a deliberately **public** key (same approach as Hypnotica) so every build installs over the last one. For a private key, add the repository secrets `KEYSTORE_B64` (base64 of a `.jks`), `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Note that switching keys means uninstalling once. Locally, a git-ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) overrides the public key.

## Architecture

```
:app              Compose UI, share target, save/share
:worker           WorkManager job, foreground notification, progress, cancel
:engine:pipeline  model install, GPU self-test / backend choice, decode, run
:engine:native    C++: ncnn runner, tiler, Hann blender, streaming PNG, JNI
:core:model       modes, models, planner (pure Kotlin, unit-tested)
tools/            model conversion (Python), desktop CLI of the engine
```

More in [docs/architecture.md](docs/architecture.md).

## Privacy

Rescale has no `INTERNET` permission, no analytics and no crash reporting. Images never leave the device. See [PRIVACY.md](PRIVACY.md).

## Credits

[Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) by Xintao Wang et al. · [ncnn](https://github.com/Tencent/ncnn) by Tencent · [pnnx](https://github.com/pnnx/pnnx)

## License

GPL-3.0. Model weights carry their own licenses (all BSD-3-Clause today).
