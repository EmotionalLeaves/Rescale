# Changelog

## v0.8.0

Sharper text.

- New **Text** image type, for screenshots, documents, signs and scans
- **Text** pack (32 MB, `models-v4`): RealESRNet x4plus, Real-ESRGAN's generator before GAN training. It sharpens letters without the made-up texture and ringing GAN models add around text. On a JPEG-compressed screenshot it beats every other model in the app (26.6 dB vs 25.2 for the Photo model, 21.8 for bicubic)
- Without the pack, Text mode uses the built-in Photo model and offers the download

## v0.7.1

- Fixes "Download failed (HTTP 404)" for the Repair and Generative detail packs: v0.7.0 pointed at packs that weren't published yet, and its checksums don't match the published `models-v3` files. This build has the published checksums
- Generated detail and Repair are unchanged

## v0.7.0

Fills in what's missing: damage repair and generative detail.

- **Repair** pack (79 MB, `models-v3`): finds scratches, cracks, tears and dust spots on old photos (the detector from Microsoft's "Bringing Old Photos Back to Life", MIT) and fills them in with **MI-GAN** (Picsart, MIT), a mobile inpainting GAN. Runs on the input before everything else, so the repaired photo is what gets upscaled and face-restored
- **Mask editor:** turning on Repair shows what the detector found (pink). Paint over anything else you want gone (a blemish, a date stamp, a stray object), or erase detections that aren't damage (railings, wires, highlights)
- Thin damage is filled at full resolution in overlapping 512² windows; holes too big for one window are filled on a downscaled copy first, and that fill becomes context for a full-resolution pass over the hole's edge
- On black-and-white and sepia photos, filled pixels are put back on the photo's own brightness→tone curve (the inpainter likes to add blue)
- Auto *suggests* Repair when it sees a lot of damage, but never turns it on by itself: the detector also fires on thin real things like lattice towers
- **Generative detail** pack (804 MB, `models-v3`): **AdcSR**, a one-step diffusion upscaler distilled from Stable Diffusion 2.1 (CVPR 2025). Real hair strands, fur, skin and fabric where the GAN models smear. One pass, no prompt, no noise. Photo mode, first 4× only; Auto picks it for small photos (≤ 0.4 MP) when installed. Its weights carry the CreativeML Open RAIL++-M licence, so the app shows the use restrictions and asks you to agree before downloading. Needs 6 GB of RAM
- AdcSR's VAE decoder overflows FP16 on flat areas (~69,000 vs a 65,504 limit: NaN tiles on phone GPUs). The last two decoder blocks are rescaled by 1/32, exact in FP32, peak now ~2,200. Each tile's mean and contrast are matched to its input, as AdcSR does per image
- Any tile that comes back with NaN/inf now fails the job (and switches the app to CPU) instead of writing black squares
- Big models load without ncnn's winograd/sgemm weight transforms, which would multiply their memory 2–4×
- A downloaded pack is verified and unpacked in place, without a second temporary copy
- Progress shows "Repairing damage" while the Repair stage runs

## v0.6.0

Face restoration and resumable jobs.

- **Face restore** pack (155 MB, `models-v2`): RetinaFace finds faces and GFPGAN v1.4 restores them. Each face is aligned to the FFHQ template, restored at 512², and feathered into the final output as it streams to disk. Strength slider, default 60%. Faces already larger than 400 px in the original are left alone
- **Auto** turns on Faces when it finds small faces in a photo (and the pack is installed)
- Detection uses GFPGAN's own 0.97 confidence threshold. A lower one painted a face onto a rocket in testing
- An honest notice that restored faces are partly invented
- GFPGAN's StyleGAN2 layers were rewritten into an exactly equivalent form that stays inside FP16 range on phone GPUs (76–78 dB vs the original)
- **Resume after process death:** each finished pass of a chain is checkpointed, so if Android kills a long job, it picks up after the last completed pass instead of starting over
- Existing packs stay on `models-v1`, so nothing needs re-downloading

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
