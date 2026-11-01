#include "tiler.h"

#include <algorithm>
#include <cmath>

namespace rescale {

Axis planAxis(int length, int tile, int overlap) {
    Axis a;
    a.length = length;
    a.tile = std::max(1, std::min(tile, length));
    overlap = std::max(0, std::min(overlap, a.tile / 2));
    if (a.tile >= length) {
        a.starts = {0};
        return a;
    }
    const int step = a.tile - overlap;
    // Smallest n with (n-1)*step + tile >= length.
    const int n = 1 + (length - a.tile + step - 1) / step;
    a.starts.resize(n);
    for (int i = 0; i < n; ++i) {
        // Spread evenly so the last tile ends exactly at the border.
        a.starts[i] = static_cast<int>(std::lround(static_cast<double>(i) * (length - a.tile) / (n - 1)));
    }
    return a;
}

static inline float hannRamp(float t) {  // t in (0,1) -> (0,1), smooth
    return 0.5f - 0.5f * std::cos(static_cast<float>(M_PI) * t);
}

std::vector<float> tileWeights(const Axis& axis, int i, int scale) {
    const int n = static_cast<int>(axis.starts.size());
    const int len = axis.tile * scale;
    std::vector<float> w(len, 1.0f);
    if (i > 0) {
        const int ov = (axis.starts[i - 1] + axis.tile - axis.starts[i]) * scale;
        for (int u = 0; u < ov && u < len; ++u) w[u] *= hannRamp((u + 0.5f) / ov);
    }
    if (i < n - 1) {
        const int ov = (axis.starts[i] + axis.tile - axis.starts[i + 1]) * scale;
        for (int k = 0; k < ov && k < len; ++k) w[len - 1 - k] *= hannRamp((k + 0.5f) / ov);
    }
    return w;
}

std::vector<float> weightSum(const Axis& axis, int scale) {
    std::vector<float> sum(static_cast<size_t>(axis.length) * scale, 0.0f);
    for (size_t i = 0; i < axis.starts.size(); ++i) {
        const auto w = tileWeights(axis, static_cast<int>(i), scale);
        const int base = axis.starts[i] * scale;
        for (size_t u = 0; u < w.size(); ++u) sum[base + u] += w[u];
    }
    return sum;
}

}  // namespace rescale
