# Architecture

## Flow of one job

1. **Pick.** `RescaleViewModel.pick()` copies the chosen image into private storage (so the background job never loses its URI grant) and reads size and EXIF orientation.
2. **Enqueue.** `UpscaleWorker.enqueue()` starts a unique WorkManager job (one at a time for now).
3. **Plan.** `Planner.plan(mode, scale)` picks the model and an optional downsample factor (Photo 2× = 4× model, then 2:1 area downsample, which is what Real-ESRGAN's `--outscale` does).
4. **Backend.** `BackendSelector` loads the model on Vulkan when available. The first time per model and app version it runs a fixed probe tile on GPU and CPU and only trusts the GPU if they match. A model error on GPU flips the app to CPU.
5. **Decode.** The full input is decoded once as non-premultiplied RGBA. The EXIF rotation is *not* applied to pixels: the engine reads through an orientation-aware view instead.
6. **Engine** (C++, `engine/native/src/main/cpp/core`):
   - `planAxis` lays tiles out so they overlap by ≥ `overlap` px and the last tile ends exactly at the border.
   - Each tile is reflect-padded by `pad` px, run through the model, and cropped.
   - Weights are separable 1-D raised-cosine ramps inside overlaps (none at image borders). The normaliser is the product of two precomputed 1-D sums, so there's no 2-D weight buffer.
   - Only one **band** (tile height × scale rows × output width, RGB float) lives in memory. When a tile row finishes, all rows above the next tile row are final and are pushed through the sink chain, and the band slides down. The tile size shrinks automatically to fit the memory budget.
   - Sink chain: `[BoxDownsample] → Tee → PngWriter (output)` and `→ BoxDownsample → PngWriter (≤ 2048 px preview)`.
   - `PngWriter` is a small streaming encoder (Paeth filter, zlib deflate), so there's no libpng dependency.
   - Cancellation is an atomic flag checked between tiles. Progress is a JNI callback per tile.
7. **Result.** The worker returns the paths. The UI shows the preview in the compare viewer, and Save copies the full PNG into MediaStore.

## Testing

- `engine/native/src/test/cpp/engine_test.cpp` (host, ASan + UBSan): tile planning, reflect indexing, seam-free stitching (exact on flat colour, ≤ 1/255 on gradients), feather smoothness with deliberately inconsistent tiles, orientation mapping, alpha and downsample, cancellation, and a PNG round-trip with CRC and Paeth decoding.
- `core/model` JVM tests: planner and estimates.
- `tools/host-cli/compare_tiling.py`: the real model through the real engine vs untiled PyTorch (≈ 60 dB).

## Memory

Input is capped at 50 MP for now (decoded once, ~200 MB of native memory at the cap). The output is unbounded in principle: memory is set by the band, not the image. Disk needs about 1.5 bytes per output pixel, which is checked before starting.

## Known gaps (planned)

Resume after process death (checkpointed bands), thermal throttling, region-decoded inputs > 50 MP, JPEG/WebP streaming export, deep zoom on the full-resolution output, ONNX Runtime backend for transformer models.
