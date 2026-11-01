#include "faces.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace rescale {

const float kFfhq512[10] = {192.98138f, 239.94708f, 318.90277f, 240.19360f, 256.63416f,
                            314.01935f, 201.26117f, 371.41043f, 313.08905f, 371.15118f};

Affine Affine::inverse() const {
    const float det = a * e - b * d;
    Affine r;
    if (std::fabs(det) < 1e-12f) return r;
    r.a = e / det;
    r.b = -b / det;
    r.d = -d / det;
    r.e = a / det;
    r.c = -(r.a * c + r.b * f);
    r.f = -(r.d * c + r.e * f);
    return r;
}

std::vector<std::array<float, 4>> retinaPriors(int size) {
    static const int steps[3] = {8, 16, 32};
    static const int minSizes[3][2] = {{16, 32}, {64, 128}, {256, 512}};
    std::vector<std::array<float, 4>> p;
    for (int k = 0; k < 3; ++k) {
        const int fm = (size + steps[k] - 1) / steps[k];
        for (int i = 0; i < fm; ++i)
            for (int j = 0; j < fm; ++j)
                for (int m : minSizes[k])
                    p.push_back({(j + 0.5f) * steps[k] / size, (i + 0.5f) * steps[k] / size,
                                 static_cast<float>(m) / size, static_cast<float>(m) / size});
    }
    return p;
}

static float iou(const Face& a, const Face& b) {
    const float x0 = std::max(a.box[0], b.box[0]), y0 = std::max(a.box[1], b.box[1]);
    const float x1 = std::min(a.box[2], b.box[2]), y1 = std::min(a.box[3], b.box[3]);
    const float inter = std::max(0.f, x1 - x0) * std::max(0.f, y1 - y0);
    const float ua = (a.box[2] - a.box[0]) * (a.box[3] - a.box[1]) + (b.box[2] - b.box[0]) * (b.box[3] - b.box[1]);
    return ua > 0 ? inter / (ua - inter) : 0.f;
}

std::vector<Face> decodeRetina(const float* loc, const float* conf, const float* lm, int size, float scoreThr,
                               float nmsThr) {
    const auto priors = retinaPriors(size);
    std::vector<Face> cand;
    for (size_t i = 0; i < priors.size(); ++i) {
        const float s = conf[i * 2 + 1];
        if (s < scoreThr) continue;
        const auto& p = priors[i];
        Face f;
        f.score = s;
        const float cx = p[0] + loc[i * 4 + 0] * 0.1f * p[2];
        const float cy = p[1] + loc[i * 4 + 1] * 0.1f * p[3];
        const float w = p[2] * std::exp(loc[i * 4 + 2] * 0.2f);
        const float h = p[3] * std::exp(loc[i * 4 + 3] * 0.2f);
        f.box = {(cx - w / 2) * size, (cy - h / 2) * size, (cx + w / 2) * size, (cy + h / 2) * size};
        for (int k = 0; k < 5; ++k) {
            f.lm[k * 2] = (p[0] + lm[i * 10 + k * 2] * 0.1f * p[2]) * size;
            f.lm[k * 2 + 1] = (p[1] + lm[i * 10 + k * 2 + 1] * 0.1f * p[3]) * size;
        }
        cand.push_back(f);
    }
    std::sort(cand.begin(), cand.end(), [](const Face& a, const Face& b) { return a.score > b.score; });
    std::vector<Face> keep;
    for (const Face& f : cand) {
        bool ok = true;
        for (const Face& k : keep)
            if (iou(f, k) > nmsThr) {
                ok = false;
                break;
            }
        if (ok) keep.push_back(f);
    }
    return keep;
}

Affine similarity(const float* src, const float* dst, int n) {
    // Solve [x -y 1 0; y x 0 1] [a b tx ty]^T = [x'; y'] in least squares (4×4 normal equations).
    double ata[4][4] = {}, atb[4] = {};
    for (int i = 0; i < n; ++i) {
        const double x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1];
        const double r1[4] = {x, -y, 1, 0}, r2[4] = {y, x, 0, 1};
        for (int r = 0; r < 4; ++r) {
            for (int c = 0; c < 4; ++c) ata[r][c] += r1[r] * r1[c] + r2[r] * r2[c];
            atb[r] += r1[r] * u + r2[r] * v;
        }
    }
    // Gaussian elimination with partial pivoting.
    double m[4][5];
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) m[r][c] = ata[r][c];
        m[r][4] = atb[r];
    }
    for (int col = 0; col < 4; ++col) {
        int piv = col;
        for (int r = col + 1; r < 4; ++r)
            if (std::fabs(m[r][col]) > std::fabs(m[piv][col])) piv = r;
        for (int c = 0; c < 5; ++c) std::swap(m[col][c], m[piv][c]);
        if (std::fabs(m[col][col]) < 1e-12) return Affine{};
        for (int r = 0; r < 4; ++r) {
            if (r == col) continue;
            const double k = m[r][col] / m[col][col];
            for (int c = col; c < 5; ++c) m[r][c] -= k * m[col][c];
        }
    }
    const double sa = m[0][4] / m[0][0], sb = m[1][4] / m[1][1], tx = m[2][4] / m[2][2], ty = m[3][4] / m[3][3];
    Affine t;
    t.a = static_cast<float>(sa);
    t.b = static_cast<float>(-sb);
    t.c = static_cast<float>(tx);
    t.d = static_cast<float>(sb);
    t.e = static_cast<float>(sa);
    t.f = static_cast<float>(ty);
    return t;
}

void warpToTemplate(const Image& img, const Affine& imageToTemplate, int size, std::vector<float>& out) {
    const Affine inv = imageToTemplate.inverse();
    const size_t plane = static_cast<size_t>(size) * size;
    out.assign(3 * plane, 0.f);
    const int W = img.width(), H = img.height();
    for (int ty = 0; ty < size; ++ty)
        for (int tx = 0; tx < size; ++tx) {
            float x, y;
            inv.apply(tx + 0.5f, ty + 0.5f, x, y);
            x -= 0.5f;
            y -= 0.5f;
            const float cx = std::clamp(x, 0.f, static_cast<float>(W - 1));
            const float cy = std::clamp(y, 0.f, static_cast<float>(H - 1));
            const int x0 = static_cast<int>(cx), y0 = static_cast<int>(cy);
            const int x1 = std::min(x0 + 1, W - 1), y1 = std::min(y0 + 1, H - 1);
            const float fx = cx - x0, fy = cy - y0;
            const uint8_t *p00 = img.at(x0, y0), *p10 = img.at(x1, y0), *p01 = img.at(x0, y1), *p11 = img.at(x1, y1);
            for (int c = 0; c < 3; ++c) {
                const float v = (p00[c] * (1 - fx) + p10[c] * fx) * (1 - fy) + (p01[c] * (1 - fx) + p11[c] * fx) * fy;
                out[c * plane + static_cast<size_t>(ty) * size + tx] = v / 255.f;
            }
        }
}

FaceCompositor::FaceCompositor(std::vector<RestoredFace> faces, float scale, int width, int channels, float strength,
                               RowSink* next)
    : scale_(scale), strength_(std::clamp(strength, 0.f, 1.f)), w_(width), c_(channels), next_(next) {
    row_.resize(static_cast<size_t>(width) * channels);
    for (auto& f : faces) {
        // Output-space bounding box of the template square.
        const Affine inv = f.imageToTemplate.inverse();
        float xs[4], ys[4];
        const float s = static_cast<float>(f.size);
        inv.apply(0, 0, xs[0], ys[0]);
        inv.apply(s, 0, xs[1], ys[1]);
        inv.apply(0, s, xs[2], ys[2]);
        inv.apply(s, s, xs[3], ys[3]);
        const float minx = *std::min_element(xs, xs + 4), maxx = *std::max_element(xs, xs + 4);
        const float miny = *std::min_element(ys, ys + 4), maxy = *std::max_element(ys, ys + 4);
        Placed p{std::move(f), 0, 0, 0, 0};
        p.x0 = std::max(0, static_cast<int>(std::floor(minx * scale)));
        p.x1 = std::min(width, static_cast<int>(std::ceil(maxx * scale)) + 1);
        p.y0 = static_cast<int>(std::floor(miny * scale));
        p.y1 = static_cast<int>(std::ceil(maxy * scale)) + 1;
        faces_.push_back(std::move(p));
    }
}

namespace {
// 0 at the template border, 1 inside a margin (smoothstep): hides the crop edge.
inline float feather(float t, float size) {
    const float m = size * 0.09f;
    const float d = std::min(t, size - t);
    if (d <= 0) return 0.f;
    if (d >= m) return 1.f;
    const float u = d / m;
    return u * u * (3 - 2 * u);
}
}  // namespace

bool FaceCompositor::push(const uint8_t* in) {
    const int y = y_++;
    bool touched = false;
    for (const Placed& p : faces_) {
        if (y < p.y0 || y >= p.y1) continue;
        if (!touched) {
            std::memcpy(row_.data(), in, row_.size());
            touched = true;
        }
        const RestoredFace& f = p.face;
        const int S = f.size;
        const size_t plane = static_cast<size_t>(S) * S;
        const float iy = (y + 0.5f) / scale_ - 0.5f;
        for (int x = p.x0; x < p.x1; ++x) {
            const float ix = (x + 0.5f) / scale_ - 0.5f;
            float tx, ty;
            f.imageToTemplate.apply(ix + 0.5f, iy + 0.5f, tx, ty);
            const float wgt = feather(tx, static_cast<float>(S)) * feather(ty, static_cast<float>(S)) * strength_;
            if (wgt <= 0.f) continue;
            const float sx = std::clamp(tx - 0.5f, 0.f, static_cast<float>(S - 1));
            const float sy = std::clamp(ty - 0.5f, 0.f, static_cast<float>(S - 1));
            const int x0 = static_cast<int>(sx), y0 = static_cast<int>(sy);
            const int x1 = std::min(x0 + 1, S - 1), y1 = std::min(y0 + 1, S - 1);
            const float fx = sx - x0, fy = sy - y0;
            uint8_t* px = &row_[static_cast<size_t>(x) * c_];
            for (int c = 0; c < 3; ++c) {
                const float* pl = f.rgb.data() + c * plane;
                const float v = (pl[y0 * S + x0] * (1 - fx) + pl[y0 * S + x1] * fx) * (1 - fy) +
                                (pl[y1 * S + x0] * (1 - fx) + pl[y1 * S + x1] * fx) * fy;
                const float o = px[c] * (1 - wgt) + std::clamp(v, 0.f, 1.f) * 255.f * wgt;
                px[c] = static_cast<uint8_t>(std::lround(std::clamp(o, 0.f, 255.f)));
            }
        }
    }
    return next_->push(touched ? row_.data() : in);
}

}  // namespace rescale
