// Damage repair (ncnn-free parts): fills masked pixels (scratches, tears, spots, or areas the
// user painted) with an inpainting network that works on fixed 512×512 windows.
//
// Thin damage is filled at native resolution in overlapping 512 windows. Holes too thick for one
// window to see past are first filled on a downscaled copy (recursively), and that fill becomes
// context for a native-resolution pass over the hole's rim.
#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

#include "engine.h"

namespace rescale {

// Runs the inpainting network on one 512×512 window.
// rgb: 3 planes of 512² floats in [0,1]. mask: 512² floats, 1 = hole. out: 3 planes in [0,1].
// Only hole pixels of `out` are used.
class InpaintRunner {
public:
    virtual ~InpaintRunner() = default;
    virtual bool run(const std::vector<float>& rgb, const std::vector<float>& mask, std::vector<float>& out) = 0;
};

constexpr int kInpaintWindow = 512;
constexpr int kInpaintStride = 384;
// Hole pixels further than this from known pixels are filled at a coarser level first.
constexpr float kFineRadius = 40.f;

// Approximate Euclidean distance (chamfer 3-4, in px) from each pixel to the nearest non-hole
// pixel; 0 for non-hole pixels. All-hole images return a large value everywhere.
std::vector<float> holeDistance(const std::vector<uint8_t>& mask, int w, int h);

// Morphological dilation of a 0/1 mask with a disc of radius r.
void dilateMask(std::vector<uint8_t>& mask, int w, int h, int r);

// Fills hole pixels of `rgba` (w×h, 4 bytes per px, alpha untouched) in place. On black-and-white
// and toned photos the filled pixels' colour is taken from the photo's own brightness→tone curve.
// `mask`: w×h, nonzero = hole. Progress reports windows run so far against an estimate.
Status repairImage(std::vector<uint8_t>& rgba, int w, int h, const std::vector<uint8_t>& mask, InpaintRunner& net,
                   const ProgressFn& progress, const std::atomic<bool>* cancel);

// Number of windows the native-resolution pass over `mask` would run (for progress/estimates).
int countRepairWindows(const std::vector<uint8_t>& mask, int w, int h);

// Bilinear resize of a single-channel float map (used to bring the detector output to full res).
void resizeBilinear(const float* src, int sw, int sh, float* dst, int dw, int dh);

}  // namespace rescale
