// Streaming PNG reader (8-bit RGB/RGBA, non-interlaced: what PngWriter produces) and a
// PNG → JPEG transcoder that never holds more than a few rows in memory.
#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include <zlib.h>

namespace rescale {

class PngReader {
public:
    explicit PngReader(const std::string& path);
    ~PngReader();
    bool ok() const { return ok_; }
    int width() const { return w_; }
    int height() const { return h_; }
    int channels() const { return c_; }
    // Next unfiltered row (width*channels bytes), or nullptr at the end / on error.
    const uint8_t* next();

private:
    bool fill();  // inflate more data into the row buffer
    FILE* fp_ = nullptr;
    bool ok_ = false, streamEnd_ = false;
    int w_ = 0, h_ = 0, c_ = 0, row_ = 0;
    uint32_t idatLeft_ = 0;
    z_stream zs_{};
    std::vector<uint8_t> in_, raw_, prev_, cur_;
    size_t rawHave_ = 0;
};

// Transcodes a PNG to baseline JPEG (alpha is composited over white). quality 1..100.
bool pngToJpeg(const std::string& pngPath, const std::string& jpegPath, int quality);

}  // namespace rescale
