// JNI bridge to whisper.cpp: load model, transcribe 16 kHz mono audio, cancel, release.
// The result is JSON (UTF-8 bytes): [{"t0":ms,"t1":ms,"text":"..."}, ...].
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <mutex>
#include <vector>
#include "ggml-backend.h"
#include "whisper.h"

namespace {

struct Ctx {
    whisper_context *w = nullptr;
    std::atomic<bool> abort{false};
};

struct Progress {
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID method = nullptr;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user) {
    auto *p = static_cast<Progress *>(user);
    if (p->listener && p->method) {
        p->env->CallVoidMethod(p->listener, p->method, (jint) progress);
        if (p->env->ExceptionCheck()) p->env->ExceptionClear();
    }
}

bool on_abort(void *user) { return static_cast<Ctx *>(user)->abort.load(); }

void json_escape(std::string &out, const char *s) {
    for (const unsigned char *c = (const unsigned char *) s; *c; ++c) {
        switch (*c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (*c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof b, "\\u%04x", *c);
                    out += b;
                } else {
                    out += (char) *c;
                }
        }
    }
}

void silent_log(ggml_log_level, const char *, void *) {}

// The CPU backend is always loaded; the OpenCL one (Adreno) only when the GPU is actually asked for – loading it
// initializes the phone's OpenCL driver, which must never happen on the CPU path (a driver crash takes the app down).
void load_cpu() {
    static std::once_flag once;
    std::call_once(once, [] { ggml_backend_load("libggml-cpu.so"); });
}

bool load_gpu() {
    load_cpu();
    static std::once_flag once;
    static bool gpu = false;
    std::call_once(once, [] {
        ggml_backend_load("libggml-opencl.so");
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto t = ggml_backend_dev_type(ggml_backend_dev_get(i));
            if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) gpu = true;
        }
    });
    return gpu;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_se_gendut_klipp_engine_Whisper_nativeGpuAvailable(JNIEnv *, jclass) {
    whisper_log_set(silent_log, nullptr);
    return load_gpu() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_Whisper_nativeInit(JNIEnv *env, jclass, jstring path, jboolean useGpu) {
    whisper_log_set(silent_log, nullptr);
    load_cpu();
    const bool gpu = useGpu && load_gpu();
    const char *p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (!p) return 0;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = gpu;
    whisper_context *w = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!w) return 0;
    auto *c = new Ctx();
    c->w = w;
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_Whisper_nativeFree(JNIEnv *, jclass, jlong h) {
    auto *c = reinterpret_cast<Ctx *>(h);
    if (!c) return;
    whisper_free(c->w);
    delete c;
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_Whisper_nativeAbort(JNIEnv *, jclass, jlong h) {
    auto *c = reinterpret_cast<Ctx *>(h);
    if (c) c->abort.store(true);
}

JNIEXPORT jbyteArray JNICALL Java_se_gendut_klipp_engine_Whisper_nativeTranscribe(
        JNIEnv *env, jclass, jlong h, jfloatArray pcm, jstring lang, jint threads, jint maxLen, jstring prompt, jstring vadModel, jobject listener) {
    auto *c = reinterpret_cast<Ctx *>(h);
    if (!c || !pcm) return nullptr;
    c->abort.store(false);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wp.beam_search.beam_size = 2;
    wp.n_threads = threads > 0 ? threads : 4;
    wp.translate = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    // short lines for captions: word wrap at no more than maxLen characters
    wp.token_timestamps = maxLen > 0;
    wp.max_len = maxLen > 0 ? maxLen : 0;
    wp.split_on_word = true;
    wp.suppress_nst = true;

    const char *l = lang ? env->GetStringUTFChars(lang, nullptr) : nullptr;
    wp.language = (l && *l) ? l : "auto";
    wp.detect_language = false;
    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    if (pr && *pr) wp.initial_prompt = pr;
    // voice activity detection (Silero): only the parts with speech are transcribed – no invented text over room
    // noise, music or silence, and long pauses are skipped
    const char *vm = vadModel ? env->GetStringUTFChars(vadModel, nullptr) : nullptr;
    if (vm && *vm) {
        wp.vad = true;
        wp.vad_model_path = vm;
        wp.vad_params = whisper_vad_default_params();
    }

    Progress prog;
    prog.env = env;
    prog.listener = listener;
    if (listener) {
        jclass k = env->GetObjectClass(listener);
        prog.method = env->GetMethodID(k, "onProgress", "(I)V");
        env->DeleteLocalRef(k);
        if (env->ExceptionCheck()) { env->ExceptionClear(); prog.method = nullptr; }
    }
    wp.progress_callback = on_progress;
    wp.progress_callback_user_data = &prog;
    wp.abort_callback = on_abort;
    wp.abort_callback_user_data = c;

    jsize n = env->GetArrayLength(pcm);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    if (!data) {
        // out of memory: nothing to listen to (the Java exception stays pending and is rethrown)
        if (l) env->ReleaseStringUTFChars(lang, l);
        if (pr) env->ReleaseStringUTFChars(prompt, pr);
        return nullptr;
    }
    int r = whisper_full(c->w, wp, data, n);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    if (l) env->ReleaseStringUTFChars(lang, l);
    if (pr) env->ReleaseStringUTFChars(prompt, pr);
    if (vm) env->ReleaseStringUTFChars(vadModel, vm);
    if (r != 0 || c->abort.load()) return nullptr;

    // With VAD, a segment's start after a long pause is placed where the previous speech ended (whisper maps the
    // joined speech back to the original time only roughly) – it is clamped into the speech segment it ends in.
    std::vector<std::pair<int64_t, int64_t>> speech;
    for (int i = 0; i < whisper_full_n_vad_segments(c->w); ++i)
        speech.emplace_back(whisper_full_get_vad_segment_t0(c->w, i), whisper_full_get_vad_segment_t1(c->w, i));

    std::string out = "[";
    const int segs = whisper_full_n_segments(c->w);
    for (int i = 0; i < segs; ++i) {
        if (i) out += ',';
        int64_t t0 = whisper_full_get_segment_t0(c->w, i), t1 = whisper_full_get_segment_t1(c->w, i);
        for (auto &sp : speech) {
            if (t1 > sp.first && t1 <= sp.second + 2) { if (t0 < sp.first) t0 = sp.first; break; }
        }
        // times in hundredths of a second → ms
        out += "{\"t0\":" + std::to_string(t0 * 10);
        out += ",\"t1\":" + std::to_string(t1 * 10);
        out += ",\"text\":\"";
        json_escape(out, whisper_full_get_segment_text(c->w, i));
        out += "\"}";
    }
    out += "]";
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

} // extern "C"
