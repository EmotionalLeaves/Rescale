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

private:
    ncnn::Net net_;
    int scale_;
    bool gpu_;
    std::string inBlob_, outBlob_;
};

// Vulkan instance is process-wide; created lazily, returns number of GPUs.
int ensureGpuInstance();

}  // namespace rescale
