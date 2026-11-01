# Contributing

Thanks for helping! Issues and PRs are welcome.

## Ground rules

- No proprietary dependencies (no Firebase, Play Services, Crashlytics), no analytics, no network calls the user didn't ask for.
- Keep module boundaries: UI in `:app`, background work in `:worker`, orchestration in `:engine:pipeline`, C++ in `:engine:native`, pure logic in `:core:model`.
- Kotlin: official style. C++: C++17, `clang-format` (Google style, 120 cols).

## Before opening a PR

```sh
./gradlew :core:model:test assembleDebug
cmake -S engine/native/src/test/cpp -B build/host && cmake --build build/host && ctest --test-dir build/host
```

If you touch tiling, blending or the runner, also run `tools/host-cli/compare_tiling.py` (expect > 50 dB).

## Adding a model

1. Confirm the license allows redistribution in a GPL-3.0 app (BSD/MIT/Apache are fine; "research only" or non-commercial are not).
2. Convert: `tools/model-conversion/export_ncnn.py` (or the equivalent pnnx steps for other architectures). FP16 by default; don't INT8-quantize SR backbones.
3. Parity: the script checks PyTorch vs ncnn (must be > 40 dB).
4. Add the files to `app/src/main/assets/models/`, a `ModelSpec` in `core/model`, and a row in `MODELS_LICENSES.md`.

## Reporting artifacts

Use the "Artifact report" issue template. Attach the original image if you can share it, plus mode, scale, device, and whether it ran on GPU or CPU (shown on the result screen).
