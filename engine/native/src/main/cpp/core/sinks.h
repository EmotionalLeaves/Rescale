// Row sinks: the engine produces finished output rows top to bottom and pushes them
// through a chain of sinks (downsample -> tee -> PNG writer / preview). Nothing ever
// holds the full output image.
#pragma once

#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include <zlib.h>

namespace rescale {

class RowSink {
public:
    virtual ~RowSink() = default;
    // Row of width*channels bytes. Returns false on error (e.g. disk full).
    virtual bool push(const uint8_t* row) = 0;
    virtual bool finish() = 0;
};

// Streaming PNG encoder (8-bit RGB or RGBA, Paeth filter, zlib deflate).
class PngWriter : public RowSink {
public:
    PngWriter(const std::string& path, int width, int height, int channels, int level = 6);
    ~PngWriter() override;
    bool ok() const { return fp_ != nullptr && !failed_; }
    bool push(const uint8_t* row) override;
    bool finish() override;

private:
    bool writeChunk(const char type[4], const uint8_t* data, size_t len);
    bool flushDeflate(int flush);

    FILE* fp_ = nullptr;
    int width_, height_, channels_;
    int rows_ = 0;
    bool failed_ = false;
    bool finished_ = false;
    z_stream zs_{};
    std::vector<uint8_t> prev_, filtered_, out_;
};

// Area (box) downsample by integer factor k. Edge blocks that are only partly inside the
// image are averaged over the pixels that exist, so output is ceil(w/k) x ceil(h/k).
class BoxDownsample : public RowSink {
public:
    BoxDownsample(int width, int height, int channels, int k, RowSink* next);
    bool push(const uint8_t* row) override;
    bool finish() override;
    int outWidth() const { return ow_; }
    int outHeight() const { return oh_; }

private:
    bool emit();
    int w_, h_, c_, k_, ow_, oh_;
    int rowsInBlock_ = 0, rowsSeen_ = 0;
    std::vector<uint32_t> acc_;
    std::vector<uint8_t> out_;
    RowSink* next_;
};

class Tee : public RowSink {
public:
    Tee(RowSink* a, RowSink* b) : a_(a), b_(b) {}
    bool push(const uint8_t* row) override { return a_->push(row) && (!b_ || b_->push(row)); }
    bool finish() override { return a_->finish() && (!b_ || b_->finish()); }

private:
    RowSink* a_;
    RowSink* b_;
};

// Collects rows in memory (tests, small previews).
class MemorySink : public RowSink {
public:
    MemorySink(int width, int channels) : w_(width), c_(channels) {}
    bool push(const uint8_t* row) override {
        data.insert(data.end(), row, row + static_cast<size_t>(w_) * c_);
        ++rows;
        return true;
    }
    bool finish() override { return true; }
    std::vector<uint8_t> data;
    int rows = 0;

private:
    int w_, c_;
};

}  // namespace rescale
