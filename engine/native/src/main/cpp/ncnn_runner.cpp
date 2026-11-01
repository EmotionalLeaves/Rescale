#include "ncnn_runner.h"

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

bool NcnnRunner::load(const std::string& paramPath, const std::string& binPath) {
    return net_.load_param(paramPath.c_str()) == 0 && net_.load_model(binPath.c_str()) == 0;
}

bool NcnnRunner::run(const std::vector<float>& in, int w, int h, std::vector<float>& out) {
    ncnn::Mat m(w, h, 3);
    const size_t plane = static_cast<size_t>(w) * h;
    for (int c = 0; c < 3; ++c) std::memcpy(m.channel(c), in.data() + c * plane, plane * sizeof(float));

    ncnn::Extractor ex = net_.create_extractor();
    if (ex.input(inBlob_.c_str(), m) != 0) return false;
    ncnn::Mat r;
    if (ex.extract(outBlob_.c_str(), r) != 0) return false;
    if (r.c != 3 || r.w != w * scale_ || r.h != h * scale_) return false;
    if (r.elemsize != 4) {  // make sure we read fp32
        ncnn::Mat f;
        ncnn::cast_float16_to_float32(r, f);
        r = f;
    }
    const size_t oplane = static_cast<size_t>(r.w) * r.h;
    out.resize(3 * oplane);
    for (int c = 0; c < 3; ++c) std::memcpy(out.data() + c * oplane, r.channel(c), oplane * sizeof(float));
    return true;
}

}  // namespace rescale
