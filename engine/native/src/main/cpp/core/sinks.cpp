#include "sinks.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace rescale {

namespace {
void be32(uint8_t* p, uint32_t v) {
    p[0] = v >> 24;
    p[1] = v >> 16;
    p[2] = v >> 8;
    p[3] = v;
}

inline uint8_t paeth(int a, int b, int c) {
    const int p = a + b - c;
    const int pa = std::abs(p - a), pb = std::abs(p - b), pc = std::abs(p - c);
    if (pa <= pb && pa <= pc) return static_cast<uint8_t>(a);
    if (pb <= pc) return static_cast<uint8_t>(b);
    return static_cast<uint8_t>(c);
}
}  // namespace

PngWriter::PngWriter(const std::string& path, int width, int height, int channels, int level)
    : width_(width), height_(height), channels_(channels) {
    fp_ = std::fopen(path.c_str(), "wb");
    if (!fp_) return;
    static const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    failed_ |= std::fwrite(sig, 1, 8, fp_) != 8;
    uint8_t ihdr[13];
    be32(ihdr, width);
    be32(ihdr + 4, height);
    ihdr[8] = 8;                               // bit depth
    ihdr[9] = channels == 4 ? 6 : 2;           // RGBA : RGB
    ihdr[10] = ihdr[11] = ihdr[12] = 0;        // deflate, adaptive filtering, no interlace
    failed_ |= !writeChunk("IHDR", ihdr, sizeof ihdr);
    failed_ |= deflateInit(&zs_, level) != Z_OK;
    const size_t stride = static_cast<size_t>(width) * channels;
    prev_.assign(stride, 0);
    filtered_.resize(stride + 1);
    out_.resize(1 << 18);
}

PngWriter::~PngWriter() {
    if (fp_) {
        deflateEnd(&zs_);
        std::fclose(fp_);
    }
}

bool PngWriter::writeChunk(const char type[4], const uint8_t* data, size_t len) {
    uint8_t hdr[8];
    be32(hdr, static_cast<uint32_t>(len));
    std::memcpy(hdr + 4, type, 4);
    uLong crc = crc32(0L, reinterpret_cast<const Bytef*>(type), 4);
    if (len) crc = crc32(crc, data, static_cast<uInt>(len));
    uint8_t crcb[4];
    be32(crcb, static_cast<uint32_t>(crc));
    return std::fwrite(hdr, 1, 8, fp_) == 8 && (len == 0 || std::fwrite(data, 1, len, fp_) == len) &&
           std::fwrite(crcb, 1, 4, fp_) == 4;
}

bool PngWriter::flushDeflate(int flush) {
    int ret;
    do {
        zs_.next_out = out_.data();
        zs_.avail_out = static_cast<uInt>(out_.size());
        ret = deflate(&zs_, flush);
        if (ret == Z_STREAM_ERROR) return false;
        const size_t have = out_.size() - zs_.avail_out;
        if (have && !writeChunk("IDAT", out_.data(), have)) return false;
    } while (zs_.avail_out == 0 || (flush == Z_FINISH && ret != Z_STREAM_END));
    return true;
}

bool PngWriter::push(const uint8_t* row) {
    if (!ok() || rows_ >= height_) return false;
    const size_t stride = static_cast<size_t>(width_) * channels_;
    const int bpp = channels_;
    filtered_[0] = 4;  // Paeth
    for (size_t i = 0; i < stride; ++i) {
        const int a = i >= static_cast<size_t>(bpp) ? row[i - bpp] : 0;
        const int b = prev_[i];
        const int c = i >= static_cast<size_t>(bpp) ? prev_[i - bpp] : 0;
        filtered_[i + 1] = static_cast<uint8_t>(row[i] - paeth(a, b, c));
    }
    std::memcpy(prev_.data(), row, stride);
    zs_.next_in = filtered_.data();
    zs_.avail_in = static_cast<uInt>(filtered_.size());
    if (!flushDeflate(Z_NO_FLUSH)) {
        failed_ = true;
        return false;
    }
    ++rows_;
    return true;
}

bool PngWriter::finish() {
    if (finished_) return ok();
    finished_ = true;
    if (!ok() || rows_ != height_) {
        failed_ = true;
        return false;
    }
    zs_.next_in = nullptr;
    zs_.avail_in = 0;
    failed_ |= !flushDeflate(Z_FINISH);
    failed_ |= !writeChunk("IEND", nullptr, 0);
    failed_ |= std::fflush(fp_) != 0;
    return ok();
}

BoxDownsample::BoxDownsample(int width, int height, int channels, int k, RowSink* next)
    : w_(width), h_(height), c_(channels), k_(k), next_(next) {
    ow_ = (width + k - 1) / k;
    oh_ = (height + k - 1) / k;
    acc_.assign(static_cast<size_t>(ow_) * c_, 0);
    out_.resize(static_cast<size_t>(ow_) * c_);
}

bool BoxDownsample::push(const uint8_t* row) {
    for (int x = 0; x < w_; ++x) {
        uint32_t* a = &acc_[static_cast<size_t>(x / k_) * c_];
        const uint8_t* p = row + static_cast<size_t>(x) * c_;
        for (int ch = 0; ch < c_; ++ch) a[ch] += p[ch];
    }
    ++rowsInBlock_;
    ++rowsSeen_;
    if (rowsInBlock_ == k_ || rowsSeen_ == h_) return emit();
    return true;
}

bool BoxDownsample::emit() {
    for (int ox = 0; ox < ow_; ++ox) {
        const int cols = std::min(k_, w_ - ox * k_);
        const uint32_t n = static_cast<uint32_t>(cols * rowsInBlock_);
        for (int ch = 0; ch < c_; ++ch) {
            const size_t i = static_cast<size_t>(ox) * c_ + ch;
            out_[i] = static_cast<uint8_t>((acc_[i] + n / 2) / n);
        }
    }
    std::fill(acc_.begin(), acc_.end(), 0);
    rowsInBlock_ = 0;
    return next_->push(out_.data());
}

bool BoxDownsample::finish() {
    if (rowsInBlock_ > 0 && !emit()) return false;
    return next_->finish();
}

}  // namespace rescale
