// Desktop harness for the exact engine the app runs (CPU ncnn). Useful for checking
// tiling/blending against whole-image PyTorch output and for benchmarking on a PC.
//
//   rescale_cli <model-base> <in-blob> <out-blob> <scale> <in.rgba> <w> <h> <out.png>
//               [--orientation N] [--downsample K] [--tile T] [--threads N]
//
// <in.rgba> is raw 8-bit RGBA (see compare_tiling.py, which also prepares it).
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "core/engine.h"
#include "core/sinks.h"
#include "ncnn_runner.h"

using namespace rescale;

int main(int argc, char** argv) {
    if (argc < 9) {
        std::fprintf(stderr, "usage: %s model-base in-blob out-blob scale in.rgba w h out.png [opts]\n", argv[0]);
        return 2;
    }
    const std::string base = argv[1];
    const int scale = std::atoi(argv[4]);
    const int w = std::atoi(argv[6]), h = std::atoi(argv[7]);
    int orientation = 1, downsample = 1, tile = 128, threads = 4;
    for (int i = 9; i + 1 < argc; i += 2) {
        if (!std::strcmp(argv[i], "--orientation")) orientation = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--downsample")) downsample = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--tile")) tile = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--threads")) threads = std::atoi(argv[i + 1]);
    }

    std::vector<uint8_t> px(static_cast<size_t>(w) * h * 4);
    std::ifstream f(argv[5], std::ios::binary);
    if (!f.read(reinterpret_cast<char*>(px.data()), static_cast<std::streamsize>(px.size()))) {
        std::fprintf(stderr, "can't read %s\n", argv[5]);
        return 1;
    }

    NcnnRunner runner(scale, false, threads, argv[2], argv[3]);
    if (!runner.load(base + ".param", base + ".bin")) {
        std::fprintf(stderr, "can't load model %s\n", base.c_str());
        return 1;
    }
    Image img(px.data(), w, h, w * 4, orientation);
    const int C = outputChannels(img);
    const int OW = img.width() * scale, OH = img.height() * scale;
    BoxDownsample probe(OW, OH, C, downsample, nullptr);
    PngWriter png(argv[8], probe.outWidth(), probe.outHeight(), C);
    BoxDownsample down(OW, OH, C, downsample, &png);
    RowSink* sink = downsample > 1 ? static_cast<RowSink*>(&down) : &png;

    EngineOptions opt;
    opt.tile = tile;
    const auto t0 = std::chrono::steady_clock::now();
    const Status st = upscale(img, runner, opt, *sink, [](int d, int t) {
        std::fprintf(stderr, "\rtile %d/%d", d, t);
    }, nullptr);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::fprintf(stderr, "\nstatus=%d  %dx%d -> %dx%d in %.2fs\n", st, img.width(), img.height(),
                 probe.outWidth(), probe.outHeight(), secs);
    return st == kOk ? 0 : 1;
}
