# Changelog

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
