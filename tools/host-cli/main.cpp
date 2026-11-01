// Desktop harness for the exact engine the app runs (CPU ncnn). Useful for checking
// tiling/blending against whole-image PyTorch output and for benchmarking on a PC.
//
//   rescale_cli <model-base> <in-blob> <out-blob> <scale> <in.rgba> <w> <h> <out.png>
//               [--orientation N] [--downsample K] [--tile T] [--threads N]
//               [--detail 0..1] [--luma 0|1] [--colorfix 0|1]
//               [--dejpeg <fbcnn-model-base> <quality 1..100>]   (1× FBCNN pre-pass)
//
// <in.rgba> is raw 8-bit RGBA (see compare_tiling.py, which also prepares it).
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "core/chain.h"
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
    PostOptions post;
    std::string dejpeg;
    int quality = 50;
    for (int i = 9; i + 1 < argc; i += 2) {
        if (!std::strcmp(argv[i], "--orientation")) orientation = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--downsample")) downsample = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--tile")) tile = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--threads")) threads = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--detail")) post.detail = static_cast<float>(std::atof(argv[i + 1]));
        else if (!std::strcmp(argv[i], "--luma")) post.lumaOnly = std::atoi(argv[i + 1]) != 0;
        else if (!std::strcmp(argv[i], "--colorfix")) post.colorFix = std::atoi(argv[i + 1]) != 0;
        else if (!std::strcmp(argv[i], "--dejpeg") && i + 2 < argc) {
            dejpeg = argv[i + 1];
            quality = std::atoi(argv[i + 2]);
            ++i;
        }
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
    std::vector<PassSpec> passes;
    std::unique_ptr<NcnnRunner> fb;
    if (!dejpeg.empty()) {
        fb = std::make_unique<NcnnRunner>(1, false, threads, "in0", "out0");
        if (!fb->load(dejpeg + ".param", dejpeg + ".bin")) {
            std::fprintf(stderr, "can't load %s\n", dejpeg.c_str());
            return 1;
        }
        fb->setExtraInput("in1", {1.0f - quality / 100.0f});
        fb->setAlignment(8);
        PassSpec p;
        p.runner = fb.get();
        p.opt.tile = 256;
        p.opt.pad = 32;
        p.opt.overlap = 32;
        passes.push_back(p);
    }
    PassSpec up;
    up.runner = &runner;
    up.opt.tile = tile;
    up.opt.post = post;
    up.downsample = downsample;
    passes.push_back(up);

    std::unique_ptr<PngWriter> png;
    const auto t0 = std::chrono::steady_clock::now();
    const ChainResult r = runChain(img, passes, "/tmp", 0,
        [&](int fw, int fh, int c) { png = std::make_unique<PngWriter>(argv[8], fw, fh, c); return png.get(); },
        [](int d, int t) { std::fprintf(stderr, "\rtile %d/%d", d, t); }, nullptr);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::fprintf(stderr, "\nstatus=%d  %dx%d -> %dx%d in %.2fs\n", r.status, img.width(), img.height(), r.width,
                 r.height, secs);
    const Status st = r.status;
    return st == kOk ? 0 : 1;
}
