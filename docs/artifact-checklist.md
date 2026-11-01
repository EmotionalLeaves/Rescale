# Per-release visual checklist

The automated gates (see `benchmark/README.md`) cover seams, grids, colour drift and parity. These need human eyes; check them on a phone before tagging a release, at 100% (1:1 button) and 400%.

- [ ] Tile seams at 1:1 on skies and skin (GPU and "Use CPU only")
- [ ] Halos or ringing along high-contrast edges (text, branches against sky)
- [ ] Colour shift vs the original (toggle "Match source colors")
- [ ] Waxy or plastic skin, over-smoothed hair (lower Detail should fix)
- [ ] Hallucinated textures: fake text, fur on smooth surfaces, invented patterns
- [ ] Gradient banding in skies (PNG and JPEG export)
- [ ] Image borders (reflect padding)
- [ ] 8× and 16× chains: compounding artifacts vs 4×
- [ ] De-JPEG on a q10–q30 JPEG: blocks gone, no new smearing
- [ ] Transparency survives (PNG with alpha, 2× and 4×)
- [ ] EXIF: camera info kept, **no GPS**, orientation correct in the gallery
- [ ] Batch of 5 mixed images (one corrupt) finishes, and the corrupt one fails alone
