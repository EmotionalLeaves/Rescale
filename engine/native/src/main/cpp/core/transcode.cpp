#include "transcode.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>

#include "../third_party/jpge/jpge.h"

namespace rescale {

namespace {
uint32_t be32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }

inline uint8_t paeth(int a, int b, int c) {
    const int p = a + b - c, pa = std::abs(p - a), pb = std::abs(p - b), pc = std::abs(p - c);
    if (pa <= pb && pa <= pc) return static_cast<uint8_t>(a);
    return static_cast<uint8_t>(pb <= pc ? b : c);
}
}  // namespace

PngReader::PngReader(const std::string& path) {
    fp_ = std::fopen(path.c_str(), "rb");
    if (!fp_) return;
    uint8_t sig[8];
    static const uint8_t want[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    if (std::fread(sig, 1, 8, fp_) != 8 || std::memcmp(sig, want, 8) != 0) return;
    uint8_t hdr[8], ihdr[13], crc[4];
    if (std::fread(hdr, 1, 8, fp_) != 8 || std::memcmp(hdr + 4, "IHDR", 4) != 0 || be32(hdr) != 13) return;
    if (std::fread(ihdr, 1, 13, fp_) != 13 || std::fread(crc, 1, 4, fp_) != 4) return;
    w_ = static_cast<int>(be32(ihdr));
    h_ = static_cast<int>(be32(ihdr + 4));
    if (ihdr[8] != 8 || ihdr[12] != 0) return;  // 8-bit, non-interlaced only
    c_ = ihdr[9] == 2 ? 3 : ihdr[9] == 6 ? 4 : ihdr[9] == 0 ? 1 : 0;
    if (!c_ || w_ <= 0 || h_ <= 0) return;
    if (inflateInit(&zs_) != Z_OK) return;
    const size_t stride = static_cast<size_t>(w_) * c_;
    raw_.resize(stride + 1);
    prev_.assign(stride, 0);
    cur_.resize(stride);
    in_.resize(1 << 16);
    ok_ = true;
}

PngReader::~PngReader() {
    if (ok_) inflateEnd(&zs_);
    if (fp_) std::fclose(fp_);
}

bool PngReader::fill() {
    // Inflate until raw_ holds one full filtered row.
    while (rawHave_ < raw_.size()) {
        if (zs_.avail_in == 0) {
            while (idatLeft_ == 0) {  // advance to the next IDAT chunk
                uint8_t hdr[8], crc[4];
                if (std::fread(hdr, 1, 8, fp_) != 8) return false;
                const uint32_t len = be32(hdr);
                if (std::memcmp(hdr + 4, "IDAT", 4) == 0) {
                    idatLeft_ = len;
                    if (len == 0 && std::fread(crc, 1, 4, fp_) != 4) return false;
                } else {
                    if (std::memcmp(hdr + 4, "IEND", 4) == 0) return false;
                    if (std::fseek(fp_, static_cast<long>(len) + 4, SEEK_CUR) != 0) return false;
                }
            }
            const size_t n = std::fread(in_.data(), 1, std::min<size_t>(in_.size(), idatLeft_), fp_);
            if (n == 0) return false;
            idatLeft_ -= static_cast<uint32_t>(n);
            if (idatLeft_ == 0) {
                uint8_t crc[4];
                if (std::fread(crc, 1, 4, fp_) != 4) return false;
            }
            zs_.next_in = in_.data();
            zs_.avail_in = static_cast<uInt>(n);
        }
        zs_.next_out = raw_.data() + rawHave_;
        zs_.avail_out = static_cast<uInt>(raw_.size() - rawHave_);
        const int r = inflate(&zs_, Z_NO_FLUSH);
        if (r != Z_OK && r != Z_STREAM_END && r != Z_BUF_ERROR) return false;
        rawHave_ = raw_.size() - zs_.avail_out;
        if (r == Z_STREAM_END && rawHave_ < raw_.size()) return false;
    }
    return true;
}

const uint8_t* PngReader::next() {
    if (!ok_ || row_ >= h_) return nullptr;
    rawHave_ = 0;
    if (!fill()) {
        ok_ = false;
        return nullptr;
    }
    const size_t stride = cur_.size();
    const int bpp = c_;
    const uint8_t f = raw_[0];
    const uint8_t* r = raw_.data() + 1;
    for (size_t i = 0; i < stride; ++i) {
        const int a = i >= static_cast<size_t>(bpp) ? cur_[i - bpp] : 0;
        const int b = prev_[i];
        const int c = i >= static_cast<size_t>(bpp) ? prev_[i - bpp] : 0;
        int pred = 0;
        switch (f) {
            case 0: pred = 0; break;
            case 1: pred = a; break;
            case 2: pred = b; break;
            case 3: pred = (a + b) / 2; break;
            case 4: pred = paeth(a, b, c); break;
            default: ok_ = false; return nullptr;
        }
        cur_[i] = static_cast<uint8_t>(r[i] + pred);
    }
    prev_ = cur_;
    ++row_;
    return cur_.data();
}

namespace {
class FileStream : public jpge::output_stream {
public:
    explicit FileStream(const std::string& p) { fp_ = std::fopen(p.c_str(), "wb"); }
    ~FileStream() override {
        if (fp_) std::fclose(fp_);
    }
    bool ok() const { return fp_ != nullptr; }
    bool put_buf(const void* buf, int len) override { return std::fwrite(buf, 1, len, fp_) == static_cast<size_t>(len); }
    bool close() {
        const bool r = std::fclose(fp_) == 0;
        fp_ = nullptr;
        return r;
    }

private:
    FILE* fp_ = nullptr;
};
}  // namespace

bool pngToJpeg(const std::string& pngPath, const std::string& jpegPath, int quality) {
    PngReader png(pngPath);
    if (!png.ok() || png.width() > 65535 || png.height() > 65535) return false;
    FileStream out(jpegPath);
    if (!out.ok()) return false;
    jpge::params p;
    p.m_quality = std::max(1, std::min(100, quality));
    // Keep full chroma resolution at high quality; 4:2:0 below.
    p.m_subsampling = quality >= 90 ? jpge::H1V1 : jpge::H2V2;
    jpge::jpeg_encoder enc;
    if (!enc.init(&out, png.width(), png.height(), 3, p) || enc.get_total_passes() != 1) return false;
    std::vector<uint8_t> rgb(static_cast<size_t>(png.width()) * 3);
    const int c = png.channels();
    for (int y = 0; y < png.height(); ++y) {
        const uint8_t* row = png.next();
        if (!row) return false;
        for (int x = 0; x < png.width(); ++x) {
            const uint8_t* s = row + static_cast<size_t>(x) * c;
            uint8_t* d = &rgb[static_cast<size_t>(x) * 3];
            if (c == 1) {
                d[0] = d[1] = d[2] = s[0];
            } else if (c == 4) {  // composite over white
                const int a = s[3];
                for (int k = 0; k < 3; ++k) d[k] = static_cast<uint8_t>((s[k] * a + 255 * (255 - a) + 127) / 255);
            } else {
                d[0] = s[0]; d[1] = s[1]; d[2] = s[2];
            }
        }
        if (!enc.process_scanline(rgb.data())) return false;
    }
    if (!enc.process_scanline(nullptr)) return false;
    enc.deinit();
    return out.close();
}

}  // namespace rescale
