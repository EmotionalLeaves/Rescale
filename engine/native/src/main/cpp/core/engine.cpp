#include "engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "tiler.h"

namespace rescale {

Image::Image(const uint8_t* rgba, int srcW, int srcH, int strideBytes, int orientation)
    : px_(rgba), sw_(srcW), sh_(srcH), stride_(strideBytes), orient_(orientation) {
    if (orient_ < 1 || orient_ > 8) orient_ = 1;
    const bool swap = orient_ >= 5;
    w_ = swap ? sh_ : sw_;
    h_ = swap ? sw_ : sh_;
}

const uint8_t* Image::at(int x, int y) const {
    int sx, sy;
    switch (orient_) {
        case 2: sx = sw_ - 1 - x; sy = y; break;               // mirror horizontal
        case 3: sx = sw_ - 1 - x; sy = sh_ - 1 - y; break;     // rotate 180
        case 4: sx = x; sy = sh_ - 1 - y; break;               // mirror vertical
        case 5: sx = y; sy = x; break;                         // transpose
        case 6: sx = y; sy = sh_ - 1 - x; break;               // rotate 90 CW
        case 7: sx = sw_ - 1 - y; sy = sh_ - 1 - x; break;     // transverse
        case 8: sx = sw_ - 1 - y; sy = x; break;               // rotate 270 CW
        default: sx = x; sy = y; break;
    }
    return px_ + static_cast<size_t>(sy) * stride_ + static_cast<size_t>(sx) * 4;
}

bool Image::hasAlpha() const {
    for (int y = 0; y < sh_; ++y) {
        const uint8_t* row = px_ + static_cast<size_t>(y) * stride_;
        for (int x = 0; x < sw_; ++x)
            if (row[x * 4 + 3] != 255) return true;
    }
    return false;
}

namespace {

// Bilinear alpha at output pixel (ox, oy) for upscale factor s (pixel-centre aligned).
uint8_t sampleAlpha(const Image& img, int ox, int oy, int s) {
    const float fx = std::clamp((ox + 0.5f) / s - 0.5f, 0.0f, static_cast<float>(img.width() - 1));
    const float fy = std::clamp((oy + 0.5f) / s - 0.5f, 0.0f, static_cast<float>(img.height() - 1));
    const int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
    const int x1 = std::min(x0 + 1, img.width() - 1), y1 = std::min(y0 + 1, img.height() - 1);
    const float tx = fx - x0, ty = fy - y0;
    const float a = img.at(x0, y0)[3] * (1 - tx) + img.at(x1, y0)[3] * tx;
    const float b = img.at(x0, y1)[3] * (1 - tx) + img.at(x1, y1)[3] * tx;
    return static_cast<uint8_t>(std::lround(a * (1 - ty) + b * ty));
}

inline uint8_t toByte(float v) {
    v = std::clamp(v, 0.0f, 1.0f);  // clamp in float before quantising
    return static_cast<uint8_t>(std::lround(v * 255.0f));
}

}  // namespace

Status upscale(const Image& img, TileRunner& runner, const EngineOptions& opt, RowSink& sink,
               const ProgressFn& progress, const std::atomic<bool>* cancel) {
    const int W = img.width(), H = img.height();
    if (W <= 0 || H <= 0) return kBadInput;
    const int s = runner.scale();
    const int OW = W * s;
    const int C = outputChannels(img);

    const Axis ax = planAxis(W, opt.tile, opt.overlap);
    const Axis ay = planAxis(H, opt.tile, opt.overlap);
    const auto sumX = weightSum(ax, s);
    const auto sumY = weightSum(ay, s);
    std::vector<std::vector<float>> wxs(ax.starts.size());
    for (size_t i = 0; i < wxs.size(); ++i) wxs[i] = tileWeights(ax, static_cast<int>(i), s);

    const int tw = ax.tile, th = ay.tile, pad = opt.pad;
    const int inW = tw + 2 * pad, inH = th + 2 * pad;
    const int bandRows = th * s;

    // Accumulator for the current band: bandRows rows of OW pixels, RGB float.
    std::vector<float> acc(static_cast<size_t>(bandRows) * OW * 3, 0.0f);
    int bandTop = 0;  // output row index of acc row 0
    int emitted = 0;  // rows already pushed to the sink

    std::vector<float> in(static_cast<size_t>(3) * inW * inH), out, scratch;
    std::vector<uint8_t> row(static_cast<size_t>(OW) * C);

    const int total = static_cast<int>(ax.starts.size() * ay.starts.size());
    int done = 0;

    auto emitUpTo = [&](int limit) -> bool {  // emit output rows [emitted, limit)
        for (; emitted < limit; ++emitted) {
            const float* a = &acc[static_cast<size_t>(emitted - bandTop) * OW * 3];
            const float wy = sumY[emitted];
            for (int x = 0; x < OW; ++x) {
                const float norm = 1.0f / (sumX[x] * wy);
                uint8_t* p = &row[static_cast<size_t>(x) * C];
                p[0] = toByte(a[x * 3 + 0] * norm);
                p[1] = toByte(a[x * 3 + 1] * norm);
                p[2] = toByte(a[x * 3 + 2] * norm);
                if (C == 4) p[3] = sampleAlpha(img, x, emitted, s);
            }
            if (!sink.push(row.data())) return false;
        }
        return true;
    };

    for (size_t ty = 0; ty < ay.starts.size(); ++ty) {
        const int y0 = ay.starts[ty];
        // Slide the band so it starts at this tile row; rows above were already emitted.
        const int newTop = y0 * s;
        if (newTop != bandTop) {
            const int keep = std::max(0, bandTop + bandRows - newTop);
            const size_t rowFloats = static_cast<size_t>(OW) * 3;
            std::memmove(acc.data(), acc.data() + static_cast<size_t>(newTop - bandTop) * rowFloats,
                         keep * rowFloats * sizeof(float));
            std::fill(acc.begin() + keep * rowFloats, acc.end(), 0.0f);
            bandTop = newTop;
        }
        const auto wy = tileWeights(ay, static_cast<int>(ty), s);

        for (size_t tx = 0; tx < ax.starts.size(); ++tx) {
            if (cancel && cancel->load()) return kCancelled;
            const int x0 = ax.starts[tx];

            // Build the reflect-padded planar input tile.
            const size_t plane = static_cast<size_t>(inW) * inH;
            for (int j = 0; j < inH; ++j) {
                const int sy = reflect(y0 - pad + j, H);
                for (int i = 0; i < inW; ++i) {
                    const uint8_t* p = img.at(reflect(x0 - pad + i, W), sy);
                    const size_t k = static_cast<size_t>(j) * inW + i;
                    in[k] = p[0] * (1.0f / 255.0f);
                    in[plane + k] = p[1] * (1.0f / 255.0f);
                    in[2 * plane + k] = p[2] * (1.0f / 255.0f);
                }
            }
            if (!runner.run(in, inW, inH, out)) return kModelError;
            const int ow = inW * s;
            const size_t oplane = static_cast<size_t>(ow) * inH * s;
            if (out.size() < 3 * oplane) return kModelError;
            if (opt.post.active()) postProcessTile(in, inW, inH, s, opt.post, out, scratch);

            // Crop the padding away and accumulate weighted pixels into the band.
            const auto& wx = wxs[tx];
            for (int v = 0; v < th * s; ++v) {
                const size_t srcRow = static_cast<size_t>(v + pad * s) * ow + pad * s;
                float* dst = &acc[(static_cast<size_t>(v) * OW + static_cast<size_t>(x0) * s) * 3];
                for (int u = 0; u < tw * s; ++u) {
                    const float w = wx[u] * wy[v];
                    dst[u * 3 + 0] += w * out[srcRow + u];
                    dst[u * 3 + 1] += w * out[oplane + srcRow + u];
                    dst[u * 3 + 2] += w * out[2 * oplane + srcRow + u];
                }
            }
            if (progress) progress(++done, total);
        }

        // Rows above the next tile row's start can no longer change.
        const int finalRows = ty + 1 < ay.starts.size() ? ay.starts[ty + 1] * s : H * s;
        if (!emitUpTo(finalRows)) return kWriteError;
    }
    return sink.finish() ? kOk : kWriteError;
}

}  // namespace rescale
