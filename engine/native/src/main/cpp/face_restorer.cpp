#include "face_restorer.h"

#include <algorithm>
#include <cmath>

#include <platform.h>

namespace rescale {

namespace {
constexpr int kDetSize = 640;
}

FaceDetector::FaceDetector(bool gpu, int threads) {
    net_.opt.num_threads = threads > 0 ? threads : 2;
    net_.opt.use_vulkan_compute = gpu && ensureGpuInstance() > 0;
}

bool FaceDetector::load(const std::string& param, const std::string& bin) {
    return net_.load_param(param.c_str()) == 0 && net_.load_model(bin.c_str()) == 0;
}

std::vector<Face> FaceDetector::detect(const Image& img, float scoreThr) {
    const int W = img.width(), H = img.height();
    const float s = static_cast<float>(kDetSize) / std::max(W, H);  // up or down
    ncnn::Mat in(kDetSize, kDetSize, 3);
    in.fill(0.f);
    const int dw = std::min(kDetSize, static_cast<int>(std::lround(W * s)));
    const int dh = std::min(kDetSize, static_cast<int>(std::lround(H * s)));
    for (int y = 0; y < dh; ++y) {
        const float sy = std::clamp((y + 0.5f) / s - 0.5f, 0.f, static_cast<float>(H - 1));
        const int y0 = static_cast<int>(sy), y1 = std::min(y0 + 1, H - 1);
        const float fy = sy - y0;
        for (int x = 0; x < dw; ++x) {
            const float sx = std::clamp((x + 0.5f) / s - 0.5f, 0.f, static_cast<float>(W - 1));
            const int x0 = static_cast<int>(sx), x1 = std::min(x0 + 1, W - 1);
            const float fx = sx - x0;
            const uint8_t *a = img.at(x0, y0), *b = img.at(x1, y0), *c = img.at(x0, y1), *d = img.at(x1, y1);
            for (int ch = 0; ch < 3; ++ch) {
                const float v = (a[ch] * (1 - fx) + b[ch] * fx) * (1 - fy) + (c[ch] * (1 - fx) + d[ch] * fx) * fy;
                in.channel(ch)[y * kDetSize + x] = v / 255.f;
            }
        }
    }
    ncnn::Extractor ex = net_.create_extractor();
    ex.input("in0", in);
    ncnn::Mat loc, conf, lm;
    if (ex.extract("out0", loc) || ex.extract("out1", conf) || ex.extract("out2", lm)) return {};
    auto faces = decodeRetina(static_cast<const float*>(loc.data), static_cast<const float*>(conf.data),
                              static_cast<const float*>(lm.data), kDetSize, scoreThr, 0.4f);
    for (Face& f : faces) {  // back to image px
        for (float& v : f.box) v /= s;
        for (float& v : f.lm) v /= s;
    }
    return faces;
}

std::vector<RestoredFace> restoreFaces(const Image& img, FaceDetector& det, TileRunner& gfpgan, int maxFaces,
                                       float maxFacePx, int* detected, std::vector<Face>* found) {
    auto faces = det.detect(img);
    if (detected) *detected = static_cast<int>(faces.size());
    if (found) *found = faces;
    std::vector<RestoredFace> out;
    std::vector<float> crop, restored;
    for (const Face& f : faces) {
        if (static_cast<int>(out.size()) >= maxFaces) break;
        const float size = std::max(f.box[2] - f.box[0], f.box[3] - f.box[1]);
        const float eyes = std::hypot(f.lm[2] - f.lm[0], f.lm[3] - f.lm[1]);
        if (size > maxFacePx || eyes < 4.f) continue;  // big faces don't need it; specks are noise
        RestoredFace r;
        r.size = 512;
        r.imageToTemplate = similarity(f.lm.data(), kFfhq512, 5);
        warpToTemplate(img, r.imageToTemplate, r.size, crop);
        if (!gfpgan.run(crop, r.size, r.size, restored)) continue;
        r.rgb = restored;
        out.push_back(std::move(r));
    }
    return out;
}

}  // namespace rescale
