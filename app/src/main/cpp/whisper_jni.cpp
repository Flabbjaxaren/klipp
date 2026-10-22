// JNI bridge to whisper.cpp: load model, transcribe 16 kHz mono audio, cancel, release.
// The result is JSON (UTF-8 bytes): [{"t0":ms,"t1":ms,"text":"..."}, ...].
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <string>
#include <vector>
#include "whisper.h"

namespace {

struct Ctx {
    whisper_context *w = nullptr;
    bool dtw = false;
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

whisper_alignment_heads_preset heads(const char *s) {
    std::string n = s ? s : "";
    if (n == "base") return WHISPER_AHEADS_BASE;
    if (n == "small") return WHISPER_AHEADS_SMALL;
    if (n == "medium") return WHISPER_AHEADS_MEDIUM;
    return WHISPER_AHEADS_NONE;
}

// a word or only punctuation? ASCII letters/digits or any other UTF-8 byte (å, é – also a token holding only part of
// one) count as a word; the general punctuation block U+2000–U+206F (– … ”) does not.
bool has_word(const char *s) {
    for (const unsigned char *c = (const unsigned char *) s; *c; ++c) {
        if (isalnum(*c)) return true;
        if (*c == 0xE2 && (c[1] == 0x80 || c[1] == 0x81)) {
            if (c[2]) c += 2; else break;
            continue;
        }
        if (*c >= 0x80) return true;
    }
    return false;
}

/*
 * Word times by DTW (one word per segment: max_len 1 with word splitting). whisper's own token times put word edges
 * a median ~0.2 s off; the DTW alignment of the cross-attention is far tighter. whisper.cpp gives each token the
 * time the alignment LEAVES it (the first jump goes to the first token), so a word starts [startMs] before the DTW
 * time of its first token and ends [endMs] after that of its last – offsets measured per model against Swedish speech
 * with known word edges (median error ~40 ms instead of ~180 ms). Neighbours that overlap meet halfway.
 * Segments without a DTW time keep whisper's times; punctuation keeps the previous word's end.
 */
void words_by_dtw(whisper_context *w, std::vector<std::pair<int64_t, int64_t>> &times, int startMs, int endMs) {
    const whisper_token eot = whisper_token_eot(w);
    std::vector<bool> word(times.size());
    int prev = -1;
    for (int i = 0; i < (int) times.size(); ++i) {
        if (!(word[i] = has_word(whisper_full_get_segment_text(w, i)))) continue;
        int64_t first = -1, last = -1;
        for (int k = 0; k < whisper_full_n_tokens(w, i); ++k) {
            if (whisper_full_get_token_id(w, i, k) >= eot) continue;
            if (!has_word(whisper_full_get_token_text(w, i, k))) continue;
            const int64_t t = whisper_full_get_token_t_dtw(w, i, k);
            if (t < 0) continue;
            if (first < 0) first = t;
            last = t;
        }
        if (first < 0) { prev = i; continue; }
        int64_t t0 = std::max<int64_t>(0, first * 10 - startMs), t1 = std::max(t0 + 50, last * 10 + endMs);
        if (prev >= 0 && times[prev].second > t0) {
            const int64_t mid = std::max((times[prev].second + t0) / 2, times[prev].first + 50);
            times[prev].second = mid;
            t0 = std::max(t0, mid);
            t1 = std::max(t1, t0 + 50);
        }
        times[i] = {t0, t1};
        prev = i;
    }
    // punctuation ends where the word before it ends (it is joined onto that word)
    for (int i = 1; i < (int) times.size(); ++i)
        if (!word[i]) times[i] = {times[i - 1].second, times[i - 1].second};
}

} // namespace

extern "C" {

// [dtwHeads]: the model's alignment heads ("base", "small", "medium") for word times by DTW, or null. DTW needs flash
// attention off – only used for word transcripts, where the times decide where text-based edits cut.
JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_Whisper_nativeInit(JNIEnv *env, jclass, jstring path, jstring dtwHeads) {
    whisper_log_set(silent_log, nullptr);
    const char *p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (!p) return 0;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    const char *dh = dtwHeads ? env->GetStringUTFChars(dtwHeads, nullptr) : nullptr;
    const whisper_alignment_heads_preset preset = heads(dh);
    if (dh) env->ReleaseStringUTFChars(dtwHeads, dh);
    if (preset != WHISPER_AHEADS_NONE) {
        cp.flash_attn = false;
        cp.dtw_token_timestamps = true;
        cp.dtw_aheads_preset = preset;
    }
    whisper_context *w = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!w) return 0;
    auto *c = new Ctx();
    c->w = w;
    c->dtw = preset != WHISPER_AHEADS_NONE;
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

// [dtwStartMs]/[dtwEndMs] (word transcripts on a DTW context, maxLen 1): see words_by_dtw below.
JNIEXPORT jbyteArray JNICALL Java_se_gendut_klipp_engine_Whisper_nativeTranscribe(
        JNIEnv *env, jclass, jlong h, jfloatArray pcm, jstring lang, jint threads, jint maxLen, jstring prompt, jstring vadModel,
        jint dtwStartMs, jint dtwEndMs, jobject listener) {
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

    const int segs = whisper_full_n_segments(c->w);
    // times in ms: whisper's segment times (hundredths of a second), or for word transcripts the DTW word times
    std::vector<std::pair<int64_t, int64_t>> times(segs);
    for (int i = 0; i < segs; ++i) {
        int64_t t0 = whisper_full_get_segment_t0(c->w, i), t1 = whisper_full_get_segment_t1(c->w, i);
        for (auto &sp : speech) {
            if (t1 > sp.first && t1 <= sp.second + 2) { if (t0 < sp.first) t0 = sp.first; break; }
        }
        times[i] = {t0 * 10, t1 * 10};
    }
    if (c->dtw && maxLen == 1) words_by_dtw(c->w, times, dtwStartMs, dtwEndMs);

    std::string out = "[";
    for (int i = 0; i < segs; ++i) {
        if (i) out += ',';
        out += "{\"t0\":" + std::to_string(times[i].first);
        out += ",\"t1\":" + std::to_string(times[i].second);
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
