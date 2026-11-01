// Tiled upscaling engine: reads an (oriented) RGBA8 image, runs the model tile by tile with
// reflect padding, cross-fades overlapping tiles and streams finished rows to a RowSink.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <vector>

#include "sinks.h"

namespace rescale {

// Read-only RGBA8 source with an EXIF orientation (1..8) applied on the fly.
class Image {
public:
    Image(const uint8_t* rgba, int srcW, int srcH, int strideBytes, int orientation = 1);
    int width() const { return w_; }
    int height() const { return h_; }
    const uint8_t* at(int x, int y) const;  // x,y in oriented space
    bool hasAlpha() const;

private:
    const uint8_t* px_;
    int sw_, sh_, stride_, orient_, w_, h_;
};

// Runs the network on one planar float tile in [0,1]. Output must be (w*scale)x(h*scale).
class TileRunner {
public:
    virtual ~TileRunner() = default;
    virtual int scale() const = 0;
    // in: 3 planes of w*h floats (R,G,B). out: resized to 3 planes of (w*s)*(h*s).
    virtual bool run(const std::vector<float>& in, int w, int h, std::vector<float>& out) = 0;
};

struct EngineOptions {
    int tile = 128;       // input pixels per tile edge (before padding)
    int pad = 12;         // reflect-padded context per side, cropped away after inference
    int overlap = 16;     // minimum cross-faded overlap between neighbouring tiles
};

enum Status : int {
    kOk = 0,
    kCancelled = 1,
    kModelError = -1,
    kWriteError = -2,
    kBadInput = -3,
};

using ProgressFn = std::function<void(int done, int total)>;

// Output rows are width*scale wide, with 4 channels if the image has alpha, else 3.
Status upscale(const Image& img, TileRunner& runner, const EngineOptions& opt, RowSink& sink,
               const ProgressFn& progress, const std::atomic<bool>* cancel);

inline int outputChannels(const Image& img) { return img.hasAlpha() ? 4 : 3; }

}  // namespace rescale
