#include "repairer.h"

#include <algorithm>
#include <cmath>

#include "ncnn_runner.h"

namespace rescale {

ScratchDetector::ScratchDetector(bool gpu, int threads) {
    net_.opt.num_threads = threads > 0 ? threads : 2;
    net_.opt.use_vulkan_compute = gpu && ensureGpuInstance() > 0;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
}

bool ScratchDetector::load(const std::string& param, const std::string& bin) {
    return net_.load_param(param.c_str()) == 0 && net_.load_model(bin.c_str()) == 0;
}

bool ScratchDetector::logits(const Image& img, int shortSide, std::vector<float>& full, float& scale) {
    const int W = img.width(), H = img.height();
    // Never upsample; cap the long side so panoramas stay cheap. Sizes are multiples of 16.
    float s = std::min(1.f, static_cast<float>(shortSide) / std::min(W, H));
    s = std::min(s, 2 * static_cast<float>(shortSide) / std::max(W, H));
    const int dw = std::max(16, static_cast<int>(std::lround(W * s / 16.f)) * 16);
    const int dh = std::max(16, static_cast<int>(std::lround(H * s / 16.f)) * 16);
    scale = s;

    // Area-average to the detection size, as grayscale (ITU-R 601 luma, like PIL's "L"), in [-1,1].
    ncnn::Mat in(dw, dh, 1);
    const float fx = static_cast<float>(W) / dw, fy = static_cast<float>(H) / dh;
    for (int y = 0; y < dh; ++y) {
        const int y0 = std::min(H - 1, static_cast<int>(y * fy));
        const int y1 = std::max(y0 + 1, std::min(H, static_cast<int>((y + 1) * fy)));
        float* row = in.row(y);
        for (int x = 0; x < dw; ++x) {
            const int x0 = std::min(W - 1, static_cast<int>(x * fx));
            const int x1 = std::max(x0 + 1, std::min(W, static_cast<int>((x + 1) * fx)));
            float acc = 0;
            for (int yy = y0; yy < y1; ++yy)
                for (int xx = x0; xx < x1; ++xx) {
                    const uint8_t* p = img.at(xx, yy);
                    acc += 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2];
                }
            row[x] = acc / ((y1 - y0) * (x1 - x0)) / 127.5f - 1.f;
        }
    }
    ncnn::Extractor ex = net_.create_extractor();
    ncnn::Mat out;
    if (ex.input("in0", in) != 0 || ex.extract("out0", out) != 0) return false;
    if (out.w != dw || out.h != dh) return false;
    if (out.elemsize != 4) {
        ncnn::Mat f;
        ncnn::cast_float16_to_float32(out, f);
        out = f;
    }
    full.resize(static_cast<size_t>(W) * H);
    resizeBilinear(static_cast<const float*>(out.data), dw, dh, full.data(), W, H);
    return true;
}

bool ScratchDetector::detect(const Image& img, std::vector<uint8_t>& mask, int shortSide, float threshold,
                             float* fineCoverage) {
    const int W = img.width(), H = img.height();
    std::vector<float> a, b;
    float s = 1.f, s2 = 1.f;
    if (!logits(img, shortSide, a, s)) return false;
    const float logitThr = std::log(threshold / (1.f - threshold));
    if (fineCoverage) {
        size_t n = 0;
        for (float v : a) n += v >= logitThr;
        *fineCoverage = static_cast<float>(static_cast<double>(n) / a.size());
    }
    // A second look at half the size catches wider cracks and tears the fine pass misses
    // (recall on synthetic damage: 62% -> 72%).
    if (std::min(W, H) * s >= 256 && logits(img, shortSide / 2, b, s2)) {
        for (size_t i = 0; i < a.size(); ++i) a[i] = std::max(a[i], b[i]);
    }
    // Threshold in logit space (sigmoid is monotonic).
    mask.assign(a.size(), 0);
    for (size_t i = 0; i < a.size(); ++i) mask[i] = a[i] >= logitThr ? 1 : 0;
    dilateMask(mask, W, H, 1 + static_cast<int>(std::lround(1.f / s)));
    return true;
}

NcnnInpainter::NcnnInpainter(bool gpu, int threads) : gpu_(gpu && ensureGpuInstance() > 0) {
    net_.opt.num_threads = threads > 0 ? threads : 2;
    net_.opt.use_vulkan_compute = gpu_;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    net_.opt.use_fp16_arithmetic = gpu_;
#if NCNN_VULKAN
    if (gpu_) net_.set_vulkan_device(0);
#endif
}

bool NcnnInpainter::load(const std::string& param, const std::string& bin) {
    return net_.load_param(param.c_str()) == 0 && net_.load_model(bin.c_str()) == 0;
}

bool NcnnInpainter::run(const std::vector<float>& rgb, const std::vector<float>& mask, std::vector<float>& out) {
    const int N = kInpaintWindow;
    const size_t plane = static_cast<size_t>(N) * N;
    if (rgb.size() != 3 * plane || mask.size() != plane) return false;
    // MI-GAN input: [0.5 - mask, image * (1 - mask)] with the image in [-1, 1].
    ncnn::Mat m(N, N, 4);
    float* m0 = m.channel(0);
    for (size_t i = 0; i < plane; ++i) m0[i] = 0.5f - mask[i];
    for (int c = 0; c < 3; ++c) {
        float* d = m.channel(c + 1);
        const float* s = rgb.data() + c * plane;
        for (size_t i = 0; i < plane; ++i) d[i] = (s[i] * 2.f - 1.f) * (1.f - mask[i]);
    }
    ncnn::Extractor ex = net_.create_extractor();
    ncnn::Mat r;
    if (ex.input("in0", m) != 0 || ex.extract("out0", r) != 0) return false;
    if (r.c != 3 || r.w != N || r.h != N) return false;
    if (r.elemsize != 4) {
        ncnn::Mat f;
        ncnn::cast_float16_to_float32(r, f);
        r = f;
    }
    out.resize(3 * plane);
    for (int c = 0; c < 3; ++c) {
        const float* s = r.channel(c);
        float* d = out.data() + c * plane;
        for (size_t i = 0; i < plane; ++i) d[i] = (s[i] + 1.f) * 0.5f;
    }
    return true;
}

}  // namespace rescale
