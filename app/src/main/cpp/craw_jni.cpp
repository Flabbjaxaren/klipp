#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <thread>

#include "common/Clip.h"

namespace {
using craw::tools::Clip;
using craw::tools::RawFrame;

// CRAW CFA values map each 2x2 tile to camera RGB. In recorded RGB, the
// bottom-right sample is unused and the other three samples form one pixel.
constexpr int bayer[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
constexpr int rgbPlanes[] = {0, 1, 2};
// Camera2 lens shading planes are R, green on red row, green on blue row, B.
constexpr int shadingPlanes[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};

struct Plane {
    uint8_t* data;
    int row;
    int pixel;

    void put(int x, int y, unsigned value) const {
        const uint16_t word = value << 6;
        std::memcpy(data + size_t(y) * row + size_t(x) * pixel, &word, sizeof(word));
    }
};

Plane plane(JNIEnv* env, jobject buffer, int row, int pixel, int w, int h) {
    auto* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!ptr || pixel < 2 || row < int64_t(w - 1) * pixel + 2 ||
        env->GetDirectBufferCapacity(buffer) < int64_t(h - 1) * row + int64_t(w - 1) * pixel + 2) {
        throw std::runtime_error("Invalid encoder image plane");
    }
    return {ptr, row, pixel};
}

using Color = std::array<float, 3>;
using Matrix = std::array<Color, 3>;

Matrix cameraToLogGamut(const craw::tools::ClipInfo& info, const Matrix& xyzToGamut) {
    const auto& forward = info.forwardMatrix1.size() == 9 ? info.forwardMatrix1 : info.forwardMatrix2;
    const bool calibrated = forward.size() == 9 &&
        std::all_of(forward.begin(), forward.end(), [](double v) { return std::isfinite(v) && std::abs(v) < 100; });
    constexpr Matrix d50ToD65{{{.9555766f, -.0230393f, .0631636f},
        {-.0282895f, 1.0099416f, .0210077f}, {.0122982f, -.0204830f, 1.3299098f}}};
    constexpr Matrix rec709ToXyz{{{.4123908f, .3575843f, .1804808f},
        {.212639f, .7151687f, .0721923f}, {.0193308f, .1191948f, .9505322f}}};
    Matrix cameraToXyz = rec709ToXyz;
    if (calibrated) {
        for (int r = 0; r < 3; ++r) {
            for (int c = 0; c < 3; ++c) {
                cameraToXyz[r][c] = d50ToD65[r][0] * forward[c] +
                    d50ToD65[r][1] * forward[3 + c] + d50ToD65[r][2] * forward[6 + c];
            }
        }
    }
    Matrix result{};
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            result[r][c] = xyzToGamut[r][0] * cameraToXyz[0][c] +
                xyzToGamut[r][1] * cameraToXyz[1][c] + xyzToGamut[r][2] * cameraToXyz[2][c];
        }
    }
    return result;
}

constexpr float logLutMin = -.0625f;
constexpr int logLutScale = 4096;
constexpr size_t logLutSplit = 33280; // 8.0
constexpr size_t logLutSize = logLutSplit + 120 * 64 + 1;

struct LogEncoding {
    Matrix matrix;
    float exposure;
    std::array<float, logLutSize> curve;
};

struct Source {
    Clip clip;
    std::unique_ptr<LogEncoding> encoding;
    explicit Source(int fd) : clip(fd, "CRAW import") {}
};

class Develop {
public:
    Develop(const RawFrame& raw, const Source& source)
        : raw_(raw), rgb_(source.clip.isRgb()), encoding_(*source.encoding) {
        if ((!rgb_ && (raw.cfa < 0 || raw.cfa > 3)) || raw.width < 2 || raw.height < 2 ||
            !std::isfinite(raw.white) || !std::all_of(raw.black.begin(), raw.black.end(),
                [&](double black) { return std::isfinite(black) && raw.white - black >= 1; })) {
            throw std::runtime_error("Invalid CRAW frame calibration");
        }
        double reference = 0;
        for (double n : raw.neutral) if (std::isfinite(n) && n > 0) reference = std::max(reference, n);
        for (int c = 0; c < 3; ++c) {
            const double n = raw.neutral[c];
            const double wb = std::isfinite(n) && n > 0 ? std::min(8.0, reference / n) : 1;
            whiteBalance_[c] = wb * encoding_.exposure;
        }
        for (int c = 0; c < 4; ++c) inverseRange_[c] = 1. / (raw.white - raw.black[c]);
        if (raw.shadingColumns > 0 && raw.shadingRows > 0 &&
            raw.shadingGains.size() == size_t(raw.shadingColumns) * raw.shadingRows * 4) {
            xShading_ = grid(raw.width, raw.shadingColumns, 4);
            yShading_ = grid(raw.height, raw.shadingRows, raw.shadingColumns * 4);
        }
    }

    Color at(int x, int y) const {
        Color camera{};
        if (rgb_) {
            for (int c : rgbPlanes) camera[c] = sample(x * 2 + (c == 1), y * 2 + (c == 2), c);
        } else {
            int count[3]{};
            const int own = channel(x, y);
            camera[own] = sample(x, y, own);
            for (int yy = std::max(0, y - 1); yy <= std::min(int(raw_.height) - 1, y + 1); ++yy) {
                for (int xx = std::max(0, x - 1); xx <= std::min(int(raw_.width) - 1, x + 1); ++xx) {
                    const int c = channel(xx, yy);
                    if (c == own) continue;
                    camera[c] += sample(xx, yy, c);
                    ++count[c];
                }
            }
            for (int c = 0; c < 3; ++c) if (count[c]) camera[c] /= count[c];
        }
        Color result{};
        for (int c = 0; c < 3; ++c) {
            const auto& m = encoding_.matrix[c];
            const float v = m[0] * camera[0] + m[1] * camera[1] + m[2] * camera[2];
            const float index = v <= 8.f ? (v - logLutMin) * logLutScale : logLutSplit + (v - 8.f) * 64.f;
            const float at = std::clamp(index, 0.f, float(logLutSize - 1));
            const size_t lo = size_t(at);
            const size_t hi = std::min(lo + 1, logLutSize - 1);
            result[c] = std::lerp(encoding_.curve[lo], encoding_.curve[hi], at - lo);
        }
        return result;
    }

private:
    int channel(int x, int y) const { return bayer[raw_.cfa][(y & 1) * 2 + (x & 1)]; }

    struct GridPoint { size_t first; size_t second; float fraction; };

    static std::vector<GridPoint> grid(unsigned size, int count, size_t stride) {
        std::vector<GridPoint> points(size);
        for (unsigned i = 0; i < size; ++i) {
            const float coordinate = std::clamp((i + .5f) / size * count - .5f, 0.f, float(count - 1));
            const int low = int(coordinate);
            points[i] = {low * stride, std::min(low + 1, count - 1) * stride, coordinate - low};
        }
        return points;
    }

    float sample(int x, int y, int c) const {
        const int site = rgb_ ? c : (y & 1) * 2 + (x & 1);
        const float normalized = (raw_.pixels[size_t(y) * raw_.width + x] - raw_.black[site]) * inverseRange_[site];
        float gain = 1;
        if (!xShading_.empty()) {
            constexpr int rgbShading[] = {0, 1, 3};
            const int plane = rgb_ ? rgbShading[c] : shadingPlanes[raw_.cfa][site];
            const auto& xx = xShading_[x];
            const auto& yy = yShading_[y];
            const auto* gains = raw_.shadingGains.data() + plane;
            const float a = std::lerp(gains[yy.first + xx.first], gains[yy.first + xx.second], xx.fraction);
            const float b = std::lerp(gains[yy.second + xx.first], gains[yy.second + xx.second], xx.fraction);
            const float value = std::lerp(a, b, yy.fraction);
            if (std::isfinite(value) && value > 0) gain = std::min(value, 8.f);
        }
        return std::max(normalized, 0.f) * whiteBalance_[c] * gain;
    }

    const RawFrame& raw_;
    bool rgb_;
    const LogEncoding& encoding_;
    std::array<float, 4> inverseRange_{};
    Color whiteBalance_{};
    std::vector<GridPoint> xShading_, yShading_;
};

void fail(JNIEnv* env, const std::exception& e) {
    if (!env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/io/IOException"), e.what());
    }
}

Source& source(jlong handle) { return *reinterpret_cast<Source*>(handle); }

unsigned quantize(float v) { return unsigned(v + .5f); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_se_gendut_klipp_engine_CrawImport_open(JNIEnv* env, jobject, jint fd) {
    try {
        auto input = std::make_unique<Source>(fd);
        if (!input->clip.frameCount()) throw std::runtime_error("CRAW has no video frames");
        return reinterpret_cast<jlong>(input.release());
    } catch (const std::exception& e) {
        fail(env, e);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_se_gendut_klipp_engine_CrawImport_close(JNIEnv*, jobject, jlong handle) {
    delete &source(handle);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_se_gendut_klipp_engine_CrawImport_metadata(JNIEnv* env, jobject, jlong handle) {
    const auto& c = source(handle).clip;
    const auto [w, h] = c.imageSize();
    const auto& info = c.info();
    const auto& audio = c.audio();
    const double fps = info.fpsNum / info.fpsDen;
    const jlong fields[] = {w, h, jlong(c.frameCount()), info.orientationDeg,
        std::isfinite(fps) && fps >= 1 && fps <= 1000 ? jlong(std::round(fps * 1000)) : 30000,
        audio.channels, audio.sampleRate, jlong(audio.frames), c.reader().frame(0).timestampNs};
    auto result = env->NewLongArray(std::size(fields));
    if (result) env->SetLongArrayRegion(result, 0, std::size(fields), fields);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_se_gendut_klipp_engine_CrawImport_configure(JNIEnv* env, jobject, jlong handle,
    jfloatArray xyzToGamut, jfloatArray curve, jint ei) {
    try {
        auto& input = source(handle);
        auto encoding = std::make_unique<LogEncoding>();
        Matrix matrix{};
        for (int r = 0; r < 3; ++r) env->GetFloatArrayRegion(xyzToGamut, r * 3, 3, matrix[r].data());
        env->GetFloatArrayRegion(curve, 0, logLutSize, encoding->curve.data());
        if (env->ExceptionCheck()) return;
        encoding->matrix = cameraToLogGamut(input.clip.info(), matrix);
        encoding->exposure = 1;
        if (ei > 0) {
            const double iso = input.clip.frameRaw(0).iso;
            const double base = std::isfinite(iso) && iso > 0 ? iso : 800;
            encoding->exposure = std::clamp(ei / base, .03125, 32.);
        }
        input.encoding = std::move(encoding);
    } catch (const std::exception& e) {
        fail(env, e);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_se_gendut_klipp_engine_CrawImport_decode(JNIEnv* env, jobject, jlong handle, jint frame,
    jint w, jint h, jobject yBuffer, jint yRow, jint yPixel, jobject uBuffer, jint uRow, jint uPixel,
    jobject vBuffer, jint vRow, jint vPixel) {
    try {
        const auto& c = source(handle).clip;
        const auto raw = c.frameRaw(frame, std::clamp(std::thread::hardware_concurrency(), 1u, 4u));
        const unsigned scale = c.isRgb() ? 2 : 1;
        if (raw.width / scale < unsigned(w) || raw.height / scale < unsigned(h) ||
            (raw.cfa == int(craw::Cfa::RGB)) != c.isRgb()) {
            throw std::runtime_error("CRAW frame layout changed");
        }
        const auto yPlane = plane(env, yBuffer, yRow, yPixel, w, h);
        const auto uPlane = plane(env, uBuffer, uRow, uPixel, w / 2, h / 2);
        const auto vPlane = plane(env, vBuffer, vRow, vPixel, w / 2, h / 2);
        const Develop develop(raw, source(handle));
        const int sourceW = raw.width / scale, sourceH = raw.height / scale;
        struct AxisSample { int first, second; float weight; };
        const auto sampling = [](int from, int to) {
            std::vector<AxisSample> lookup(to);
            for (int i = 0; i < to; ++i) {
                const float position = std::clamp((i + .5f) * from / to - .5f, 0.f, float(from - 1));
                const int first = int(position);
                lookup[i] = {first, std::min(first + 1, from - 1), position - first};
            }
            return lookup;
        };
        const bool resize = sourceW != w || sourceH != h;
        const auto xLookup = resize ? sampling(sourceW, w) : std::vector<AxisSample>{};
        const auto yLookup = resize ? sampling(sourceH, h) : std::vector<AxisSample>{};
        auto writeRows = [&](int begin, int end, std::array<std::vector<Color>, 4>& rows) {
            std::array<int, 4> indices{-1, -1, -1, -1};
            std::array<int, 4> needed{};
            const auto developedRow = [&](int sy) -> const std::vector<Color>& {
                for (size_t i = 0; i < indices.size(); ++i) if (indices[i] == sy) return rows[i];
                size_t slot = 0;
                while (std::find(needed.begin(), needed.end(), indices[slot]) != needed.end()) ++slot;
                indices[slot] = sy;
                auto& row = rows[slot];
                for (int sx = 0; sx < sourceW; ++sx) row[sx] = develop.at(sx, sy);
                return row;
            };
            for (int y = begin; y < end; y += 2) {
                std::array<std::array<const std::vector<Color>*, 2>, 2> lines{};
                if (resize) {
                    needed = {yLookup[y].first, yLookup[y].second, yLookup[y + 1].first, yLookup[y + 1].second};
                    for (int dy = 0; dy < 2; ++dy) {
                        const auto& cy = yLookup[y + dy];
                        lines[dy] = {&developedRow(cy.first), &developedRow(cy.second)};
                    }
                }
                for (int x = 0; x < w; x += 2) {
                    float cb = 0, cr = 0;
                    for (int dy = 0; dy < 2; ++dy) {
                        for (int dx = 0; dx < 2; ++dx) {
                            Color rgb;
                            if (resize) {
                                const auto& cx = xLookup[x + dx];
                                const auto& cy = yLookup[y + dy];
                                const auto& a = *lines[dy][0];
                                const auto& b = *lines[dy][1];
                                for (int c = 0; c < 3; ++c) {
                                    rgb[c] = std::lerp(std::lerp(a[cx.first][c], a[cx.second][c], cx.weight),
                                        std::lerp(b[cx.first][c], b[cx.second][c], cx.weight), cy.weight);
                                }
                            } else {
                                rgb = develop.at(x + dx, y + dy);
                            }
                            const auto [r, g, b] = rgb;
                            const float luma = .2126f * r + .7152f * g + .0722f * b;
                            yPlane.put(x + dx, y + dy, quantize(64 + 876 * luma));
                            cb += b - luma;
                            cr += r - luma;
                        }
                    }
                    uPlane.put(x / 2, y / 2, quantize(512 + 224 * cb / 1.8556f));
                    vPlane.put(x / 2, y / 2, quantize(512 + 224 * cr / 1.5748f));
                }
            }
        };
        const int workers = int64_t(w) * h < 1'000'000 ? 1 :
            int(std::clamp(std::thread::hardware_concurrency(), 1u, 4u));
        std::vector<std::array<std::vector<Color>, 4>> rowCaches(workers);
        if (resize) for (auto& cache : rowCaches) for (auto& row : cache) row.resize(sourceW);
        const auto row = [&](int part) { return 2 * ((h / 2) * part / workers); };
        std::vector<std::jthread> tasks;
        for (int part = 1; part < workers; ++part) tasks.emplace_back(writeRows, row(part), row(part + 1), std::ref(rowCaches[part]));
        writeRows(0, row(1), rowCaches[0]);
        tasks.clear(); // Join before the encoder receives the image.
        return raw.timestampNs;
    } catch (const std::exception& e) {
        fail(env, e);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_se_gendut_klipp_engine_CrawImport_pcm16(JNIEnv* env, jobject, jlong handle, jlong start,
    jint frames, jobject buffer) {
    try {
        const auto& c = source(handle).clip;
        const auto& audio = c.audio();
        const size_t samples = size_t(frames) * audio.channels;
        auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
        if (!out || env->GetDirectBufferCapacity(buffer) < int64_t(samples * 2) ||
            start < 0 || frames < 0 || uint64_t(start) + frames > audio.frames) {
            throw std::runtime_error("Invalid CRAW audio range");
        }
        const size_t stride = audio.format == 1 ? 2 : 3;
        const auto bytes = audio.frames * audio.channels * stride;
        const uint64_t payload = c.wavSize() - bytes - (bytes & 1);
        const auto offset = payload + uint64_t(start) * audio.channels * stride;
        if (stride == 2) {
            if (c.readWav(offset, out, samples * 2) != samples * 2) throw std::runtime_error("Short CRAW audio");
        } else {
            std::vector<uint8_t> pcm(samples * 3);
            if (c.readWav(offset, pcm.data(), pcm.size()) != pcm.size()) throw std::runtime_error("Short CRAW audio");
            for (size_t i = 0; i < samples; ++i) {
                out[2 * i] = pcm[3 * i + 1];
                out[2 * i + 1] = pcm[3 * i + 2];
            }
        }
    } catch (const std::exception& e) {
        fail(env, e);
    }
}
