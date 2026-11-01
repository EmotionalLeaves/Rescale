// Desktop harness for the exact engine the app runs (CPU ncnn). Useful for checking
// tiling/blending against whole-image PyTorch output and for benchmarking on a PC.
//
//   rescale_cli <model-base> <in-blob> <out-blob> <scale> <in.rgba> <w> <h> <out.png>
//               [--orientation N] [--downsample K] [--tile T] [--threads N]
//               [--detail 0..1] [--luma 0|1] [--colorfix 0|1]
//               [--faces <retinaface-base> <gfpgan-base>] [--face-strength 0..1]
//               [--repeat K]  (chain the upscaler K times)
//               [--dejpeg <fbcnn-model-base> <quality 1..100>]   (1× FBCNN pre-pass)
//               [--pad P] [--align A] [--norm MUL ADD] [--statmatch 0|1] [--lowmem 0|1]   (runner options, e.g. AdcSR)
//               [--repair <detector-base|-> <inpainter-base>] [--mask mask.gray]   (damage repair pre-stage;
//                  mask.gray: w*h bytes, nonzero = fill; detector "-" = only the given mask)
//               [--save-repaired out.png]
//
// <in.rgba> is raw 8-bit RGBA (see compare_tiling.py, which also prepares it).
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "core/chain.h"
#include "core/engine.h"
#include "core/sinks.h"
#include "face_restorer.h"
#include "repairer.h"
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
    int repeat = 1;
    std::string faceDet, faceGen;
    float faceStrength = 0.7f;
    int quality = 50;
    int pad = 12, align = 1, statMatch = 0, lowMem = 0;
    float normMul = 1.f, normAdd = 0.f;
    std::string repDet, repNet, maskPath, savedRepaired;
    for (int i = 9; i + 1 < argc; i += 2) {
        if (!std::strcmp(argv[i], "--orientation")) orientation = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--downsample")) downsample = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--tile")) tile = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--threads")) threads = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--detail")) post.detail = static_cast<float>(std::atof(argv[i + 1]));
        else if (!std::strcmp(argv[i], "--luma")) post.lumaOnly = std::atoi(argv[i + 1]) != 0;
        else if (!std::strcmp(argv[i], "--colorfix")) post.colorFix = std::atoi(argv[i + 1]) != 0;
        else if (!std::strcmp(argv[i], "--repeat")) repeat = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--face-strength")) faceStrength = static_cast<float>(std::atof(argv[i + 1]));
        else if (!std::strcmp(argv[i], "--faces") && i + 2 < argc) {
            faceDet = argv[i + 1];
            faceGen = argv[i + 2];
            ++i;
        }
        else if (!std::strcmp(argv[i], "--pad")) pad = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--align")) align = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--statmatch")) statMatch = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--lowmem")) lowMem = std::atoi(argv[i + 1]);
        else if (!std::strcmp(argv[i], "--mask")) maskPath = argv[i + 1];
        else if (!std::strcmp(argv[i], "--save-repaired")) savedRepaired = argv[i + 1];
        else if (!std::strcmp(argv[i], "--norm") && i + 2 < argc) {
            normMul = static_cast<float>(std::atof(argv[i + 1]));
            normAdd = static_cast<float>(std::atof(argv[i + 2]));
            ++i;
        }
        else if (!std::strcmp(argv[i], "--repair") && i + 2 < argc) {
            repDet = argv[i + 1];
            repNet = argv[i + 2];
            ++i;
        }
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
    runner.setLowMemory(lowMem != 0);
    if (!runner.load(base + ".param", base + ".bin")) {
        std::fprintf(stderr, "can't load model %s\n", base.c_str());
        return 1;
    }
    runner.setAlignment(align);
    runner.setNormalization(normMul, normAdd);
    runner.setStatMatch(statMatch != 0);
    Image oriented(px.data(), w, h, w * 4, orientation);
    // Damage repair works on an oriented copy, which then feeds the chain.
    std::vector<uint8_t> repaired;
    std::unique_ptr<Image> repairedImg;
    if (!repNet.empty()) {
        const int W = oriented.width(), H = oriented.height();
        std::vector<uint8_t> mask(static_cast<size_t>(W) * H, 0);
        const auto tr = std::chrono::steady_clock::now();
        if (repDet != "-") {
            ScratchDetector det(false, threads);
            if (!det.load(repDet + ".param", repDet + ".bin") || !det.detect(oriented, mask)) {
                std::fprintf(stderr, "scratch detection failed\n");
                return 1;
            }
        }
        if (!maskPath.empty()) {
            std::ifstream mf(maskPath, std::ios::binary);
            std::vector<uint8_t> user(mask.size());
            if (!mf.read(reinterpret_cast<char*>(user.data()), static_cast<std::streamsize>(user.size()))) {
                std::fprintf(stderr, "can't read mask %s\n", maskPath.c_str());
                return 1;
            }
            for (size_t i = 0; i < mask.size(); ++i) mask[i] |= user[i] ? 1 : 0;
        }
        size_t holes = 0;
        for (uint8_t m : mask) holes += m;
        repaired.resize(static_cast<size_t>(W) * H * 4);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) std::memcpy(&repaired[(static_cast<size_t>(y) * W + x) * 4], oriented.at(x, y), 4);
        NcnnInpainter inp(false, threads);
        if (!inp.load(repNet + ".param", repNet + ".bin")) {
            std::fprintf(stderr, "can't load inpainter\n");
            return 1;
        }
        const Status rs = repairImage(repaired, W, H, mask, inp, [](int d, int t) { std::fprintf(stderr, "\rrepair %d/%d", d, t); },
                                      nullptr);
        std::fprintf(stderr, "\nrepair: %.2f%% of pixels, status %d, %.2fs\n", 100.0 * holes / mask.size(), rs,
                     std::chrono::duration<double>(std::chrono::steady_clock::now() - tr).count());
        if (rs != kOk) return 1;
        if (!savedRepaired.empty()) {
            PngWriter pw(savedRepaired, W, H, 3);
            std::vector<uint8_t> row(static_cast<size_t>(W) * 3);
            for (int y = 0; y < H; ++y) {
                for (int x = 0; x < W; ++x) std::memcpy(&row[x * 3], &repaired[(static_cast<size_t>(y) * W + x) * 4], 3);
                pw.push(row.data());
            }
            pw.finish();
        }
        repairedImg = std::make_unique<Image>(repaired.data(), W, H, W * 4, 1);
    }
    const Image& img = repairedImg ? *repairedImg : oriented;
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
    // --repeat K chains the upscaler K times like the app does for 16×/32× (pre-blur, decaying detail).
    for (int k = 0; k < repeat; ++k) {
        PassSpec up;
        up.runner = &runner;
        up.opt.tile = tile;
        up.opt.pad = pad;
        up.opt.overlap = std::max(16, pad);
        up.opt.post = post;
        if (k > 0) {
            up.opt.post.preBlur = 0.4f;
            for (int j = 0; j < k; ++j) up.opt.post.detail *= 0.7f;
        }
        up.downsample = k + 1 == repeat ? downsample : 1;
        passes.push_back(up);
    }

    std::vector<RestoredFace> faces;
    const auto tf = std::chrono::steady_clock::now();
    if (!faceDet.empty()) {
        FaceDetector det(false, threads);
        NcnnRunner gfp(1, false, threads, "in0", "out0");
        if (!det.load(faceDet + ".param", faceDet + ".bin") || !gfp.load(faceGen + ".param", faceGen + ".bin")) {
            std::fprintf(stderr, "can't load face models\n");
            return 1;
        }
        gfp.setNormalization(2.f, -1.f);
        int found = 0;
        std::vector<Face> raw;
        faces = restoreFaces(img, det, gfp, 8, 400.f, &found, &raw);
        for (const Face& f : raw)
            std::fprintf(stderr, "  face score %.3f box %.0f,%.0f-%.0f,%.0f\n", f.score, f.box[0], f.box[1], f.box[2], f.box[3]);
        std::fprintf(stderr, "faces: %d detected, %zu restored in %.2fs\n", found, faces.size(),
                     std::chrono::duration<double>(std::chrono::steady_clock::now() - tf).count());
    }

    std::unique_ptr<PngWriter> png;
    const auto t0 = std::chrono::steady_clock::now();
    const ChainResult r = runChain(img, passes, "/tmp", 0,
        [&](int fw, int fh, int c) { png = std::make_unique<PngWriter>(argv[8], fw, fh, c); return png.get(); },
        [](int d, int t) { std::fprintf(stderr, "\rtile %d/%d", d, t); }, nullptr, std::move(faces), faceStrength);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::fprintf(stderr, "\nstatus=%d  %dx%d -> %dx%d in %.2fs\n", r.status, img.width(), img.height(), r.width,
                 r.height, secs);
    const Status st = r.status;
    return st == kOk ? 0 : 1;
}
