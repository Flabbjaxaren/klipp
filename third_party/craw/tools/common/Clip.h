// SPDX-License-Identifier: Apache-2.0
//
// A .CRAW take as CinemaDNG: per-frame DNG files and one WAV file, used by
// craw2dng and craw_mount. Accepts both the SPEC.md META layout
// ("sensor"/"lens"/"video") and the flat RawLens keys (blackLevel,
// colorMatrix1, sensorArrangment, ...).
#pragma once

#include <craw/Reader.h>

#include "Json.h"

#include <array>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace craw::tools {

struct ClipInfo {
    std::string make = "Unknown", model = "Unknown", uniqueModel = "Cinematic RAW Studio";
    int cfa = 0;  // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    std::array<double, 4> black{0, 0, 0, 0};  // 2x2 row-major
    double white = 65535;
    std::vector<double> colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, calibration1, calibration2;
    int illuminant1 = 21, illuminant2 = 17;
    int orientationDeg = 0;
    double fpsNum = 30, fpsDen = 1;
    int timecodeStartFrames = 0;
};

struct AudioInfo {
    int format = 0;  // AUDI: 1 S16, 2 F32, 3 S24; 0 = no audio
    int channels = 0;
    uint32_t sampleRate = 0;
    uint64_t frames = 0;  // per channel
};

// Everything needed to develop one frame (see CinematicRAWStudio-TUI).
struct RawFrame {
    std::vector<uint16_t> pixels;  // width * height CFA samples
    uint32_t width = 0, height = 0;
    int cfa = 0;
    std::array<double, 4> black{};  // 2x2 row-major
    double white = 65535;
    std::array<double, 3> neutral{1, 1, 1};  // AsShotNeutral (camera RGB of white)
    double iso = 0, exposureSeconds = 0;
    int64_t timestampNs = 0;
    // Lens shading: gains per point, colour order R, Gr, Gb, B; empty = none.
    uint16_t shadingColumns = 0, shadingRows = 0;
    std::vector<float> shadingGains;
};

class Clip {
public:
    explicit Clip(const std::string& path);
#ifndef _WIN32
    // From an open descriptor (see Reader(int, name)); `fd` stays the caller's.
    Clip(int fd, const std::string& name);
#endif

    const ClipInfo& info() const { return info_; }
    size_t frameCount() const { return reader_.frameCount(); }
    const Reader& reader() const { return reader_; }

    // DNG for frame `i`. With `padTo` > 0 the image data starts at offset
    // `padTo` (so every frame of a clip has the same size; craw_mount needs
    // sizes before decoding). Throws DecodeError.
    std::vector<uint8_t> frameDng(size_t i, uint32_t padTo = 0, unsigned threads = 1) const;

    // Decoded CFA samples plus per-frame levels, white balance and lens shading.
    RawFrame frameRaw(size_t i, unsigned threads = 1) const;

    // Size of every padded DNG of this clip (all frames share dimensions).
    uint64_t paddedDngSize(uint32_t padTo) const;
    std::pair<uint32_t, uint32_t> frameSize() const { return {width_, height_}; }  // coded frame size
    bool isRgb() const { return rgb_; }
    // DNG samples bit-packed (MSB first) at the sensor bit depth instead of
    // 16 bits each: 10-bit takes become 37.5 % smaller, still lossless.
    // Used by the mounts, where every byte crosses the filesystem bridge.
    void setPackedDng(bool on);
    // Bytes of one frame's DNG pixel strip.
    uint64_t dngPixelBytes() const;
    // Picture size: half the coded size for RGB frames.
    std::pair<uint32_t, uint32_t> imageSize() const { return rgb_ ? std::make_pair(width_ / 2, height_ / 2) : std::make_pair(width_, height_); }

    const AudioInfo& audio() const { return audio_; }

    // Gyroflow motion log (.gcsv 1.3) from the IMU_ chunks: one row per gyro
    // sample, t in microseconds since the first frame, gyro in rad/s and
    // accelerometer (interpolated to the gyro times) in g, raw Android sensor
    // axes. `orientation` is Gyroflow's IMU orientation string. Empty when the
    // take has no gyro data.
    std::string gcsv(const std::string& orientation = kDefaultGcsvOrientation) const;
    static constexpr const char* kDefaultGcsvOrientation = "XYZ";
    uint64_t wavSize() const;
    // Reads `len` bytes of the WAV file at `offset` (header + concatenated AUDI samples).
    size_t readWav(uint64_t offset, uint8_t* dst, size_t len) const;

private:
    unsigned codedBitDepth_ = 16;
    void init();
    struct AudioPart { uint64_t wavOffset; const uint8_t* data; size_t size; };
    struct Shading { uint16_t columns = 0, rows = 0; std::vector<float> gains; };

    Reader reader_;
    ClipInfo info_;
    AudioInfo audio_;
    std::vector<AudioPart> audioParts_;
    std::vector<uint8_t> wavHeader_;
    std::map<uint32_t, Shading> shading_;
    uint32_t width_ = 0, height_ = 0;
    bool rgb_ = false;  // frames carry RGB cells (SPEC 3.8)
    unsigned packBits_ = 16;  // DNG BitsPerSample
};

// SMPTE timecode string HH:MM:SS:FF for frame `index` at an integer fps.
std::string timecode(int64_t index, int fps);

}  // namespace craw::tools
