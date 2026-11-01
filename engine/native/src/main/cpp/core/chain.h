// Multi-pass pipeline: e.g. de-JPEG (1×) → upscale (4×) → area downsample, or chained
// upscales. Intermediate images are streamed to raw RGBA files and memory-mapped for the next
// pass, so nothing big lives on the heap.
#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "engine.h"
#include "faces.h"

namespace rescale {

struct PassSpec {
    TileRunner* runner = nullptr;
    EngineOptions opt;
    int downsample = 1;  // area downsample applied to this pass's output
};

// Creates the sink for the final pass once its size is known.
using FinalSinkFactory = std::function<RowSink*(int width, int height, int channels)>;

struct ChainResult {
    Status status = kOk;
    int width = 0, height = 0;
};

// Tile size actually used for an output row of `outWidth` at `scale`, so the blend band
// (outWidth × tile*scale × 3 floats) fits in `maxBandBytes` (0 = no limit).
int fitTile(int tile, int outWidth, int scale, long long maxBandBytes);

// Number of tiles a pass over a w×h image will run.
int countTiles(int w, int h, const EngineOptions& opt);

// [faces]: restored faces (input-image space) blended into the final output with [faceStrength].
// Intermediates in [tmpDir] get a ".done" marker when complete; if a job is re-run with the same
// tmpDir (e.g. after the process was killed), finished passes are skipped.
ChainResult runChain(const Image& input, std::vector<PassSpec>& passes, const std::string& tmpDir,
                     long long maxBandBytes, const FinalSinkFactory& makeFinal, const ProgressFn& progress,
                     const std::atomic<bool>* cancel, std::vector<RestoredFace> faces = {},
                     float faceStrength = 0.f);

}  // namespace rescale
