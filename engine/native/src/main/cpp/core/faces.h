// Face restoration support (ncnn-free parts): RetinaFace decoding, 5-point similarity
// alignment to the FFHQ template, cropping, and a streaming compositor that blends restored
// faces into the final output rows.
#pragma once

#include <array>
#include <vector>

#include "engine.h"
#include "sinks.h"

namespace rescale {

struct Face {
    float score = 0;
    std::array<float, 4> box{};   // x0, y0, x1, y1 (image px)
    std::array<float, 10> lm{};   // 5 landmarks: eyes, nose, mouth corners
};

// x' = a*x + b*y + c ; y' = d*x + e*y + f
struct Affine {
    float a = 1, b = 0, c = 0, d = 0, e = 1, f = 0;
    void apply(float x, float y, float& ox, float& oy) const {
        ox = a * x + b * y + c;
        oy = d * x + e * y + f;
    }
    Affine inverse() const;
};

// RetinaFace (mobile0.25) anchors for a size×size input.
std::vector<std::array<float, 4>> retinaPriors(int size);

// Decodes raw outputs (n priors) to faces in the network's input pixels, with NMS.
std::vector<Face> decodeRetina(const float* loc, const float* conf, const float* lm, int size, float scoreThr,
                               float nmsThr);

// Least-squares similarity transform (rotation, uniform scale, translation) mapping src → dst.
Affine similarity(const float* src, const float* dst, int n);

// FFHQ 5-point template for a 512×512 aligned face (as used by GFPGAN/facexlib).
extern const float kFfhq512[10];

// Samples the aligned face crop: for each template pixel, inverse-map into the image (bilinear,
// clamped). Output planar RGB in [0,1].
void warpToTemplate(const Image& img, const Affine& imageToTemplate, int size, std::vector<float>& out);

struct RestoredFace {
    Affine imageToTemplate;    // input-image px → template px
    std::vector<float> rgb;    // planar RGB [0,1], size×size
    int size = 512;
};

// Blends restored faces into rows of the final output (scale `scale` = output px / input px).
// Feathered square mask in template space; `strength` in [0,1].
class FaceCompositor : public RowSink {
public:
    FaceCompositor(std::vector<RestoredFace> faces, float scale, int width, int channels, float strength,
                   RowSink* next);
    bool push(const uint8_t* row) override;
    bool finish() override { return next_->finish(); }

private:
    struct Placed {
        RestoredFace face;
        int x0, x1, y0, y1;  // output-space bounding box
    };
    std::vector<Placed> faces_;
    float scale_, strength_;
    int w_, c_, y_ = 0;
    std::vector<uint8_t> row_;
    RowSink* next_;
};

}  // namespace rescale
