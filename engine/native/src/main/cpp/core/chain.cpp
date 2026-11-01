#include "chain.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>

#include "tiler.h"

namespace rescale {

namespace {

// Writes rows as RGBA8 (alpha 255 when the source rows are RGB).
class RawRgbaSink : public RowSink {
public:
    RawRgbaSink(const std::string& path, int width, int channels) : w_(width), c_(channels) {
        fp_ = std::fopen(path.c_str(), "wb");
        buf_.resize(static_cast<size_t>(width) * 4);
    }
    ~RawRgbaSink() override {
        if (fp_) std::fclose(fp_);
    }
    bool ok() const { return fp_ != nullptr; }
    bool push(const uint8_t* row) override {
        if (!fp_) return false;
        const uint8_t* src = row;
        if (c_ == 3) {
            for (int x = 0; x < w_; ++x) {
                std::memcpy(&buf_[x * 4], row + x * 3, 3);
                buf_[x * 4 + 3] = 255;
            }
            src = buf_.data();
        }
        return std::fwrite(src, 4, w_, fp_) == static_cast<size_t>(w_);
    }
    bool finish() override {
        if (!fp_) return false;
        const bool ok = std::fflush(fp_) == 0 && std::fclose(fp_) == 0;
        fp_ = nullptr;
        return ok;
    }

private:
    FILE* fp_ = nullptr;
    int w_, c_;
    std::vector<uint8_t> buf_;
};

// Read-only mapping of a raw RGBA file; unlinks the file when done.
struct Mapped {
    void* addr = MAP_FAILED;
    size_t size = 0;
    std::string path;
    bool open(const std::string& p, size_t bytes) {
        path = p;
        size = bytes;
        const int fd = ::open(p.c_str(), O_RDONLY);
        if (fd < 0) return false;
        addr = ::mmap(nullptr, bytes, PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        return addr != MAP_FAILED;
    }
    ~Mapped() {
        if (addr != MAP_FAILED) ::munmap(addr, size);
        if (!path.empty()) ::unlink(path.c_str());
    }
};

}  // namespace

int fitTile(int tile, int outWidth, int scale, long long maxBandBytes) {
    tile = std::max(32, tile);
    if (maxBandBytes <= 0) return tile;
    while (tile > 32 && static_cast<long long>(outWidth) * tile * scale * 12 > maxBandBytes) tile -= 16;
    return tile;
}

int countTiles(int w, int h, const EngineOptions& opt) {
    return static_cast<int>(planAxis(w, opt.tile, opt.overlap).starts.size() *
                            planAxis(h, opt.tile, opt.overlap).starts.size());
}

ChainResult runChain(const Image& input, std::vector<PassSpec>& passes, const std::string& tmpDir,
                     long long maxBandBytes, const FinalSinkFactory& makeFinal, const ProgressFn& progress,
                     const std::atomic<bool>* cancel) {
    ChainResult res;
    if (passes.empty()) {
        res.status = kModelError;
        return res;
    }

    // Work out sizes and tile counts up front so progress covers the whole chain.
    struct Geo { int inW, inH, outW, outH; };
    std::vector<Geo> geo;
    int w = input.width(), h = input.height(), total = 0;
    for (auto& p : passes) {
        const int s = p.runner->scale();
        p.opt.tile = fitTile(p.opt.tile, w * s, s, maxBandBytes);
        total += countTiles(w, h, p.opt);
        const int ow = (w * s + p.downsample - 1) / p.downsample;
        const int oh = (h * s + p.downsample - 1) / p.downsample;
        geo.push_back({w, h, ow, oh});
        w = ow;
        h = oh;
    }
    res.width = w;
    res.height = h;

    const bool alpha = input.hasAlpha();
    const int channels = alpha ? 4 : 3;
    int base = 0;
    std::unique_ptr<Mapped> current;  // mapped output of the previous pass
    std::unique_ptr<Image> currentImg;

    for (size_t i = 0; i < passes.size(); ++i) {
        PassSpec& p = passes[i];
        const Image& src = i == 0 ? input : *currentImg;
        const int s = p.runner->scale();
        const bool last = i + 1 == passes.size();
        const Geo& g = geo[i];

        std::unique_ptr<RawRgbaSink> raw;
        RowSink* out = nullptr;
        const std::string rawPath = tmpDir + "/pass" + std::to_string(i) + ".rgba";
        if (last) {
            out = makeFinal(g.outW, g.outH, channels);
            if (!out) {
                res.status = kWriteError;
                return res;
            }
        } else {
            raw = std::make_unique<RawRgbaSink>(rawPath, g.outW, channels);
            if (!raw->ok()) {
                res.status = kWriteError;
                return res;
            }
            out = raw.get();
        }
        std::unique_ptr<BoxDownsample> down;
        RowSink* sink = out;
        if (p.downsample > 1) {
            down = std::make_unique<BoxDownsample>(g.inW * s, g.inH * s, channels, p.downsample, out);
            sink = down.get();
        }

        const int offset = base;
        const Status st = upscale(src, *p.runner, p.opt, *sink,
                                  [&](int done, int) { if (progress) progress(offset + done, total); }, cancel);
        base += countTiles(g.inW, g.inH, p.opt);
        if (st != kOk) {
            if (raw) {
                raw.reset();
                ::unlink(rawPath.c_str());
            }
            res.status = st;
            return res;
        }
        if (!last) {
            raw.reset();
            auto next = std::make_unique<Mapped>();
            if (!next->open(rawPath, static_cast<size_t>(g.outW) * g.outH * 4)) {
                res.status = kWriteError;
                return res;
            }
            currentImg = std::make_unique<Image>(static_cast<const uint8_t*>(next->addr), g.outW, g.outH, g.outW * 4, 1);
            current = std::move(next);  // frees (and unlinks) the pass before
        }
    }
    res.status = kOk;
    return res;
}

}  // namespace rescale
