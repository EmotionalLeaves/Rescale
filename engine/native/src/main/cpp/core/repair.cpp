#include "repair.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace rescale {

namespace {

constexpr int kMargin = (kInpaintWindow - kInpaintStride) / 2;  // 64: fills this close to a window edge are redone

inline int reflect101(int i, int n) {
    if (n == 1) return 0;
    const int period = 2 * (n - 1);
    i %= period;
    if (i < 0) i += period;
    return i < n ? i : period - i;
}

// Window origins along one axis: evenly spaced, at most kInpaintStride apart, the first at 0
// and the last ending exactly at the border.
std::vector<int> windowStarts(int n) {
    if (n <= kInpaintWindow) return {0};
    const int span = n - kInpaintWindow;
    const int k = (span + kInpaintStride - 1) / kInpaintStride + 1;
    std::vector<int> s(k);
    for (int i = 0; i < k; ++i) s[i] = static_cast<int>((static_cast<long long>(span) * i) / (k - 1));
    return s;
}

// The part of a window whose fill is final: everything except a margin on sides that are
// not image borders (those pixels lack context beyond the window and are redone by the next one).
void interior(int start, int n, int& a, int& b) {
    const int end = std::min(start + kInpaintWindow, n);
    a = start == 0 ? 0 : start + kMargin;
    b = end == n ? n : end - kMargin;
}

bool anyHole(const std::vector<uint8_t>& m, int w, int x0, int y0, int x1, int y1) {
    for (int y = y0; y < y1; ++y) {
        const uint8_t* r = m.data() + static_cast<size_t>(y) * w;
        for (int x = x0; x < x1; ++x)
            if (r[x]) return true;
    }
    return false;
}

struct Counter {
    const ProgressFn* fn;
    int done = 0, total = 0;
    void tick() {
        ++done;
        if (*fn) (*fn)(std::min(done, total), total);
    }
};

// Native-resolution pass: fills every pixel of `hole` (which is consumed).
Status finePass(std::vector<uint8_t>& rgba, int w, int h, std::vector<uint8_t>& hole, InpaintRunner& net,
                Counter& counter, const std::atomic<bool>* cancel) {
    const int N = kInpaintWindow;
    std::vector<float> rgb(3 * N * N), mk(N * N), out;
    const auto xs = windowStarts(w), ys = windowStarts(h);
    for (int y0 : ys) {
        for (int x0 : xs) {
            const int x1 = std::min(x0 + N, w), y1 = std::min(y0 + N, h);
            if (!anyHole(hole, w, x0, y0, x1, y1)) continue;
            if (cancel && cancel->load()) return kCancelled;
            // Window contents; reflected when the image is smaller than the window.
            for (int j = 0; j < N; ++j) {
                const int sy = reflect101(y0 + j, h);
                for (int i = 0; i < N; ++i) {
                    const int sx = reflect101(x0 + i, w);
                    const size_t si = static_cast<size_t>(sy) * w + sx;
                    const uint8_t* p = &rgba[si * 4];
                    const size_t o = static_cast<size_t>(j) * N + i;
                    const bool hl = hole[si] != 0;
                    mk[o] = hl ? 1.f : 0.f;
                    for (int c = 0; c < 3; ++c) rgb[c * N * N + o] = hl ? 0.f : p[c] / 255.f;
                }
            }
            if (!net.run(rgb, mk, out) || out.size() != rgb.size()) return kModelError;
            for (int y = y0; y < y1; ++y) {
                for (int x = x0; x < x1; ++x) {
                    const size_t si = static_cast<size_t>(y) * w + x;
                    if (!hole[si]) continue;
                    const size_t o = static_cast<size_t>(y - y0) * N + (x - x0);
                    for (int c = 0; c < 3; ++c) {
                        const float v = std::clamp(out[c * N * N + o], 0.f, 1.f);
                        rgba[si * 4 + c] = static_cast<uint8_t>(std::lround(v * 255.f));
                    }
                }
            }
            int ix0, ix1, iy0, iy1;
            interior(x0, w, ix0, ix1);
            interior(y0, h, iy0, iy1);
            for (int y = iy0; y < iy1; ++y)
                std::fill(hole.begin() + static_cast<size_t>(y) * w + ix0, hole.begin() + static_cast<size_t>(y) * w + ix1, 0);
            counter.tick();
        }
    }
    return kOk;
}

Status repairLevel(std::vector<uint8_t>& rgba, int w, int h, const std::vector<uint8_t>& mask, InpaintRunner& net,
                   Counter& counter, const std::atomic<bool>* cancel, int depth) {
    std::vector<uint8_t> hole(mask.size());
    bool any = false;
    for (size_t i = 0; i < mask.size(); ++i) any |= (hole[i] = mask[i] ? 1 : 0) != 0;
    if (!any) return kOk;

    const std::vector<float> dist = holeDistance(hole, w, h);
    const float maxd = *std::max_element(dist.begin(), dist.end());
    if (maxd > kFineRadius && depth < 6 && std::min(w, h) > 32) {
        // Coarse level: known-pixel averages over f×f blocks; a block is a hole if mostly hole.
        const int f = std::max(2, static_cast<int>(std::ceil(maxd / (kFineRadius * 0.75f))));
        const int cw = (w + f - 1) / f, ch = (h + f - 1) / f;
        std::vector<uint8_t> small(static_cast<size_t>(cw) * ch * 4, 255), smask(static_cast<size_t>(cw) * ch, 0);
        for (int by = 0; by < ch; ++by) {
            for (int bx = 0; bx < cw; ++bx) {
                float acc[3] = {0, 0, 0};
                int known = 0, n = 0;
                for (int y = by * f; y < std::min(h, by * f + f); ++y)
                    for (int x = bx * f; x < std::min(w, bx * f + f); ++x, ++n) {
                        const size_t i = static_cast<size_t>(y) * w + x;
                        if (hole[i]) continue;
                        ++known;
                        for (int c = 0; c < 3; ++c) acc[c] += rgba[i * 4 + c];
                    }
                const size_t o = static_cast<size_t>(by) * cw + bx;
                smask[o] = known * 2 < n ? 1 : 0;
                for (int c = 0; c < 3; ++c)
                    small[o * 4 + c] = known ? static_cast<uint8_t>(std::lround(acc[c] / known)) : 0;
            }
        }
        const Status st = repairLevel(small, cw, ch, smask, net, counter, cancel, depth + 1);
        if (st != kOk) return st;
        // Bilinear upsample of the coarse fill into every hole pixel.
        for (int y = 0; y < h; ++y) {
            const float sy = std::clamp((y + 0.5f) / f - 0.5f, 0.f, static_cast<float>(ch - 1));
            const int y0 = static_cast<int>(sy), y1 = std::min(y0 + 1, ch - 1);
            const float ty = sy - y0;
            for (int x = 0; x < w; ++x) {
                const size_t i = static_cast<size_t>(y) * w + x;
                if (!hole[i]) continue;
                const float sx = std::clamp((x + 0.5f) / f - 0.5f, 0.f, static_cast<float>(cw - 1));
                const int x0 = static_cast<int>(sx), x1 = std::min(x0 + 1, cw - 1);
                const float tx = sx - x0;
                for (int c = 0; c < 3; ++c) {
                    auto px = [&](int xx, int yy) { return static_cast<float>(small[(static_cast<size_t>(yy) * cw + xx) * 4 + c]); };
                    const float v = (px(x0, y0) * (1 - tx) + px(x1, y0) * tx) * (1 - ty) +
                                    (px(x0, y1) * (1 - tx) + px(x1, y1) * tx) * ty;
                    rgba[i * 4 + c] = static_cast<uint8_t>(std::lround(std::clamp(v, 0.f, 255.f)));
                }
            }
        }
        // The cores keep the coarse fill; the rim (and thin damage) is redone at full resolution.
        for (size_t i = 0; i < hole.size(); ++i)
            if (dist[i] > kFineRadius) hole[i] = 0;
    }
    return finePass(rgba, w, h, hole, net, counter, cancel);
}

// Black-and-white and toned (sepia, cyanotype) photos: colour is a function of brightness. The
// inpainter, trained on colour photos, sometimes adds a tint (blue patches in a sepia print), so
// when the known pixels follow a brightness→colour curve closely, filled pixels are put back on it.
void matchTone(std::vector<uint8_t>& rgba, const std::vector<uint8_t>& mask) {
    const size_t n = mask.size();
    double sum[256][3] = {};
    double cnt[256] = {};
    auto luma = [&](size_t i) {
        const uint8_t* p = &rgba[i * 4];
        return std::clamp(static_cast<int>(std::lround(0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2])), 0, 255);
    };
    for (size_t i = 0; i < n; ++i) {
        if (mask[i]) continue;
        const int y = luma(i);
        for (int c = 0; c < 3; ++c) sum[y][c] += rgba[i * 4 + c];
        cnt[y] += 1;
    }
    float lut[256][3];
    int prev = -1;
    for (int y = 0; y < 256; ++y) {
        if (cnt[y] == 0) continue;
        for (int c = 0; c < 3; ++c) lut[y][c] = static_cast<float>(sum[y][c] / cnt[y]);
        if (prev < 0) {
            for (int k = 0; k < y; ++k) for (int c = 0; c < 3; ++c) lut[k][c] = lut[y][c];
        } else {
            for (int k = prev + 1; k < y; ++k) {
                const float t = static_cast<float>(k - prev) / (y - prev);
                for (int c = 0; c < 3; ++c) lut[k][c] = lut[prev][c] * (1 - t) + lut[y][c] * t;
            }
        }
        prev = y;
    }
    if (prev < 0) return;  // nothing known
    for (int k = prev + 1; k < 256; ++k) for (int c = 0; c < 3; ++c) lut[k][c] = lut[prev][c];
    double err = 0, known = 0;
    for (size_t i = 0; i < n; ++i) {
        if (mask[i]) continue;
        const int y = luma(i);
        for (int c = 0; c < 3; ++c) {
            const double d = rgba[i * 4 + c] - lut[y][c];
            err += d * d;
        }
        known += 3;
    }
    if (known == 0 || std::sqrt(err / known) > 6.0) return;  // a colour photo: leave it alone
    for (size_t i = 0; i < n; ++i) {
        if (!mask[i]) continue;
        const int y = luma(i);
        for (int c = 0; c < 3; ++c) rgba[i * 4 + c] = static_cast<uint8_t>(std::lround(std::clamp(lut[y][c], 0.f, 255.f)));
    }
}

}  // namespace

std::vector<float> holeDistance(const std::vector<uint8_t>& mask, int w, int h) {
    const float inf = 1e9f;
    std::vector<float> d(mask.size());
    for (size_t i = 0; i < mask.size(); ++i) d[i] = mask[i] ? inf : 0.f;
    auto at = [&](int x, int y) -> float& { return d[static_cast<size_t>(y) * w + x]; };
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float& v = at(x, y);
            if (v == 0.f) continue;
            if (x > 0) v = std::min(v, at(x - 1, y) + 3);
            if (y > 0) {
                v = std::min(v, at(x, y - 1) + 3);
                if (x > 0) v = std::min(v, at(x - 1, y - 1) + 4);
                if (x + 1 < w) v = std::min(v, at(x + 1, y - 1) + 4);
            }
        }
    for (int y = h - 1; y >= 0; --y)
        for (int x = w - 1; x >= 0; --x) {
            float& v = at(x, y);
            if (v == 0.f) continue;
            if (x + 1 < w) v = std::min(v, at(x + 1, y) + 3);
            if (y + 1 < h) {
                v = std::min(v, at(x, y + 1) + 3);
                if (x + 1 < w) v = std::min(v, at(x + 1, y + 1) + 4);
                if (x > 0) v = std::min(v, at(x - 1, y + 1) + 4);
            }
        }
    for (float& v : d) v = v >= inf ? inf : v / 3.f;
    return d;
}

void dilateMask(std::vector<uint8_t>& mask, int w, int h, int r) {
    if (r <= 0) return;
    std::vector<uint8_t> inv(mask.size());
    bool any = false;
    for (size_t i = 0; i < mask.size(); ++i) {
        inv[i] = mask[i] ? 0 : 1;
        any |= mask[i] != 0;
    }
    if (!any) return;
    const auto d = holeDistance(inv, w, h);  // distance to the nearest masked pixel
    for (size_t i = 0; i < mask.size(); ++i) mask[i] = d[i] <= r + 0.25f ? 1 : 0;
}

int countRepairWindows(const std::vector<uint8_t>& mask, int w, int h) {
    int n = 0;
    for (int y0 : windowStarts(h))
        for (int x0 : windowStarts(w))
            if (anyHole(mask, w, x0, y0, std::min(x0 + kInpaintWindow, w), std::min(y0 + kInpaintWindow, h))) ++n;
    return n;
}

Status repairImage(std::vector<uint8_t>& rgba, int w, int h, const std::vector<uint8_t>& mask, InpaintRunner& net,
                   const ProgressFn& progress, const std::atomic<bool>* cancel) {
    if (w <= 0 || h <= 0 || rgba.size() != static_cast<size_t>(w) * h * 4 || mask.size() != static_cast<size_t>(w) * h)
        return kBadInput;
    Counter counter{&progress};
    counter.total = std::max(1, countRepairWindows(mask, w, h) + 1);
    const Status st = repairLevel(rgba, w, h, mask, net, counter, cancel, 0);
    if (st != kOk) return st;
    matchTone(rgba, mask);
    if (progress) progress(counter.total, counter.total);
    return kOk;
}

void resizeBilinear(const float* src, int sw, int sh, float* dst, int dw, int dh) {
    const float fx = static_cast<float>(sw) / dw, fy = static_cast<float>(sh) / dh;
    for (int y = 0; y < dh; ++y) {
        const float sy = std::clamp((y + 0.5f) * fy - 0.5f, 0.f, static_cast<float>(sh - 1));
        const int y0 = static_cast<int>(sy), y1 = std::min(y0 + 1, sh - 1);
        const float ty = sy - y0;
        for (int x = 0; x < dw; ++x) {
            const float sx = std::clamp((x + 0.5f) * fx - 0.5f, 0.f, static_cast<float>(sw - 1));
            const int x0 = static_cast<int>(sx), x1 = std::min(x0 + 1, sw - 1);
            const float tx = sx - x0;
            const float a = src[static_cast<size_t>(y0) * sw + x0] * (1 - tx) + src[static_cast<size_t>(y0) * sw + x1] * tx;
            const float b = src[static_cast<size_t>(y1) * sw + x0] * (1 - tx) + src[static_cast<size_t>(y1) * sw + x1] * tx;
            dst[static_cast<size_t>(y) * dw + x] = a * (1 - ty) + b * ty;
        }
    }
}

}  // namespace rescale
