#pragma once

#include <string>
#include <vector>

#include <net.h>

#include "core/engine.h"

namespace rescale {

// Runs an NCNN model (Vulkan if requested and available, CPU otherwise).
class NcnnRunner : public TileRunner {
public:
    NcnnRunner(int scale, bool gpu, int threads, std::string inBlob, std::string outBlob);
    ~NcnnRunner() override;
    // Call before load(). Skips winograd/sgemm weight transforms, which grow a model's weights
    // 2–4× in memory: needed for the ~900 MB generative model, slightly slower otherwise.
    void setLowMemory(bool on);
    bool load(const std::string& paramPath, const std::string& binPath);
    int scale() const override { return scale_; }
    bool usingGpu() const { return gpu_; }
    bool run(const std::vector<float>& in, int w, int h, std::vector<float>& out) override;

    // Optional second input (e.g. FBCNN's quality control), fed on every tile.
    void setExtraInput(std::string blob, std::vector<float> values) {
        extraBlob_ = std::move(blob);
        extraValues_ = std::move(values);
    }
    // Pad tiles internally (edge replicate) to a multiple of this (UNets need 8).
    void setAlignment(int a) { align_ = a > 0 ? a : 1; }
    // Model value range: feeds in*mul+add, returns (out-add)/mul. GFPGAN uses [-1,1]: (2, -1).
    void setNormalization(float mul, float add) {
        mul_ = mul;
        add_ = add;
    }
    // Match each output channel's mean and contrast to the input tile's (AdcSR's colour
    // normalisation, applied per tile; the contrast ratio is clamped so flat tiles stay flat).
    void setStatMatch(bool on) { statMatch_ = on; }

private:
    ncnn::Net net_;
    int scale_;
    bool gpu_;
    std::string inBlob_, outBlob_;
    std::string extraBlob_;
    std::vector<float> extraValues_;
    int align_ = 1;
    float mul_ = 1.f, add_ = 0.f;
    bool statMatch_ = false;
};

// Vulkan instance is process-wide; created lazily, returns number of GPUs.
int ensureGpuInstance();

}  // namespace rescale
