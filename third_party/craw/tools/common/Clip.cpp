// SPDX-License-Identifier: Apache-2.0
#include "Clip.h"

#include <thread>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace craw::tools {
namespace {

// ---- little-endian TIFF/DNG builder ----------------------------------------

enum TiffType : uint16_t { BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, UNDEFINED = 7, SRATIONAL = 10 };

struct Entry {
    uint16_t tag, type;
    uint32_t count;
    std::vector<uint8_t> data;
};

template <typename T>
void le(std::vector<uint8_t>& o, T v) {
    for (size_t i = 0; i < sizeof(T); ++i) o.push_back(static_cast<uint8_t>(uint64_t(v) >> (8 * i)));
}
template <typename T>
void be(std::vector<uint8_t>& o, T v) {
    for (size_t i = sizeof(T); i-- > 0;) o.push_back(static_cast<uint8_t>(uint64_t(v) >> (8 * i)));
}
void beF32(std::vector<uint8_t>& o, float f) { uint32_t u; std::memcpy(&u, &f, 4); be(o, u); }
void beF64(std::vector<uint8_t>& o, double d) { uint64_t u; std::memcpy(&u, &d, 8); be(o, u); }

class Ifd {
public:
    void shorts(uint16_t tag, std::initializer_list<uint16_t> v) { Entry e{tag, SHORT, uint32_t(v.size()), {}}; for (auto x : v) le(e.data, x); add(e); }
    void longs(uint16_t tag, const std::vector<uint32_t>& v) { Entry e{tag, LONG, uint32_t(v.size()), {}}; for (auto x : v) le(e.data, x); add(e); }
    void bytes(uint16_t tag, std::vector<uint8_t> v, uint16_t type = BYTE) { Entry e{tag, type, uint32_t(v.size()), std::move(v)}; add(e); }
    void raw(uint16_t tag, uint16_t type, uint32_t count, std::vector<uint8_t> v) { Entry e{tag, type, count, std::move(v)}; add(e); }
    void ascii(uint16_t tag, const std::string& s) { std::vector<uint8_t> v(s.begin(), s.end()); v.push_back(0); bytes(tag, v, ASCII); }
    void rationals(uint16_t tag, const std::vector<double>& v, bool sign) {
        Entry e{tag, uint16_t(sign ? SRATIONAL : RATIONAL), uint32_t(v.size()), {}};
        for (double x : v) {
            const int32_t den = 1000000;
            le(e.data, static_cast<int32_t>(std::llround(x * den)));
            le(e.data, den);
        }
        add(e);
    }
    // Serializes at `offset`; `fixups` receives (position of a LONG value, entry tag) for strips.
    std::vector<uint8_t> build(uint32_t offset, uint32_t stripOffset) {
        std::sort(entries_.begin(), entries_.end(), [](const Entry& a, const Entry& b) { return a.tag < b.tag; });
        for (auto& e : entries_)
            if (e.tag == 273) { e.data.clear(); le(e.data, stripOffset); }
        std::vector<uint8_t> out;
        le<uint16_t>(out, static_cast<uint16_t>(entries_.size()));
        uint32_t extra = offset + 2 + 12 * uint32_t(entries_.size()) + 4;
        std::vector<uint8_t> blob;
        for (auto& e : entries_) {
            le(out, e.tag);
            le(out, e.type);
            le(out, e.count);
            if (e.data.size() <= 4) {
                auto d = e.data;
                d.resize(4, 0);
                out.insert(out.end(), d.begin(), d.end());
            } else {
                le(out, extra + uint32_t(blob.size()));
                blob.insert(blob.end(), e.data.begin(), e.data.end());
                if (blob.size() & 1) blob.push_back(0);
            }
        }
        le<uint32_t>(out, 0);  // no next IFD
        out.insert(out.end(), blob.begin(), blob.end());
        return out;
    }

private:
    void add(Entry& e) { entries_.push_back(std::move(e)); }
    std::vector<Entry> entries_;
};

std::vector<double> numbers(const json::Value& v) {
    std::vector<double> out;
    if (!v.isArray()) return out;
    for (const auto& x : v.a) {
        if (x.isNumber()) out.push_back(x.n);
        else if (x.isArray() && x.size() == 2 && x[1].num() != 0) out.push_back(x[0].num() / x[1].num());  // DNG rational pair
    }
    return out;
}

int cfaFromName(const std::string& s) {
    std::string l;
    for (char c : s) l += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    if (l == "grbg") return 1;
    if (l == "gbrg") return 2;
    if (l == "bggr") return 3;
    return 0;
}

// Cell position (py * 2 + px) of R, Gr (green in the red row), Gb, B.
std::array<int, 4> colourPositions(int cfa) {
    static const int r[4] = {0, 1, 2, 3};    // R position per CFA
    static const int b[4] = {3, 2, 1, 0};
    const int rp = r[cfa], bp = b[cfa];
    const int gr = (rp & 2) | (1 - (rp & 1));  // other column, red row
    const int gb = (bp & 2) | (1 - (bp & 1));
    return {rp, gr, gb, bp};
}

}  // namespace

std::string timecode(int64_t index, int fps) {
    fps = std::max(fps, 1);
    const int64_t f = index % fps, s = index / fps;
    char buf[16];
    std::snprintf(buf, sizeof buf, "%02lld:%02lld:%02lld:%02lld", (long long)(s / 3600 % 24), (long long)(s / 60 % 60),
                  (long long)(s % 60), (long long)f);
    return buf;
}

Clip::Clip(const std::string& path) : reader_(path) { init(); }

#ifndef _WIN32
Clip::Clip(int fd, const std::string& name) : reader_(fd, name) { init(); }
#endif

void Clip::init() {
    const json::Value meta = json::parseOrEmpty(reader_.metaJson());
    const json::Value& sensor = meta["sensor"];
    const json::Value& camera = meta["camera"];
    const json::Value& video = meta["video"];
    const json::Value& extra = meta["extraData"];

    // Camera identity.
    info_.make = camera["make"].str(info_.make);
    info_.model = camera["model"].str(info_.model);
    const std::string unique = meta["uniqueCameraModel"].str(meta["UniqueCameraModel"].str());
    if (!unique.empty()) {
        info_.uniqueModel = unique;
        const auto sp = unique.find(' ');
        if (info_.make == "Unknown") info_.make = unique.substr(0, sp);
        if (info_.model == "Unknown") info_.model = sp == std::string::npos ? unique : unique.substr(sp + 1);
    } else if (info_.make != "Unknown") {
        info_.uniqueModel = info_.make + " " + info_.model;
    }

    // Sensor.
    info_.cfa = cfaFromName(sensor["cfa"].str(meta["sensorArrangment"].str("rggb")));
    auto black = numbers(sensor["black_level"].isArray() ? sensor["black_level"] : meta["blackLevel"]);
    if (black.size() == 1) black.assign(4, black[0]);
    if (black.size() == 4) std::copy(black.begin(), black.end(), info_.black.begin());
    info_.white = sensor["white_level"].num(meta["whiteLevel"].num(info_.white));
    auto pick = [&](const char* spec, const char* flat) {
        auto v = numbers(sensor[spec]);
        return v.size() == 9 ? v : numbers(meta[flat]);
    };
    info_.colorMatrix1 = pick("color_matrix1", "colorMatrix1");
    info_.colorMatrix2 = pick("color_matrix2", "colorMatrix2");
    info_.forwardMatrix1 = pick("forward_matrix1", "forwardMatrix1");
    info_.forwardMatrix2 = pick("forward_matrix2", "forwardMatrix2");
    info_.calibration1 = pick("calibration1", "calibrationMatrix1");
    info_.calibration2 = pick("calibration2", "calibrationMatrix2");
    info_.illuminant1 = int(sensor["illuminant1"].num(meta["referenceIlluminant1"].num(21)));
    info_.illuminant2 = int(sensor["illuminant2"].num(meta["referenceIlluminant2"].num(17)));
    // Capture orientation only: sensorOrientation describes the sensor mount,
    // and landscape video frames are already upright.
    info_.orientationDeg = int(meta["orientation"].num(meta["video"]["orientation"].num(0)));

    // Timing.
    if (video["fps_num"].isNumber()) {
        info_.fpsNum = video["fps_num"].num(30);
        info_.fpsDen = std::max(1.0, video["fps_den"].num(1));
    } else {
        info_.fpsNum = extra["frameRate"].num(meta["fps"].num(30));
        info_.fpsDen = 1;
    }

    // Chunks: lens shading maps, audio, first-frame geometry.
    uint64_t wavData = 0;
    for (const auto& c : reader_.chunks()) {
        // Only the small LSHD/AUDI chunks are read (and CRC-checked) here;
        // FRAM chunks are checked by Reader::frame() when decoded.
        const bool lshd = std::memcmp(c.fourcc.data(), "LSHD", 4) == 0, audi = std::memcmp(c.fourcc.data(), "AUDI", 4) == 0;
        if ((!lshd && !audi) || !reader_.crcOk(c)) continue;
        const auto p = reader_.payload(c);
        const auto* b = reinterpret_cast<const uint8_t*>(p.data());
        if (std::memcmp(c.fourcc.data(), "LSHD", 4) == 0 && p.size() >= 16) {
            Shading sh;
            uint32_t id;
            std::memcpy(&id, b + 8, 4);
            std::memcpy(&sh.columns, b + 12, 2);
            std::memcpy(&sh.rows, b + 14, 2);
            const size_t n = size_t{sh.columns} * sh.rows * 4;
            if (n == 0 || p.size() != 16 + 4 * n) continue;
            sh.gains.resize(n);
            std::memcpy(sh.gains.data(), b + 16, 4 * n);
            shading_[id] = std::move(sh);
        } else if (std::memcmp(c.fourcc.data(), "AUDI", 4) == 0 && p.size() >= 20) {
            const int fmt = b[8], ch = b[9];
            uint32_t rate, frames;
            std::memcpy(&rate, b + 12, 4);
            std::memcpy(&frames, b + 16, 4);
            const size_t bps = fmt == 1 ? 2 : fmt == 3 ? 3 : 4;
            const size_t bytes = size_t{frames} * ch * bps;
            if (fmt < 1 || fmt > 3 || ch == 0 || rate == 0 || bytes != p.size() - 20) continue;
            if (audio_.format == 0) { audio_.format = fmt; audio_.channels = ch; audio_.sampleRate = rate; }
            if (fmt != audio_.format || ch != audio_.channels || rate != audio_.sampleRate) continue;  // keep one stream
            // WAV is always integer PCM (NLEs handle float WAV poorly): F32 -> 24-bit.
            const size_t outBytes = fmt == 2 ? bytes / 4 * 3 : bytes;
            audioParts_.push_back({wavData, b + 20, outBytes});
            wavData += outBytes;
            audio_.frames += frames;
        }
    }
    if (reader_.frameCount() > 0) {
        const auto rec = reader_.frame(0);
        const auto fi = probeFrame(rec.payload, rec.payloadSize);
        codedBitDepth_ = fi.bitDepth;
        width_ = fi.width;
        height_ = fi.height;
        rgb_ = fi.cfa == Cfa::RGB;
    }
    if (audio_.format != 0) {
        const uint16_t bits = audio_.format == 1 ? 16 : 24;
        const uint16_t align = static_cast<uint16_t>(audio_.channels * bits / 8);
        auto& h = wavHeader_;
        auto tag = [&](const char* t) { h.insert(h.end(), t, t + 4); };
        // Broadcast WAV: 'bext' with TimeReference 0 so the audio lines up with
        // the CinemaDNG timecode (00:00:00:00) in an NLE.
        std::vector<uint8_t> bext(602, 0);
        const char desc[] = "Cinematic RAW Studio";
        std::memcpy(bext.data(), desc, sizeof desc - 1);          // Description[256]
        std::memcpy(bext.data() + 256, desc, sizeof desc - 1);    // Originator[32]
        bext[346] = 1;                                            // Version (after TimeReference u64 at 338)
        const uint32_t riff = static_cast<uint32_t>(std::min<uint64_t>(4 + 8 + 16 + 8 + bext.size() + 8 + wavData + (wavData & 1), 0xFFFFFFFFu));
        tag("RIFF"); le<uint32_t>(h, riff); tag("WAVE");
        tag("fmt "); le<uint32_t>(h, 16); le<uint16_t>(h, 1); le<uint16_t>(h, static_cast<uint16_t>(audio_.channels));
        le<uint32_t>(h, audio_.sampleRate); le<uint32_t>(h, audio_.sampleRate * align); le<uint16_t>(h, align); le<uint16_t>(h, bits);
        tag("bext"); le<uint32_t>(h, static_cast<uint32_t>(bext.size())); h.insert(h.end(), bext.begin(), bext.end());
        tag("data"); le<uint32_t>(h, static_cast<uint32_t>(std::min<uint64_t>(wavData, 0xFFFFFFFFu)));
    }
}

uint64_t Clip::wavSize() const {
    if (audio_.format == 0) return 0;
    uint64_t n = wavHeader_.size();
    for (const auto& p : audioParts_) n += p.size;
    return n + ((n - wavHeader_.size()) & 1);
}

size_t Clip::readWav(uint64_t offset, uint8_t* dst, size_t len) const {
    size_t done = 0;
    while (done < len) {
        const uint64_t at = offset + done;
        if (at < wavHeader_.size()) {
            const size_t n = std::min<size_t>(len - done, wavHeader_.size() - at);
            std::memcpy(dst + done, wavHeader_.data() + at, n);
            done += n;
            continue;
        }
        const uint64_t dataAt = at - wavHeader_.size();
        auto it = std::upper_bound(audioParts_.begin(), audioParts_.end(), dataAt,
                                   [](uint64_t v, const AudioPart& p) { return v < p.wavOffset; });
        if (it == audioParts_.begin()) break;
        --it;
        const uint64_t within = dataAt - it->wavOffset;
        if (within >= it->size) {
            // RIFF chunks are padded to an even byte count; data length
            // excludes this byte. Also serve partial reads of the padding.
            if (within == it->size && (dataAt & 1) && at + 1 == wavSize()) {
                dst[done++] = 0;
            }
            break;
        }
        const size_t n = std::min<size_t>(len - done, it->size - within);
        if (audio_.format == 2) {
            // float32 -> 24-bit PCM, byte by byte so any offset/length works.
            for (size_t k = 0; k < n; ++k) {
                const uint64_t o = within + k;
                float f;
                std::memcpy(&f, it->data + o / 3 * 4, 4);
                const double v = (std::isnan(f) ? 0.0 : std::clamp(static_cast<double>(f), -1.0, 1.0)) * 8388607.0;
                const int32_t q = static_cast<int32_t>(std::lround(v));
                dst[done + k] = static_cast<uint8_t>(static_cast<uint32_t>(q) >> (8 * (o % 3)));
            }
        } else {
            std::memcpy(dst + done, it->data + within, n);
        }
        done += n;
    }
    return done;
}

RawFrame Clip::frameRaw(size_t i, unsigned threads) const {
    const FrameRecord rec = reader_.frame(i);
    RawFrame out;
    const FrameInfo fi = decodeFrame(rec.payload, rec.payloadSize, out.pixels, threads);
    out.width = fi.width;
    out.height = fi.height;
    out.cfa = static_cast<int>(fi.cfa);
    out.timestampNs = rec.timestampNs;
    const json::Value fm = json::parseOrEmpty(rec.metaJson);
    out.black = info_.black;
    if (auto dyn = numbers(fm["dynamicBlackLevel"]); dyn.size() == 4) std::copy(dyn.begin(), dyn.end(), out.black.begin());
    out.white = fm["dynamicWhiteLevel"].num(info_.white);
    if (auto n = numbers(fm["asShotNeutral"]); n.size() == 3 && n[0] > 0 && n[1] > 0 && n[2] > 0)
        out.neutral = {n[0], n[1], n[2]};
    out.iso = fm["iso"].num(0);
    const double et = fm["exposureTime"].num(0);
    out.exposureSeconds = et > 1000 ? et / 1e9 : et;
    if (fm["lshd_ref"].isNumber()) {
        const auto it = shading_.find(static_cast<uint32_t>(fm["lshd_ref"].num()));
        if (it != shading_.end()) {
            out.shadingColumns = it->second.columns;
            out.shadingRows = it->second.rows;
            out.shadingGains = it->second.gains;
        }
    } else if (fm["lensShadingMap"].isArray() && fm["lensShadingMap"].size() == 4) {
        const auto cols = static_cast<uint16_t>(fm["lensShadingMapWidth"].num());
        const auto rows = static_cast<uint16_t>(fm["lensShadingMapHeight"].num());
        const size_t pts = size_t{cols} * rows;
        std::vector<float> g(pts * 4, 1.f);
        bool ok = pts > 0;
        for (int ch = 0; ch < 4 && ok; ++ch) {
            const auto& plane = fm["lensShadingMap"][ch];
            ok = plane.size() == pts;
            for (size_t k = 0; ok && k < pts; ++k) g[k * 4 + ch] = static_cast<float>(plane[k].num(1));
        }
        if (ok) { out.shadingColumns = cols; out.shadingRows = rows; out.shadingGains = std::move(g); }
    }
    return out;
}

void Clip::setPackedDng(bool on) {
    unsigned bits = 1;
    while (bits < 16 && (1u << bits) <= info_.white) ++bits;
    // White is calibration, not a bound on stored codes. Static Camera2
    // metadata can still describe RAW10 while this take stores DCG RAW14.
    packBits_ = on ? std::max(bits, codedBitDepth_) : 16;
}

uint64_t Clip::dngPixelBytes() const {
    // RGB frames: (W/2 x H/2) pixels x 3 samples. Packed rows end on a byte boundary.
    const uint64_t w = rgb_ ? width_ / 2 : width_, h = rgb_ ? height_ / 2 : height_, spp = rgb_ ? 3 : 1;
    return (w * spp * packBits_ + 7) / 8 * h;
}

std::string Clip::gcsv(const std::string& orientation) const {
    struct Sample { int64_t t; float v[3]; };
    std::vector<Sample> gyro, accel;
    for (const auto& c : reader_.chunks()) {
        if (std::memcmp(c.fourcc.data(), "IMU_", 4) != 0 || !reader_.crcOk(c)) continue;
        const auto p = reader_.payload(c);
        if (p.size() < 8) continue;
        const auto* d = reinterpret_cast<const uint8_t*>(p.data());
        uint32_t count;
        std::memcpy(&count, d + 4, 4);
        if (p.size() < 8 + size_t{count} * 20) continue;
        auto& dst = d[0] == 1 ? gyro : d[0] == 2 ? accel : gyro;
        if (d[0] != 1 && d[0] != 2) continue;
        for (uint32_t i = 0; i < count; ++i) {
            Sample s;
            std::memcpy(&s.t, d + 8 + 20 * size_t{i}, 8);
            std::memcpy(s.v, d + 16 + 20 * size_t{i}, 12);
            dst.push_back(s);
        }
    }
    if (gyro.empty()) return {};
    const json::Value meta = json::parseOrEmpty(reader_.metaJson());
    // RawLens RAW takes before "imuAxes": "android" stored gyro mapped to its
    // burst pipeline's camera frame (gyroMapperVersion 1) while accel stayed
    // in Android axes. Undo that mapping so both sensors share Android axes.
    if (meta["imuAxes"].str("") != "android" && meta["gyroMapperVersion"].num(0) == 1) {
        const int theta = ((static_cast<int>(meta["sensorOrientation"].num(90)) % 360) + 360) % 360;
        const bool front = meta["lensFacing"].str("back") == "front";
        // M = [front mirror] * Rz(90 - theta) * A, A = [[0,1,0],[1,0,0],[0,0,-1]]
        // (GyroCameraFrameMapper v1); quarter turns give exact integers.
        const int q = ((90 - theta) / 90 % 4 + 4) % 4;
        const int c = q == 0 ? 1 : q == 2 ? -1 : 0, sn = q == 1 ? 1 : q == 3 ? -1 : 0;
        const int R[9] = {c, -sn, 0, sn, c, 0, 0, 0, 1};
        const int A[9] = {0, 1, 0, 1, 0, 0, 0, 0, -1};
        int M[9];
        for (int r = 0; r < 3; ++r)
            for (int k = 0; k < 3; ++k) {
                int acc = 0;
                for (int j = 0; j < 3; ++j) acc += R[r * 3 + j] * A[j * 3 + k];
                M[r * 3 + k] = acc;
            }
        if (front) for (int k = 0; k < 3; ++k) M[k] = -M[k];
        // M is a signed permutation (orthogonal): its inverse is its transpose.
        for (auto& g : gyro) {
            const float x = g.v[0], y = g.v[1], z = g.v[2];
            for (int k = 0; k < 3; ++k) g.v[k] = static_cast<float>(M[0 * 3 + k] * x + M[1 * 3 + k] * y + M[2 * 3 + k] * z);
        }
    }
    auto byTime = [](const Sample& a, const Sample& b) { return a.t < b.t; };
    std::stable_sort(gyro.begin(), gyro.end(), byTime);
    std::stable_sort(accel.begin(), accel.end(), byTime);
    const int64_t t0 = reader_.frameCount() ? reader_.frame(0).timestampNs : gyro.front().t;

    std::string out = "GYROFLOW IMU LOG\nversion,1.3\n";
    out += "id,craw_" + info_.uniqueModel + "\n";
    out += "orientation," + orientation + "\n";
    out += "note,Cinematic RAW Studio: raw Android sensor axes; t = 0 at the first frame\n";
    out += "vendor," + info_.make + "\n";
    // Rolling shutter: Camera2 SENSOR_ROLLING_SHUTTER_SKEW (first to last row)
    // from the frame metadata; rows of the stored frame read top to bottom.
    if (reader_.frameCount()) {
        const double skewNs = json::parseOrEmpty(reader_.frame(0).metaJson)["rollingShutterSkew"].num(0);
        if (skewNs > 0) {
            char rs[96];
            std::snprintf(rs, sizeof rs, "frame_readout_time,%.3f\nframe_readout_direction,0\n", skewNs / 1e6);
            out += rs;
        }
    }
    out += "tscale,0.000001\ngscale,1\nascale,1\n";
    out += accel.empty() ? "t,gx,gy,gz\n" : "t,gx,gy,gz,ax,ay,az\n";
    constexpr double kG = 9.80665;  // m/s^2 -> g
    size_t a = 0;
    char line[160];
    for (const auto& g : gyro) {
        if (g.t < t0) continue;  // before the first frame
        const long long us = static_cast<long long>((g.t - t0) / 1000);
        if (accel.empty()) {
            std::snprintf(line, sizeof line, "%lld,%.6f,%.6f,%.6f\n", us, g.v[0], g.v[1], g.v[2]);
        } else {
            while (a + 1 < accel.size() && accel[a + 1].t <= g.t) ++a;
            const Sample& p0 = accel[a];
            const Sample& p1 = a + 1 < accel.size() ? accel[a + 1] : accel[a];
            const double w = (p1.t > p0.t && g.t > p0.t) ? std::min(1.0, double(g.t - p0.t) / double(p1.t - p0.t)) : 0.0;
            double v[3];
            for (int k = 0; k < 3; ++k) v[k] = (p0.v[k] + (p1.v[k] - p0.v[k]) * w) / kG;
            std::snprintf(line, sizeof line, "%lld,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f\n", us, g.v[0], g.v[1], g.v[2], v[0], v[1], v[2]);
        }
        out += line;
    }
    return out;
}

uint64_t Clip::paddedDngSize(uint32_t padTo) const { return uint64_t{padTo} + dngPixelBytes(); }

std::vector<uint8_t> Clip::frameDng(size_t i, uint32_t padTo, unsigned threads) const {
    const FrameRecord rec = reader_.frame(i);
    std::vector<uint16_t> pixels;
    const FrameInfo fi = decodeFrame(rec.payload, rec.payloadSize, pixels, threads);
    const json::Value fm = json::parseOrEmpty(rec.metaJson);
    // RGB frames (SPEC 3.8) become LinearRaw DNGs: W x H pixels, 3 samples each.
    const bool rgb = fi.cfa == Cfa::RGB;
    const uint32_t outW = rgb ? fi.width / 2 : fi.width, outH = rgb ? fi.height / 2 : fi.height;
    const uint32_t spp = rgb ? 3 : 1;
    if (rgb) {
        std::vector<uint16_t> inter(size_t{outW} * outH * 3);
        for (uint32_t y = 0; y < outH; ++y)
            for (uint32_t x = 0; x < outW; ++x) {
                const uint16_t* r0 = pixels.data() + size_t{2 * y} * fi.width + 2 * x;
                uint16_t* o = inter.data() + (size_t{y} * outW + x) * 3;
                o[0] = r0[0];             // R  cell (0,0)
                o[1] = r0[1];             // G  cell (0,1)
                o[2] = r0[fi.width];      // B  cell (1,0)
            }
        pixels.swap(inter);
    }

    Ifd ifd;
    ifd.longs(254, {0});
    ifd.longs(256, {outW});
    ifd.longs(257, {outH});
    const auto bits = static_cast<uint16_t>(packBits_);
    if (rgb) ifd.shorts(258, {bits, bits, bits});
    else ifd.shorts(258, {bits});
    ifd.shorts(259, {1});
    ifd.shorts(262, {static_cast<uint16_t>(rgb ? 34892 : 32803)});
    ifd.ascii(271, info_.make);
    ifd.ascii(272, info_.model);
    ifd.longs(273, {0});  // strip offset, patched in build()
    const int deg = ((info_.orientationDeg % 360) + 360) % 360;
    ifd.shorts(274, {static_cast<uint16_t>(deg == 90 ? 6 : deg == 180 ? 3 : deg == 270 ? 8 : 1)});
    ifd.shorts(277, {static_cast<uint16_t>(spp)});
    ifd.longs(278, {outH});
    ifd.longs(279, {static_cast<uint32_t>(dngPixelBytes())});
    ifd.shorts(284, {1});
    ifd.ascii(305, "Cinematic RAW Studio craw2dng");
    static const uint8_t pattern[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    const int cfa = rgb ? 0 : static_cast<int>(fi.cfa);
    if (!rgb) {
        ifd.shorts(33421, {2, 2});
        ifd.bytes(33422, {pattern[cfa][0], pattern[cfa][1], pattern[cfa][2], pattern[cfa][3]});
    }
    if (fm["exposureTime"].isNumber()) {
        const double ns = fm["exposureTime"].num();
        ifd.rationals(33434, {ns > 1000 ? ns / 1e9 : ns}, false);  // RawLens stores ns
    }
    if (fm["iso"].isNumber()) ifd.shorts(34855, {static_cast<uint16_t>(std::clamp(fm["iso"].num(), 1.0, 65535.0))});
    ifd.bytes(50706, {1, 4, 0, 0});
    ifd.bytes(50707, {1, 1, 0, 0});
    ifd.ascii(50708, info_.uniqueModel);
    std::vector<double> black(info_.black.begin(), info_.black.end());
    if (auto dyn = numbers(fm["dynamicBlackLevel"]); dyn.size() == 4) black = dyn;
    const auto whiteLevel = static_cast<uint32_t>(fm["dynamicWhiteLevel"].num(info_.white));
    if (rgb) {
        // One black/white level per sample (BlackLevelRepeatDim 1x1).
        const double b = (black[0] + black[1] + black[2] + black[3]) / 4;
        ifd.shorts(50713, {1, 1});
        ifd.rationals(50714, {b, b, b}, false);
        ifd.longs(50717, {whiteLevel, whiteLevel, whiteLevel});
    } else {
        ifd.shorts(50713, {2, 2});
        ifd.rationals(50714, black, false);
        ifd.longs(50717, {whiteLevel});
    }
    static const std::vector<double> identity{1, 0, 0, 0, 1, 0, 0, 0, 1};
    ifd.rationals(50721, info_.colorMatrix1.size() == 9 ? info_.colorMatrix1 : identity, true);
    if (info_.colorMatrix2.size() == 9) ifd.rationals(50722, info_.colorMatrix2, true);
    if (info_.calibration1.size() == 9) ifd.rationals(50723, info_.calibration1, true);
    if (info_.calibration2.size() == 9) ifd.rationals(50724, info_.calibration2, true);
    if (auto n = numbers(fm["asShotNeutral"]); n.size() == 3) ifd.rationals(50728, n, false);
    ifd.shorts(50778, {static_cast<uint16_t>(info_.illuminant1)});
    if (info_.colorMatrix2.size() == 9) ifd.shorts(50779, {static_cast<uint16_t>(info_.illuminant2)});
    if (info_.forwardMatrix1.size() == 9) ifd.rationals(50964, info_.forwardMatrix1, true);
    if (info_.forwardMatrix2.size() == 9) ifd.rationals(50965, info_.forwardMatrix2, true);

    // Lens shading -> OpcodeList2 GainMap, one opcode per CFA phase.
    uint16_t cols = 0, rows = 0;
    std::vector<float> gains;  // colour order R, Gr, Gb, B, 4 per point
    if (fm["lshd_ref"].isNumber()) {
        const auto it = shading_.find(static_cast<uint32_t>(fm["lshd_ref"].num()));
        if (it != shading_.end()) { cols = it->second.columns; rows = it->second.rows; gains = it->second.gains; }
    } else if (fm["lensShadingMap"].isArray() && fm["lensShadingMap"].size() == 4) {
        cols = static_cast<uint16_t>(fm["lensShadingMapWidth"].num());
        rows = static_cast<uint16_t>(fm["lensShadingMapHeight"].num());
        const size_t pts = size_t{cols} * rows;
        gains.assign(pts * 4, 1.f);
        bool ok = pts > 0;
        for (int ch = 0; ch < 4 && ok; ++ch) {
            const auto& plane = fm["lensShadingMap"][ch];
            ok = plane.size() == pts;
            for (size_t k = 0; ok && k < pts; ++k) gains[k * 4 + ch] = static_cast<float>(plane[k].num(1));
        }
        if (!ok) { cols = rows = 0; gains.clear(); }
    }
    if (cols > 0 && rows > 0 && rgb) {
        // LinearRaw: one GainMap per colour plane (green = mean of Gr and Gb).
        std::vector<uint8_t> ops;
        be<uint32_t>(ops, 3);
        for (int plane = 0; plane < 3; ++plane) {
            std::vector<uint8_t> p;
            be<uint32_t>(p, 0); be<uint32_t>(p, 0);
            be<uint32_t>(p, outH); be<uint32_t>(p, outW);
            be<uint32_t>(p, static_cast<uint32_t>(plane)); be<uint32_t>(p, 1);
            be<uint32_t>(p, 1); be<uint32_t>(p, 1);
            be<uint32_t>(p, rows); be<uint32_t>(p, cols);
            beF64(p, 1.0 / rows); beF64(p, 1.0 / cols);
            beF64(p, 0.5 / rows); beF64(p, 0.5 / cols);
            be<uint32_t>(p, 1);
            for (size_t k = 0; k < size_t{cols} * rows; ++k) {
                const float* g4 = &gains[k * 4];
                beF32(p, plane == 0 ? g4[0] : plane == 2 ? g4[3] : (g4[1] + g4[2]) / 2);
            }
            be<uint32_t>(ops, 9);
            ops.insert(ops.end(), {1, 3, 0, 0});
            be<uint32_t>(ops, 0);
            be<uint32_t>(ops, static_cast<uint32_t>(p.size()));
            ops.insert(ops.end(), p.begin(), p.end());
        }
        ifd.bytes(51009, std::move(ops), UNDEFINED);
    } else if (cols > 0 && rows > 0) {
        std::vector<uint8_t> ops;
        be<uint32_t>(ops, 4);
        const auto pos = colourPositions(cfa);
        for (int ch = 0; ch < 4; ++ch) {
            std::vector<uint8_t> p;
            be<uint32_t>(p, static_cast<uint32_t>(pos[ch] >> 1));  // top
            be<uint32_t>(p, static_cast<uint32_t>(pos[ch] & 1));   // left
            be<uint32_t>(p, fi.height);
            be<uint32_t>(p, fi.width);
            be<uint32_t>(p, 0); be<uint32_t>(p, 1);  // plane, planes
            be<uint32_t>(p, 2); be<uint32_t>(p, 2);  // row/col pitch
            be<uint32_t>(p, rows); be<uint32_t>(p, cols);
            beF64(p, 1.0 / rows); beF64(p, 1.0 / cols);          // spacing (relative)
            beF64(p, 0.5 / rows); beF64(p, 0.5 / cols);          // origin: cell centres
            be<uint32_t>(p, 1);                                  // map planes
            for (size_t k = 0; k < size_t{cols} * rows; ++k) beF32(p, gains[k * 4 + ch]);
            be<uint32_t>(ops, 9);                // GainMap
            ops.insert(ops.end(), {1, 3, 0, 0}); // DNG 1.3
            be<uint32_t>(ops, 0);                // flags
            be<uint32_t>(ops, static_cast<uint32_t>(p.size()));
            ops.insert(ops.end(), p.begin(), p.end());
        }
        ifd.bytes(51009, std::move(ops), UNDEFINED);
    }

    // CinemaDNG: timecode + frame rate.
    const int fpsInt = std::max(1, static_cast<int>(std::lround(info_.fpsNum / info_.fpsDen)));
    {
        const int64_t idx = static_cast<int64_t>(i) + info_.timecodeStartFrames;
        const int64_t f = idx % fpsInt, s = idx / fpsInt;
        auto bcd = [](int64_t v) { return static_cast<uint8_t>(((v / 10) << 4) | (v % 10)); };
        ifd.bytes(51043, {bcd(f), bcd(s % 60), bcd(s / 60 % 60), bcd(s / 3600 % 24), 0, 0, 0, 0});
    }
    {
        std::vector<uint8_t> rate;
        le(rate, static_cast<int32_t>(std::lround(info_.fpsNum)));
        le(rate, static_cast<int32_t>(info_.fpsDen));
        ifd.raw(51044, SRATIONAL, 1, std::move(rate));
    }

    // Layout: header (8) | IFD + blobs | [pad] | pixels.
    std::vector<uint8_t> out{'I', 'I', 42, 0, 8, 0, 0, 0};
    auto ifdBytes = ifd.build(8, 0);
    uint32_t stripOffset = (static_cast<uint32_t>(8 + ifdBytes.size()) + 1) & ~1u;
    if (padTo > 0) stripOffset = padTo;
    ifdBytes = ifd.build(8, stripOffset);
    if (padTo > 0 && 8 + ifdBytes.size() > padTo) throw DecodeError("craw2dng: DNG header exceeds padding");
    out.insert(out.end(), ifdBytes.begin(), ifdBytes.end());
    out.resize(stripOffset, 0);
    if (packBits_ == 16) {
        const size_t px = pixels.size() * 2;
        out.resize(stripOffset + px);
        std::memcpy(out.data() + stripOffset, pixels.data(), px);  // little-endian host
        return out;
    }
    // Bit-packed, MSB first, each row starting on a byte. A sample above the
    // white level (cannot occur at the sensor bit depth) is clamped to it.
    const size_t rowSamples = size_t{outW} * spp, rowBytes = (rowSamples * packBits_ + 7) / 8;
    out.resize(stripOffset + rowBytes * outH, 0);
    const uint32_t maxV = (1u << packBits_) - 1;
    auto packRows = [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            const uint16_t* s = pixels.data() + size_t{y} * rowSamples;
            uint8_t* d = out.data() + stripOffset + size_t{y} * rowBytes;
            size_t k = 0;
            if (bits == 10)  // common case: 4 samples -> 5 bytes
                for (; k + 4 <= rowSamples; k += 4, d += 5) {
                    const uint32_t a = std::min<uint32_t>(s[k], maxV), b = std::min<uint32_t>(s[k + 1], maxV);
                    const uint32_t c = std::min<uint32_t>(s[k + 2], maxV), e = std::min<uint32_t>(s[k + 3], maxV);
                    d[0] = static_cast<uint8_t>(a >> 2);
                    d[1] = static_cast<uint8_t>((a << 6) | (b >> 4));
                    d[2] = static_cast<uint8_t>((b << 4) | (c >> 6));
                    d[3] = static_cast<uint8_t>((c << 2) | (e >> 8));
                    d[4] = static_cast<uint8_t>(e);
                }
            uint64_t acc = 0;
            unsigned n = 0;
            for (; k < rowSamples; ++k) {
                acc = (acc << bits) | std::min<uint32_t>(s[k], maxV);
                n += bits;
                while (n >= 8) { n -= 8; *d++ = static_cast<uint8_t>(acc >> n); }
            }
            if (n) *d = static_cast<uint8_t>(acc << (8 - n));
        }
    };
    const unsigned parts = std::max(1u, std::min(threads, outH));
    std::vector<std::thread> pool;
    for (unsigned t = 1; t < parts; ++t)
        pool.emplace_back(packRows, outH * t / parts, outH * (t + 1) / parts);
    packRows(0, outH / parts);
    for (auto& th : pool) th.join();
    return out;
}

}  // namespace craw::tools
