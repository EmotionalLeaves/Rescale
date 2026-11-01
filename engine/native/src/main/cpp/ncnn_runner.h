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

private:
    ncnn::Net net_;
    int scale_;
    bool gpu_;
    std::string inBlob_, outBlob_;
    std::string extraBlob_;
    std::vector<float> extraValues_;
    int align_ = 1;
    float mul_ = 1.f, add_ = 0.f;
};

// Vulkan instance is process-wide; created lazily, returns number of GPUs.
int ensureGpuInstance();

}  // namespace rescale
