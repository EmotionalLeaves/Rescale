// Per-tile artifact controls, applied to the model output before tiles are blended.
// All work on planar float RGB in [0,1]; `in` is the padded input tile (w x h),
// `out` the model output for it ((w*s) x (h*s)).
#pragma once

#include <vector>

namespace rescale {

struct PostOptions {
    // 1 = pure model output, 0 = pure bicubic (the "fidelity" end of the Detail slider).
    float detail = 1.0f;
    // Keep the model's luminance but take colour (chroma) from a bicubic upscale.
    bool lumaOnly = false;
    // Pull the output's low frequencies back to the source (fixes colour/brightness drift).
    bool colorFix = false;
    float colorFixSigma = 2.5f;  // in input pixels
    // Gaussian blur of the input tile before inference (chained passes: stops the model
    // from "upscaling its own textures"). 0 = off.
    float preBlur = 0.f;

    bool active() const { return detail < 0.999f || lumaOnly || colorFix; }
};

// Keys bicubic (a = -0.5), pixel-centre aligned, edges clamped. Planar 3-channel.
void bicubicUpscale(const std::vector<float>& in, int w, int h, int s, std::vector<float>& out);

// In-place Gaussian blur of the 3 planes of a w×h planar tile.
void blurPlanes(std::vector<float>& planes, int w, int h, float sigma);

void postProcessTile(const std::vector<float>& in, int w, int h, int s, const PostOptions& opt,
                     std::vector<float>& out, std::vector<float>& scratch);

}  // namespace rescale
