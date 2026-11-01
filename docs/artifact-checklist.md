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
- [ ] Repair on a scratched old photo: cracks and spots gone, no smears across faces; mask editor paint/erase/undo match what gets filled
- [ ] Repair on a sepia or black-and-white print: no coloured (blue/green) patches in the fills
- [ ] Repair with a large painted area (an object): plausible fill, no visible 512-px window edges
- [ ] Generative detail on a small photo: realistic texture, no tile seams at 1:1, no black or flat tiles (FP16), colours match the source
- [ ] Generative detail on a clear sky or a white wall (the FP16 trap): smooth, no NaN squares
- [ ] Generative pack: terms dialog appears before download; refused on phones under 6 GB RAM
