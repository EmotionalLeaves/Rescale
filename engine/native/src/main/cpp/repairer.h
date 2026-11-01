// ncnn-backed damage repair: scratch/tear detection (Bringing-Old-Photos-Back-to-Life UNet) and
// inpainting (MI-GAN, 512×512).
#pragma once

#include <string>
#include <vector>

#include <net.h>

#include "core/repair.h"

namespace rescale {

class ScratchDetector {
public:
    ScratchDetector(bool gpu, int threads);
    bool load(const std::string& param, const std::string& bin);
    // Hole mask (w×h of the oriented image, 1 = damage). The image is analysed with its short
    // side at `shortSide` px and again at half that (the network was trained around 256–512); the
    // probability maps are upsampled, combined (max), thresholded at `threshold`, and dilated to
    // cover the damage's soft edges.
    // `fineCoverage` (optional): share flagged by the fine pass alone, before dilation. It
    // separates damaged from clean photos better than the final mask (the coarse pass also
    // fires on lattices and railings), so Auto's suggestion uses it.
    bool detect(const Image& img, std::vector<uint8_t>& mask, int shortSide = 512, float threshold = 0.4f,
                float* fineCoverage = nullptr);

private:
    // Full-resolution logits with the image analysed at `shortSide`; `scale` = analysis/full size.
    bool logits(const Image& img, int shortSide, std::vector<float>& full, float& scale);

    ncnn::Net net_;
};

class NcnnInpainter : public InpaintRunner {
public:
    NcnnInpainter(bool gpu, int threads);
    bool load(const std::string& param, const std::string& bin);
    bool run(const std::vector<float>& rgb, const std::vector<float>& mask, std::vector<float>& out) override;
    bool usingGpu() const { return gpu_; }

private:
    ncnn::Net net_;
    bool gpu_;
};

}  // namespace rescale
