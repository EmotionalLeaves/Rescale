# Benchmarks and quality gates

`tools/host-cli/quality_suite.py` runs on every push (CI job **Quality gates**). It uses the desktop build of the app's own C++ engine with the bundled models, so it tests the same tiling, blending and post-processing code that runs on the phone (CPU ncnn instead of Vulkan).

| Gate | What it catches | Threshold |
|---|---|---|
| parity | conversion or tiling bugs: tiled ncnn vs untiled PyTorch | ≥ 50 dB (typically 62) |
| tiling | seams/blend bugs: small tiles vs one tile, with every post-processing option | ≥ 55 dB and worst pixel ≤ 4 levels (typically 1) |
| seams | visible tile lines on a smooth gradient (validated: a 2-level seam scores 1.4) | ≤ 0.8 |
| checker | checkerboard / grid artifacts on a flat image | amplitude ≤ 0.25 levels |
| color | colour drift with "Match source colors" | ≤ 1.5 levels per channel (typically 0.2) |

Results land in the `quality-results` artifact (`latest.json`), with CPU throughput per model for reference. Phone numbers come from the app itself: the result screen shows time and backend.

Images are public domain (scikit-image samples: NASA astronaut, coffee, Chelsea the cat) or synthetic. See `docs/artifact-checklist.md` for the manual per-release review.
