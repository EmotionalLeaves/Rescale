#include "ncnn_runner.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>

#include <platform.h>
#if NCNN_VULKAN
#include <gpu.h>
#endif

namespace rescale {

int ensureGpuInstance() {
    static std::once_flag once;
    static int count = 0;
    std::call_once(once, [] {
#if NCNN_VULKAN
        if (ncnn::create_gpu_instance() == 0) count = ncnn::get_gpu_count();
#endif
    });
    return count;
}

NcnnRunner::NcnnRunner(int scale, bool gpu, int threads, std::string inBlob, std::string outBlob)
    : scale_(scale), gpu_(gpu && ensureGpuInstance() > 0), inBlob_(std::move(inBlob)), outBlob_(std::move(outBlob)) {
    net_.opt.use_vulkan_compute = gpu_;
    net_.opt.num_threads = threads > 0 ? threads : 2;
    // FP16 storage/arithmetic is visually lossless for SR and roughly halves bandwidth.
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    net_.opt.use_fp16_arithmetic = gpu_;  // CPU fp16 arithmetic can band on some cores
#if NCNN_VULKAN
    if (gpu_) net_.set_vulkan_device(0);
#endif
}

NcnnRunner::~NcnnRunner() { net_.clear(); }

void NcnnRunner::setLowMemory(bool on) {
    net_.opt.use_winograd_convolution = !on;
    net_.opt.use_sgemm_convolution = !on;
}

bool NcnnRunner::load(const std::string& paramPath, const std::string& binPath) {
    return net_.load_param(paramPath.c_str()) == 0 && net_.load_model(binPath.c_str()) == 0;
}

bool NcnnRunner::run(const std::vector<float>& in, int w, int h, std::vector<float>& out) {
    const int pw = (w + align_ - 1) / align_ * align_;
    const int ph = (h + align_ - 1) / align_ * align_;
    ncnn::Mat m(pw, ph, 3);
    const size_t plane = static_cast<size_t>(w) * h;
    for (int c = 0; c < 3; ++c) {
        float* dst = m.channel(c);
        const float* src = in.data() + c * plane;
        for (int y = 0; y < ph; ++y) {
            const float* row = src + static_cast<size_t>(std::min(y, h - 1)) * w;
            float* d = dst + static_cast<size_t>(y) * pw;
            for (int x = 0; x < w; ++x) d[x] = row[x] * mul_ + add_;
            for (int x = w; x < pw; ++x) d[x] = row[w - 1] * mul_ + add_;
        }
    }

    ncnn::Extractor ex = net_.create_extractor();
    if (ex.input(inBlob_.c_str(), m) != 0) return false;
    if (!extraBlob_.empty()) {
        ncnn::Mat e(static_cast<int>(extraValues_.size()));
        std::memcpy(e.data, extraValues_.data(), extraValues_.size() * sizeof(float));
        if (ex.input(extraBlob_.c_str(), e) != 0) return false;
    }
    ncnn::Mat r;
    if (ex.extract(outBlob_.c_str(), r) != 0) return false;
    if (r.c != 3 || r.w != pw * scale_ || r.h != ph * scale_) return false;
    if (r.elemsize != 4) {  // make sure we read fp32
        ncnn::Mat f;
        ncnn::cast_float16_to_float32(r, f);
        r = f;
    }
    // FP16 overflow on a GPU shows up as inf/NaN: fail the tile so the app falls back to CPU
    // instead of writing black squares.
    for (int c = 0; c < 3; ++c) {
        const float* p = r.channel(c);
        const size_t n = static_cast<size_t>(r.w) * r.h;
        for (size_t i = 0; i < n; ++i)
            if (!std::isfinite(p[i])) return false;
    }
    const int ow = w * scale_, oh = h * scale_;
    const size_t oplane = static_cast<size_t>(ow) * oh;
    out.resize(3 * oplane);
    for (int c = 0; c < 3; ++c) {
        const float* src = r.channel(c);
        for (int y = 0; y < oh; ++y) {
            float* d = out.data() + c * oplane + static_cast<size_t>(y) * ow;
            const float* s = src + static_cast<size_t>(y) * r.w;
            if (mul_ == 1.f && add_ == 0.f) {
                std::memcpy(d, s, ow * sizeof(float));
            } else {
                for (int x = 0; x < ow; ++x) d[x] = (s[x] - add_) / mul_;
            }
        }
    }
    if (statMatch_) matchStats(in, w, h, out, scale_);
    return true;
}

}  // namespace rescale
