# Changelog

## v0.5.0

Export, batch, deep zoom and thermal management (build-order steps 14–16).

- **Export formats:** PNG, JPEG (streaming encoder, any size up to 65,535 px, without loading the image into memory) and WebP (lossy or lossless, up to 16,383 px). Pick a format and quality when you tap Save or Share
- **EXIF:** "Keep camera info" copies camera, date and exposure details from the original. **Location is always removed**
- Saved files are named after the original ("IMG_1234_4032x3024.jpg")
- **Batch queue:** pick up to 50 images. Each gets its own Auto settings (or a fixed 2×/4×/8×), runs in the background one after another, and is saved to Pictures/Rescale. Optional "only while charging". A failed image doesn't stop the rest
- **Deep zoom:** zooming past the preview decodes the visible region from the full-resolution result, and the **1:1** button shows real output pixels
- **Thermal management:** pauses between tiles when the phone runs hot (thermal status and headroom) or the battery is under 20% and not charging. The notification says "Cooling down"
- **Gentle mode** (menu): 2 threads, smaller tiles and short pauses, for long jobs without a hot phone
- ETA now uses a moving average of tile times, so it adapts when throttling starts

## v0.4.0

Model packs, restoration stages and chained passes (build-order steps 11–13).

- **Model packs** (Menu → Model packs): optional downloads published as the `models-v1` release, pinned by SHA-256 in the app and verified file by file before use. Downloads resume after interruptions, and packs can also be **imported** from a zip for phones without internet. The internet permission is used only when you tap Download.
  - **JPEG repair** (FBCNN, 92 MB): **De-JPEG** stage, driven by the JPEG quality read from the file
  - **Denoise** (4 MB): **Denoise** stage for grainy photos (50% or full strength)
  - **Photo Pro** / **Anime Pro**: Real-ESRGAN x4plus models, sharper texture, slower
- **Restoration chips** in the editor. Auto turns on De-JPEG and Denoise when it sees compression or noise and the pack is installed
- **8×, 16× and 32×** via chained passes (4× then 2×, never 2×2×2). Later passes get a light pre-blur and decaying Detail so the model doesn't sharpen its own artifacts, with warnings beyond 8× and an option to finish 32× with a plain resize
- The engine runs any chain of passes with memory-mapped intermediates on disk, so a chain doesn't need the intermediate image in RAM

## v0.3.0

Auto mode (build-order step 10).

- **Auto** is the default image type. It analyses a full-resolution crop of the image and picks Photo or Anime/Art, the scale, Detail and colour options, then shows what it picked ("Small, heavily compressed photo → Upscale 4× (Photo)")
- Analysis covers the JPEG quality read from the file's quantisation table, 8×8 blockiness, noise level (Immerkær), sharpness, palette and flatness (art vs photo), and black-and-white detection
- Damaged photos get a lower Detail so the model doesn't sharpen artifacts into fake texture. Black-and-white images get "Keep original colors"
- Picking Photo or Anime/Art switches to manual. Tapping Auto restores its choice

## v0.2.0

Artifact controls (build-order step 9).

- **Detail slider**: blends the model's output with a faithful bicubic upscale (default 80% for photos, 100% for anime)
- **Match source colors** (on by default): pulls large-scale colour and brightness back to the original, fixing model colour drift
- **Keep original colors**: sharpens brightness only and takes colour from the source (good for scans and skin tones)
- All three run per tile before blending, so they stay seam-free (66–69 dB tiled vs single tile)
- Desktop CLI gains `--detail`, `--colorfix` and `--luma`

## v0.1.0

First release (MVP).

- Photo (realesr-general-x4v3) and Anime/Art (realesr-animevideov3) modes, 2× and 4×
- ncnn Vulkan backend with GPU self-test and automatic CPU fallback
- Tiled engine: reflect padding, Hann-feathered overlaps, streaming PNG output (no full-size bitmap in memory)
- Background processing with progress notification, ETA and cancel
- Before/after compare viewer with synced zoom
- Save to Pictures/Rescale, share, and "Upscale with Rescale" share target
- EXIF orientation and transparency support
- No INTERNET permission
