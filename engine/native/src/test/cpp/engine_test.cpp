// Host-side tests for the tiling engine. No model, no Android: a fake runner does a
// nearest-neighbour upscale (optionally with a per-tile bias) so we can check that
// tiling, padding, blending and streaming are seamless and exact.
//
// Build: cmake -S engine/native/src/test/cpp -B build/host && cmake --build build/host && ctest --test-dir build/host
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <zlib.h>

#include "chain.h"
#include "engine.h"
#include "sinks.h"
#include "tiler.h"

using namespace rescale;

static int failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

class NearestRunner : public TileRunner {
public:
    explicit NearestRunner(int s, float bias = 0.f) : s_(s), bias_(bias) {}
    int scale() const override { return s_; }
    bool run(const std::vector<float>& in, int w, int h, std::vector<float>& out) override {
        const int ow = w * s_, oh = h * s_;
        out.assign(static_cast<size_t>(3) * ow * oh, 0.f);
        for (int c = 0; c < 3; ++c)
            for (int y = 0; y < oh; ++y)
                for (int x = 0; x < ow; ++x)
                    out[(static_cast<size_t>(c) * oh + y) * ow + x] =
                        in[(static_cast<size_t>(c) * h + y / s_) * w + x / s_] + bias_;
        bias_ = -bias_;  // alternate the bias per tile: seams would show up as steps
        ++calls;
        return true;
    }
    int calls = 0;

private:
    int s_;
    float bias_;
};

// Nearest upscale plus a constant per-channel offset (a model with colour drift).
class ShiftRunner : public TileRunner {
public:
    ShiftRunner(int s, float r, float g, float b) : nn_(s), d_{r, g, b} {}
    int scale() const override { return nn_.scale(); }
    bool run(const std::vector<float>& in, int w, int h, std::vector<float>& out) override {
        nn_.run(in, w, h, out);
        const size_t plane = out.size() / 3;
        for (int c = 0; c < 3; ++c)
            for (size_t i = 0; i < plane; ++i) out[c * plane + i] += d_[c];
        return true;
    }

private:
    NearestRunner nn_;
    float d_[3];
};

static std::vector<uint8_t> makeImage(int w, int h, bool gradient, bool alpha = false) {
    std::vector<uint8_t> px(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            uint8_t* p = &px[(static_cast<size_t>(y) * w + x) * 4];
            if (gradient) {
                p[0] = static_cast<uint8_t>(x * 255 / std::max(1, w - 1));
                p[1] = static_cast<uint8_t>(y * 255 / std::max(1, h - 1));
                p[2] = static_cast<uint8_t>((x + y) * 255 / std::max(1, w + h - 2));
            } else {
                p[0] = 200; p[1] = 90; p[2] = 30;
            }
            p[3] = alpha ? static_cast<uint8_t>(x * 255 / std::max(1, w - 1)) : 255;
        }
    return px;
}

static void testPlanAxis() {
    for (int len : {1, 7, 100, 128, 129, 300, 1000, 1023}) {
        for (int tile : {32, 64, 128}) {
            const Axis a = planAxis(len, tile, 16);
            CHECK(!a.starts.empty());
            CHECK(a.starts.front() == 0);
            CHECK(a.starts.back() + a.tile == len);
            for (size_t i = 1; i < a.starts.size(); ++i) {
                CHECK(a.starts[i] > a.starts[i - 1]);
                CHECK(a.starts[i - 1] + a.tile - a.starts[i] >= std::min(16, a.tile / 2));
            }
            // Normaliser must be strictly positive everywhere.
            for (float w : weightSum(a, 4)) CHECK(w > 0.f);
        }
    }
}

static void testReflect() {
    CHECK(reflect(-1, 5) == 1);
    CHECK(reflect(-2, 5) == 2);
    CHECK(reflect(5, 5) == 3);
    CHECK(reflect(6, 5) == 2);
    CHECK(reflect(0, 1) == 0);
    CHECK(reflect(-30, 5) >= 0 && reflect(-30, 5) < 5);
}

// Seam test: stitched output of a nearest runner must equal a global nearest upscale.
static void testSeamless(int w, int h, int s, bool gradient, float bias, int maxErr) {
    auto px = makeImage(w, h, gradient);
    Image img(px.data(), w, h, w * 4);
    NearestRunner runner(s, bias);
    MemorySink mem(w * s, 3);
    EngineOptions opt;
    opt.tile = 48;
    opt.pad = 6;
    opt.overlap = 12;
    const Status st = upscale(img, runner, opt, mem, nullptr, nullptr);
    CHECK(st == kOk);
    CHECK(mem.rows == h * s);
    int worst = 0;
    for (int y = 0; y < h * s; ++y)
        for (int x = 0; x < w * s; ++x)
            for (int c = 0; c < 3; ++c) {
                const int want = px[(static_cast<size_t>(y / s) * w + x / s) * 4 + c];
                const int got = mem.data[(static_cast<size_t>(y) * w * s + x) * 3 + c];
                worst = std::max(worst, std::abs(want - got));
            }
    if (worst > maxErr) std::fprintf(stderr, "  %dx%d s=%d bias=%g worst=%d\n", w, h, s, bias, worst);
    CHECK(worst <= maxErr);
}

// With alternating +/- bias per tile, the blend must still be smooth: no jump between
// neighbouring output pixels bigger than what the ramp allows.
static void testFeatherIsSmooth() {
    const int w = 200, h = 150, s = 2;
    auto px = makeImage(w, h, false);
    Image img(px.data(), w, h, w * 4);
    NearestRunner runner(s, 0.1f);
    MemorySink mem(w * s, 3);
    EngineOptions opt;
    opt.tile = 64;
    opt.pad = 4;
    opt.overlap = 24;
    CHECK(upscale(img, runner, opt, mem, nullptr, nullptr) == kOk);
    int maxStep = 0;
    for (int y = 0; y < h * s; ++y)
        for (int x = 1; x < w * s; ++x) {
            const int a = mem.data[(static_cast<size_t>(y) * w * s + x - 1) * 3];
            const int b = mem.data[(static_cast<size_t>(y) * w * s + x) * 3];
            maxStep = std::max(maxStep, std::abs(a - b));
        }
    // A hard cut would jump 2*0.1*255 = 51 levels. The Hann ramp spreads it out.
    CHECK(maxStep <= 4);
}

static void testOrientation() {
    // 3x2 image, rotate 90 CW (EXIF 6) -> 2x3.
    std::vector<uint8_t> px(3 * 2 * 4, 255);
    for (int i = 0; i < 6; ++i) px[i * 4] = static_cast<uint8_t>(i);  // R = index
    Image img(px.data(), 3, 2, 12, 6);
    CHECK(img.width() == 2 && img.height() == 3);
    // Displayed top-left comes from source bottom-left (index 3).
    CHECK(img.at(0, 0)[0] == 3);
    CHECK(img.at(1, 0)[0] == 0);
    CHECK(img.at(1, 2)[0] == 2);
    Image r8(px.data(), 3, 2, 12, 8);
    CHECK(r8.at(0, 0)[0] == 2);
    Image r3(px.data(), 3, 2, 12, 3);
    CHECK(r3.at(0, 0)[0] == 5);
}

static void testAlphaAndDownsample() {
    const int w = 50, h = 40, s = 4;
    auto px = makeImage(w, h, true, true);
    Image img(px.data(), w, h, w * 4);
    CHECK(img.hasAlpha());
    NearestRunner runner(s);
    MemorySink mem((w * s + 1) / 2, 4);
    BoxDownsample down(w * s, h * s, 4, 2, &mem);
    EngineOptions opt;
    opt.tile = 32;
    CHECK(upscale(img, runner, opt, down, nullptr, nullptr) == kOk);
    CHECK(mem.rows == h * 2);
    // Alpha ramps left to right.
    const uint8_t* row = &mem.data[static_cast<size_t>(10) * w * 2 * 4];
    CHECK(row[3] < 10);
    CHECK(row[(w * 2 - 1) * 4 + 3] > 245);
}

static void testCancel() {
    const int w = 300, h = 300;
    auto px = makeImage(w, h, true);
    Image img(px.data(), w, h, w * 4);
    NearestRunner runner(2);
    MemorySink mem(w * 2, 3);
    std::atomic<bool> cancel{false};
    EngineOptions opt;
    opt.tile = 64;
    int seen = 0;
    const Status st = upscale(img, runner, opt, mem,
                              [&](int, int) { if (++seen == 3) cancel = true; }, &cancel);
    CHECK(st == kCancelled);
    CHECK(runner.calls == 3);
}

static void testPngRoundTrip(const char* dir) {
    const int w = 77, h = 33;
    auto px = makeImage(w, h, true);
    const std::string path = std::string(dir) + "/rt.png";
    {
        PngWriter png(path, w, h, 3);
        CHECK(png.ok());
        std::vector<uint8_t> row(w * 3);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) std::memcpy(&row[x * 3], &px[(y * w + x) * 4], 3);
            CHECK(png.push(row.data()));
        }
        CHECK(png.finish());
    }
    // Decode: walk chunks, inflate IDAT, unfilter Paeth, compare.
    FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr);
    if (!f) return;
    std::vector<uint8_t> file;
    uint8_t buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) file.insert(file.end(), buf, buf + n);
    std::fclose(f);
    CHECK(file.size() > 8 && file[1] == 'P');
    std::vector<uint8_t> idat;
    size_t pos = 8;
    bool sawEnd = false;
    while (pos + 12 <= file.size()) {
        const uint32_t len = (file[pos] << 24) | (file[pos + 1] << 16) | (file[pos + 2] << 8) | file[pos + 3];
        const std::string type(reinterpret_cast<char*>(&file[pos + 4]), 4);
        const uLong crc = crc32(crc32(0, &file[pos + 4], 4), &file[pos + 8], len);
        const uint32_t stored = (file[pos + 8 + len] << 24) | (file[pos + 9 + len] << 16) |
                                (file[pos + 10 + len] << 8) | file[pos + 11 + len];
        CHECK(crc == stored);
        if (type == "IDAT") idat.insert(idat.end(), &file[pos + 8], &file[pos + 8 + len]);
        if (type == "IEND") sawEnd = true;
        pos += 12 + len;
    }
    CHECK(sawEnd);
    std::vector<uint8_t> raw(static_cast<size_t>(h) * (w * 3 + 1));
    uLongf rawLen = raw.size();
    CHECK(uncompress(raw.data(), &rawLen, idat.data(), idat.size()) == Z_OK);
    std::vector<uint8_t> prev(w * 3, 0), cur(w * 3);
    int mismatches = 0;
    for (int y = 0; y < h; ++y) {
        const uint8_t* r = &raw[static_cast<size_t>(y) * (w * 3 + 1)];
        CHECK(r[0] == 4);
        for (int i = 0; i < w * 3; ++i) {
            const int a = i >= 3 ? cur[i - 3] : 0, b = prev[i], c = i >= 3 ? prev[i - 3] : 0;
            const int p = a + b - c, pa = std::abs(p - a), pb = std::abs(p - b), pc = std::abs(p - c);
            const int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
            cur[i] = static_cast<uint8_t>(r[i + 1] + pred);
            if (cur[i] != px[(y * w + i / 3) * 4 + i % 3]) ++mismatches;
        }
        prev = cur;
    }
    CHECK(mismatches == 0);
}

static std::vector<double> channelMeans(const std::vector<uint8_t>& px, int channels, int stride) {
    std::vector<double> m(3, 0.0);
    const size_t n = px.size() / stride;
    for (size_t i = 0; i < n; ++i)
        for (int c = 0; c < 3; ++c) m[c] += px[i * stride + c];
    for (double& v : m) v /= n;
    (void)channels;
    return m;
}

static std::vector<uint8_t> runWith(const std::vector<uint8_t>& px, int w, int h, TileRunner& r,
                                    const PostOptions& post) {
    Image img(px.data(), w, h, w * 4);
    MemorySink mem(w * r.scale(), 3);
    EngineOptions opt;
    opt.tile = 40;
    opt.pad = 8;
    opt.overlap = 12;
    opt.post = post;
    CHECK(upscale(img, r, opt, mem, nullptr, nullptr) == kOk);
    return mem.data;
}

// Detail = 0 must reproduce a plain bicubic upscale (tiling included).
static void testDetailZeroIsBicubic() {
    const int w = 90, h = 70, s = 4;
    auto px = makeImage(w, h, true);
    NearestRunner nn(s);
    PostOptions post;
    post.detail = 0.f;
    auto got = runWith(px, w, h, nn, post);

    std::vector<float> planar(3 * w * h), ref;
    for (int c = 0; c < 3; ++c)
        for (int i = 0; i < w * h; ++i) planar[c * w * h + i] = px[i * 4 + c] / 255.f;
    bicubicUpscale(planar, w, h, s, ref);
    int worst = 0;
    const int ow = w * s, oh = h * s;
    for (int y = 8; y < oh - 8; ++y)  // borders: engine reflect-pads, reference clamps
        for (int x = 8; x < ow - 8; ++x)
            for (int c = 0; c < 3; ++c) {
                const int want = static_cast<int>(std::lround(std::clamp(ref[(c * oh + y) * ow + x], 0.f, 1.f) * 255));
                worst = std::max(worst, std::abs(want - got[(y * ow + x) * 3 + c]));
            }
    if (worst > 1) std::fprintf(stderr, "  detail=0 worst=%d\n", worst);
    CHECK(worst <= 1);
}

// Colour fix must cancel a model that drifts colours.
static void testColorFix() {
    const int w = 96, h = 64, s = 2;
    auto px = makeImage(w, h, true);
    const auto want = channelMeans(px, 3, 4);
    ShiftRunner drift(s, 0.12f, 0.f, -0.08f);
    auto raw = runWith(px, w, h, drift, PostOptions{});
    PostOptions post;
    post.colorFix = true;
    ShiftRunner drift2(s, 0.12f, 0.f, -0.08f);
    auto fixed = runWith(px, w, h, drift2, post);
    const auto mr = channelMeans(raw, 3, 3), mf = channelMeans(fixed, 3, 3);
    CHECK(std::fabs(mr[0] - want[0]) > 20);   // drift is visible without the fix
    for (int c = 0; c < 3; ++c) {
        if (std::fabs(mf[c] - want[c]) > 1.5) std::fprintf(stderr, "  colorfix c%d %.2f vs %.2f\n", c, mf[c], want[c]);
        CHECK(std::fabs(mf[c] - want[c]) <= 1.5);
    }
}

// Luminance-only must discard a chroma-only shift from the model.
static void testLumaOnly() {
    const int w = 80, h = 60, s = 2;
    auto px = makeImage(w, h, true);
    const auto want = channelMeans(px, 3, 4);
    // +R, -B chosen so luma (0.299R + 0.587G + 0.114B) is unchanged.
    const float dr = 0.1f, db = -0.1f * 0.299f / 0.114f;
    ShiftRunner chroma(s, dr, 0.f, db);
    PostOptions post;
    post.lumaOnly = true;
    auto got = runWith(px, w, h, chroma, post);
    const auto m = channelMeans(got, 3, 3);
    for (int c = 0; c < 3; ++c) {
        if (std::fabs(m[c] - want[c]) > 1.5) std::fprintf(stderr, "  luma c%d %.2f vs %.2f\n", c, m[c], want[c]);
        CHECK(std::fabs(m[c] - want[c]) <= 1.5);
    }
}

// Chain: 1× pass then 2× pass then 4× with /2 downsample == nearest 4× of the input.
static void testChain(const char* dir) {
    const int w = 70, h = 50;
    auto px = makeImage(w, h, false);  // constant colour, so nearest chains are exact
    Image img(px.data(), w, h, w * 4);
    NearestRunner a(1), b(2), c(4);
    std::vector<PassSpec> passes(3);
    passes[0].runner = &a;
    passes[1].runner = &b;
    passes[2].runner = &c;
    passes[2].downsample = 2;
    for (auto& p : passes) p.opt.tile = 32;
    std::unique_ptr<MemorySink> mem;
    int lastDone = 0, lastTotal = 0;
    auto r = runChain(img, passes, dir, 0,
                      [&](int fw, int, int ch) { mem = std::make_unique<MemorySink>(fw, ch); return mem.get(); },
                      [&](int d, int t) { CHECK(d > lastDone); lastDone = d; lastTotal = t; }, nullptr);
    CHECK(r.status == kOk);
    CHECK(r.width == w * 4 && r.height == h * 4);
    CHECK(mem && mem->rows == h * 4);
    CHECK(lastDone == lastTotal && lastTotal > 0);
    int worst = 0;
    for (size_t i = 0; mem && i < mem->data.size(); i += 3)
        worst = std::max({worst, std::abs(mem->data[i] - 200), std::abs(mem->data[i + 1] - 90),
                          std::abs(mem->data[i + 2] - 30)});
    CHECK(worst == 0);
    // Intermediate files are cleaned up.
    CHECK(std::fopen((std::string(dir) + "/pass0.rgba").c_str(), "rb") == nullptr);
    CHECK(std::fopen((std::string(dir) + "/pass1.rgba").c_str(), "rb") == nullptr);

    // Cancel during the second pass.
    std::atomic<bool> cancel{false};
    NearestRunner a2(1), b2(2);
    std::vector<PassSpec> p2(2);
    p2[0].runner = &a2;
    p2[1].runner = &b2;
    for (auto& p : p2) p.opt.tile = 32;
    const int firstPassTiles = countTiles(w, h, p2[0].opt);
    auto r2 = runChain(img, p2, dir, 0,
                       [&](int fw, int, int ch) { mem = std::make_unique<MemorySink>(fw, ch); return mem.get(); },
                       [&](int d, int) { if (d == firstPassTiles + 1) cancel = true; }, &cancel);
    CHECK(r2.status == kCancelled);
    CHECK(std::fopen((std::string(dir) + "/pass0.rgba").c_str(), "rb") == nullptr);
}

static void testFitTile() {
    CHECK(fitTile(192, 16000, 4, 0) == 192);
    const int t = fitTile(192, 16000, 4, 64LL << 20);
    CHECK(t < 192 && t >= 32);
    CHECK(16000LL * t * 4 * 12 <= (64LL << 20) || t == 32);
}

int main(int argc, char** argv) {
    const char* tmp = argc > 1 ? argv[1] : ".";
    testPlanAxis();
    testReflect();
    testSeamless(1, 1, 4, false, 0.f, 0);
    testSeamless(37, 23, 4, true, 0.f, 1);
    testSeamless(130, 97, 4, false, 0.f, 0);   // constant colour: exact
    testSeamless(130, 97, 4, true, 0.f, 1);    // gradient: < 1/255
    testSeamless(301, 157, 2, true, 0.f, 1);
    testFeatherIsSmooth();
    testOrientation();
    testAlphaAndDownsample();
    testCancel();
    testPngRoundTrip(tmp);
    testDetailZeroIsBicubic();
    testColorFix();
    testLumaOnly();
    testChain(tmp);
    testFitTile();
    if (failures) {
        std::fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all engine tests passed\n");
    return 0;
}
