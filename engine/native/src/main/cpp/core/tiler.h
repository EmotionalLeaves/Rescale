// Tile planning and feathered blend weights.
//
// Tiles overlap by at least `overlap` input pixels. Inside every overlap the two tiles
// are cross-faded with raised-cosine (Hann) ramps; image borders get no ramp. Because the
// weights are separable (w(x,y) = wx(x) * wy(y)), the per-pixel normaliser is just the
// product of two 1-D weight sums, so we never need a 2-D weight buffer.
#pragma once

#include <vector>

namespace rescale {

struct Axis {
    int length = 0;            // input length along this axis
    int tile = 0;              // tile length actually used (<= length)
    std::vector<int> starts;   // tile start positions, ascending, last one ends at length
};

// Plan tiles along one axis. tile is clamped to length; overlap is clamped to tile/2.
Axis planAxis(int length, int tile, int overlap);

// 1-D weight for tile `i` of `axis`, at output resolution `scale`. Returns tile*scale values.
std::vector<float> tileWeights(const Axis& axis, int i, int scale);

// Sum over all tiles of their 1-D weights, at output resolution. length*scale values.
std::vector<float> weightSum(const Axis& axis, int scale);

// Reflect index into [0, n) without repeating the edge pixel (like numpy "reflect").
inline int reflect(int i, int n) {
    if (n == 1) return 0;
    const int period = 2 * (n - 1);
    i %= period;
    if (i < 0) i += period;
    return i < n ? i : period - i;
}

}  // namespace rescale
