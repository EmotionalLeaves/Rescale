# Decisions taken for v0.1

The outline's open decisions, as resolved for the MVP. Each one is easy to revisit.

| # | Question | v0.1 choice | Why |
|---|---|---|---|
| 1 | App license | **GPL-3.0** | Keeps forks open, fine for F-Droid, compatible with the BSD/Apache weights |
| 2 | CodeFormer | **Skip** | Non-commercial license |
| 3 | minSdk | **26** | Wider reach; CPU fallback covers devices without good Vulkan |
| 4 | Runtime | **ncnn only** (Vulkan + its own CPU path) | One runtime covers both backends for CNN models; ONNX Runtime comes with transformer models |
| 5 | Transformer models | **Not yet** | CNN-only until parity and speed tests pass on device |
| 6 | INTERNET permission | **None** | No downloads in v0.1, so the default build is fully offline |
| 7 | Bundled models | general-x4v3, animevideov3 x2 + x4 (~5 MB) | YuNet arrives with face restoration |
| 8 | Output format | **PNG** | Lossless and streamable to any size; JPEG/WebP export in v1.0 |
| 9 | Face restoration default | n/a | Not in v0.1 |
| 10 | Chained passes UX | n/a | 2× and 4× only in v0.1 |
| 11 | ABIs | arm64-v8a, armeabi-v7a, x86_64 + universal | v7a costs little and covers old phones |
| 12 | Distribution | GitHub Releases (+ Obtainium); F-Droid later | F-Droid needs ncnn built from source instead of the prebuilt |
| 13 | Name | **Rescale** | Working name from the outline |

## Deviations from the outline's stack

- **No Hilt/Room/DataStore yet.** With one screen flow and one job, a ViewModel, WorkManager's own database and SharedPreferences are enough. These come in when batch and history land.
- **Fewer modules.** `feature:*` and `core:ui/data` are folded into `:app` until there is enough UI to split.
- **Streaming PNG encoder instead of libpng.** About 150 lines on top of zlib (which the NDK ships), with no extra native dependency.
- **ncnn is a pinned prebuilt** downloaded at build time. F-Droid will need it built from source (`third_party` submodule + CMake), tracked for v1.0.
