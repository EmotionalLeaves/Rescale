# Changelog

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
