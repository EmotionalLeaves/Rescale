// ncnn-backed face detection (RetinaFace) and restoration (GFPGAN).
#pragma once

#include <string>
#include <vector>

#include <net.h>

#include "core/faces.h"
#include "ncnn_runner.h"

namespace rescale {

class FaceDetector {
public:
    explicit FaceDetector(bool gpu, int threads);
    bool load(const std::string& param, const std::string& bin);
    // Faces in image px, best first. The image is letterboxed to 640² for detection.
    // 0.97 is what GFPGAN's own pipeline uses: lower thresholds paint faces onto things that
    // merely look face-like (verified: 0.6 put a face on a rocket in the NASA test photo).
    std::vector<Face> detect(const Image& img, float scoreThr = 0.97f);

private:
    ncnn::Net net_;
};

// Detects faces, keeps those small enough to benefit (box ≤ maxFacePx in the input) up to
// maxFaces, aligns each to the 512 template and restores it with [gfpgan] (scale-1 runner,
// normalization already set to [-1,1]).
std::vector<RestoredFace> restoreFaces(const Image& img, FaceDetector& det, TileRunner& gfpgan, int maxFaces,
                                       float maxFacePx, int* detected = nullptr, std::vector<Face>* found = nullptr);

}  // namespace rescale
