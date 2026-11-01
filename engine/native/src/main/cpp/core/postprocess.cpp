#include "postprocess.h"

#include <algorithm>
#include <cmath>

namespace rescale {

namespace {

inline float cubic(float x) {  // Keys kernel, a = -0.5
    x = std::fabs(x);
    if (x < 1.f) return (1.5f * x - 2.5f) * x * x + 1.f;
    if (x < 2.f) return ((-0.5f * x + 2.5f) * x - 4.f) * x + 2.f;
    return 0.f;
}

struct Taps {
    int idx[4];
    float w[4];
};

// Precompute 4 taps per output coordinate along one axis.
std::vector<Taps> taps(int n, int s) {
    std::vector<Taps> t(static_cast<size_t>(n) * s);
    for (int o = 0; o < n * s; ++o) {
        const float src = (o + 0.5f) / s - 0.5f;
        const int base = static_cast<int>(std::floor(src)) - 1;
        float sum = 0.f;
        for (int k = 0; k < 4; ++k) {
            const int i = base + k;
            t[o].idx[k] = std::clamp(i, 0, n - 1);
            t[o].w[k] = cubic(src - i);
            sum += t[o].w[k];
        }
        for (float& w : t[o].w) w /= sum;
    }
    return t;
}

// Separable Gaussian blur of one plane in place.
void blur(float* p, int w, int h, float sigma, std::vector<float>& tmp) {
    const int r = std::max(1, static_cast<int>(std::ceil(3 * sigma)));
    std::vector<float> k(2 * r + 1);
    float sum = 0.f;
    for (int i = -r; i <= r; ++i) sum += k[i + r] = std::exp(-0.5f * i * i / (sigma * sigma));
    for (float& v : k) v /= sum;
    tmp.resize(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float a = 0.f;
            for (int i = -r; i <= r; ++i) a += k[i + r] * p[y * w + std::clamp(x + i, 0, w - 1)];
            tmp[y * w + x] = a;
        }
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float a = 0.f;
            for (int i = -r; i <= r; ++i) a += k[i + r] * tmp[std::clamp(y + i, 0, h - 1) * w + x];
            p[y * w + x] = a;
        }
}

}  // namespace

void blurPlanes(std::vector<float>& planes, int w, int h, float sigma) {
    if (sigma <= 0.f) return;
    std::vector<float> tmp;
    for (int c = 0; c < 3; ++c) blur(planes.data() + static_cast<size_t>(c) * w * h, w, h, sigma, tmp);
}

void bicubicUpscale(const std::vector<float>& in, int w, int h, int s, std::vector<float>& out) {
    const int ow = w * s, oh = h * s;
    const auto tx = taps(w, s), ty = taps(h, s);
    out.resize(static_cast<size_t>(3) * ow * oh);
    std::vector<float> rows(static_cast<size_t>(ow) * h);  // horizontal pass
    for (int c = 0; c < 3; ++c) {
        const float* src = in.data() + static_cast<size_t>(c) * w * h;
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < ow; ++x) {
                const Taps& t = tx[x];
                const float* r = src + static_cast<size_t>(y) * w;
                rows[static_cast<size_t>(y) * ow + x] =
                    t.w[0] * r[t.idx[0]] + t.w[1] * r[t.idx[1]] + t.w[2] * r[t.idx[2]] + t.w[3] * r[t.idx[3]];
            }
        float* dst = out.data() + static_cast<size_t>(c) * ow * oh;
        for (int y = 0; y < oh; ++y) {
            const Taps& t = ty[y];
            for (int x = 0; x < ow; ++x)
                dst[static_cast<size_t>(y) * ow + x] = t.w[0] * rows[static_cast<size_t>(t.idx[0]) * ow + x] +
                                                       t.w[1] * rows[static_cast<size_t>(t.idx[1]) * ow + x] +
                                                       t.w[2] * rows[static_cast<size_t>(t.idx[2]) * ow + x] +
                                                       t.w[3] * rows[static_cast<size_t>(t.idx[3]) * ow + x];
        }
    }
}

void postProcessTile(const std::vector<float>& in, int w, int h, int s, const PostOptions& opt,
                     std::vector<float>& out, std::vector<float>& bic) {
    const int ow = w * s, oh = h * s;
    const size_t oplane = static_cast<size_t>(ow) * oh;
    if (opt.detail < 0.999f || opt.lumaOnly) bicubicUpscale(in, w, h, s, bic);

    // 1. Detail slider: blend the generative output with the bicubic (fidelity) one.
    if (opt.detail < 0.999f) {
        const float a = std::clamp(opt.detail, 0.f, 1.f);
        for (size_t i = 0; i < 3 * oplane; ++i) out[i] = a * out[i] + (1 - a) * bic[i];
    }

    // 2. Luminance only: keep the model's Y, take chroma from bicubic.
    if (opt.lumaOnly) {
        for (size_t i = 0; i < oplane; ++i) {
            const float ym = 0.299f * out[i] + 0.587f * out[oplane + i] + 0.114f * out[2 * oplane + i];
            const float yb = 0.299f * bic[i] + 0.587f * bic[oplane + i] + 0.114f * bic[2 * oplane + i];
            const float d = ym - yb;
            out[i] = bic[i] + d;
            out[oplane + i] = bic[oplane + i] + d;
            out[2 * oplane + i] = bic[2 * oplane + i] + d;
        }
    }

    // 3. Colour fix: low-pass(input) - low-pass(area-downscaled output), upsampled and added.
    if (opt.colorFix) {
        const size_t plane = static_cast<size_t>(w) * h;
        std::vector<float> diff(3 * plane), tmp;
        const float inv = 1.f / (s * s);
        for (int c = 0; c < 3; ++c) {
            const float* o = out.data() + c * oplane;
            float* d = diff.data() + c * plane;
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    float acc = 0.f;
                    for (int v = 0; v < s; ++v)
                        for (int u = 0; u < s; ++u) acc += o[static_cast<size_t>(y * s + v) * ow + x * s + u];
                    d[y * w + x] = in[c * plane + y * w + x] - acc * inv;
                }
            blur(d, w, h, opt.colorFixSigma, tmp);
        }
        // Bilinear upsample of the (smooth) correction field.
        for (int c = 0; c < 3; ++c) {
            const float* d = diff.data() + c * plane;
            float* o = out.data() + c * oplane;
            for (int y = 0; y < oh; ++y) {
                const float fy = std::clamp((y + 0.5f) / s - 0.5f, 0.f, static_cast<float>(h - 1));
                const int y0 = static_cast<int>(fy), y1 = std::min(y0 + 1, h - 1);
                const float ty = fy - y0;
                for (int x = 0; x < ow; ++x) {
                    const float fx = std::clamp((x + 0.5f) / s - 0.5f, 0.f, static_cast<float>(w - 1));
                    const int x0 = static_cast<int>(fx), x1 = std::min(x0 + 1, w - 1);
                    const float tx = fx - x0;
                    const float a = d[y0 * w + x0] * (1 - tx) + d[y0 * w + x1] * tx;
                    const float b = d[y1 * w + x0] * (1 - tx) + d[y1 * w + x1] * tx;
                    o[static_cast<size_t>(y) * ow + x] += a * (1 - ty) + b * ty;
                }
            }
        }
    }
}

}  // namespace rescale
