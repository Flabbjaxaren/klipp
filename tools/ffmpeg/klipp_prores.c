/*
 * libklipp_prores.so: the JNI side of Klipp's ProRes import (se.gendut.klipp.engine.ProResNative). Errors come back
 * to Kotlin as java.io.IOException with FFmpeg's or Klipp's message – never a crash. See prores_core.h and build.sh.
 */
#include <jni.h>
#include <android/log.h>

#include "prores_core.h"

#define TAG "klipp-prores"

static void throw_io(JNIEnv *env, const char *msg) {
    jclass c = (*env)->FindClass(env, "java/io/IOException");
    if (c) (*env)->ThrowNew(env, c, msg && *msg ? msg : "ProRes: unknown error");
}

static Kp *of(jlong h) { return (Kp *)(intptr_t)h; }

/* FFmpeg's warnings and errors in logcat (the trace report reads Klipp's own notes; this is for adb) */
static void log_cb(void *avcl, int level, const char *fmt, va_list vl) {
    (void)avcl;
    if (level > AV_LOG_WARNING) return;
    __android_log_vprint(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, fmt, vl);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    av_log_set_callback(log_cb);
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL Java_se_gendut_klipp_engine_ProResNative_version(JNIEnv *env, jclass cls) {
    (void)cls;
    char v[160];
    snprintf(v, sizeof v, "FFmpeg %s (avcodec %u, avformat %u)", av_version_info(), avcodec_version(), avformat_version());
    return (*env)->NewStringUTF(env, v);
}

JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_ProResNative_open(JNIEnv *env, jclass cls, jint fd) {
    (void)cls;
    char err[512] = {0};
    Kp *k = kp_open(fd, err, sizeof err);
    if (!k) { throw_io(env, err); return 0; }
    return (jlong)(intptr_t)k;
}

JNIEXPORT jstring JNICALL Java_se_gendut_klipp_engine_ProResNative_info(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    char *s = kp_info(of(h));
    if (!s) { throw_io(env, "out of memory"); return NULL; }
    jstring r = (*env)->NewStringUTF(env, s);
    free(s);
    return r;
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_ProResNative_prepare(JNIEnv *env, jclass cls, jlong h, jstring out, jint threads,
                                                                       jintArray streams, jfloatArray mix, jint outChannels, jint copyStream) {
    (void)cls;
    Kp *k = of(h);
    const char *path = (*env)->GetStringUTFChars(env, out, NULL);
    jsize ns = streams ? (*env)->GetArrayLength(env, streams) : 0;
    jsize nm = mix ? (*env)->GetArrayLength(env, mix) : 0;
    jint *s = ns ? (*env)->GetIntArrayElements(env, streams, NULL) : NULL;
    jfloat *m = nm ? (*env)->GetFloatArrayElements(env, mix, NULL) : NULL;
    int ret;
    /* the matrix must cover every channel of every stream */
    int cols = 0;
    for (jsize i = 0; i < ns; i++) {
        int si = s[i];
        if (si >= 0 && (unsigned)si < k->ic->nb_streams) cols += k->ic->streams[si]->codecpar->ch_layout.nb_channels > 0 ? k->ic->streams[si]->codecpar->ch_layout.nb_channels : 1;
    }
    if (ns > 0 && nm != (jsize)(cols * outChannels)) ret = kp_failf(k, "the audio plan doesn't match the tracks");
    else ret = kp_prepare(k, path, threads, (const int *)s, ns, (const float *)m, outChannels, copyStream);
    if (s) (*env)->ReleaseIntArrayElements(env, streams, s, JNI_ABORT);
    if (m) (*env)->ReleaseFloatArrayElements(env, mix, m, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, out, path);
    if (ret < 0) throw_io(env, k->err);
}

static int planes_of(JNIEnv *env, KpPlanes *p, jobject y, jint ys, jobject u, jint us, jint up, jobject v, jint vs, jint vp, jint w, jint hh, jint bits) {
    memset(p, 0, sizeof *p);
    if (!y || !u || !v) return 0;
    p->y = (*env)->GetDirectBufferAddress(env, y);
    p->u = (*env)->GetDirectBufferAddress(env, u);
    p->v = (*env)->GetDirectBufferAddress(env, v);
    jlong yc = (*env)->GetDirectBufferCapacity(env, y), uc = (*env)->GetDirectBufferCapacity(env, u), vc = (*env)->GetDirectBufferCapacity(env, v);
    if (!p->y || !p->u || !p->v || yc < 0 || uc < 0 || vc < 0) return 0;
    p->ycap = (size_t)yc; p->ucap = (size_t)uc; p->vcap = (size_t)vc;
    p->ys = ys; p->us = us; p->up = up; p->vs = vs; p->vp = vp; p->w = w; p->h = hh; p->bits = bits;
    return 1;
}

/*
 * The next picture into the encoder's input planes (direct buffers: Image planes or the input buffer's views). Returns
 * its time in µs, −1 after the last one.
 */
JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_ProResNative_readFrame(JNIEnv *env, jclass cls, jlong h,
        jobject y, jint ys, jobject u, jint us, jint up, jobject v, jint vs, jint vp, jint w, jint hh, jint bits) {
    (void)cls;
    Kp *k = of(h);
    KpPlanes p;
    if (!planes_of(env, &p, y, ys, u, us, up, v, vs, vp, w, hh, bits)) { throw_io(env, "the encoder's buffers can't be reached"); return -1; }
    int64_t r = kp_read_frame(k, &p);
    if (r < -1) { throw_io(env, k->err); return -1; }
    return (jlong)r;
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_ProResNative_startMux(JNIEnv *env, jclass cls, jlong h, jbyteArray csd,
        jint w, jint hh, jint primaries, jint trc, jint space, jint range) {
    (void)cls;
    Kp *k = of(h);
    jsize n = (*env)->GetArrayLength(env, csd);
    jbyte *b = (*env)->GetByteArrayElements(env, csd, NULL);
    int ret = kp_start_mux(k, (const uint8_t *)b, n, w, hh, primaries, trc, space, range);
    (*env)->ReleaseByteArrayElements(env, csd, b, JNI_ABORT);
    if (ret < 0) throw_io(env, k->err);
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_ProResNative_writeVideo(JNIEnv *env, jclass cls, jlong h, jobject buf,
        jint offset, jint size, jlong ptsUs, jboolean key) {
    (void)cls;
    Kp *k = of(h);
    uint8_t *d = (*env)->GetDirectBufferAddress(env, buf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buf);
    if (!d || offset < 0 || size < 0 || (jlong)offset + size > cap) { throw_io(env, "bad encoder output buffer"); return; }
    int ret = kp_write_video(k, d + offset, size, ptsUs, key == JNI_TRUE);
    if (ret < 0) throw_io(env, k->err);
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_ProResNative_finish(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Kp *k = of(h);
    if (kp_finish(k) < 0) throw_io(env, k->err);
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_ProResNative_close(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    kp_close(of(h));
}
