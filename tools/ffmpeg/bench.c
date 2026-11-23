/*
 * Speed and correctness check of the ProRes import's FFmpeg part on a computer (build: tools/ffmpeg/build.sh --host):
 * decodes a ProRes file with the same configuration and code as the phone, converts every picture to P010 the way the
 * encoder gets it, and mixes the audio tracks the same way. Prints frames per second.
 *
 *   bench <file.mov> [threads] [--frame N out.p010] [--audio out.s16]
 *   bench <file.mov> --mux out.mov stand-in.hevc
 *
 * --frame writes picture N as raw P010 (compare: ffmpeg -i file.mov -vf "select=eq(n\,N)" -pix_fmt p010le -f rawvideo);
 * --audio writes the audio as raw 16-bit little-endian PCM: every source channel kept, in order (as Klipp's plan,
 * ProResPlan.audioPlan, does on the phone – the player picks each clip's channels).
 *
 * --mux checks the writing side without a phone: an Annex B HEVC stream with an access unit delimiter before each
 * picture and no B-frames (e.g. ffmpeg -i file.mov -c:v libx265 -x265-params aud=1:bframes=0 -f hevc x.hevc) stands
 * in for the phone's encoder; its pictures get the times the ProRes pictures had, its parameter sets are the encoder's
 * configuration, and the audio goes beside them – the intermediate as the phone writes it.
 */
#include <math.h>
#include <time.h>

#include "prores_core.h"

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

/* the next access unit of an Annex B stream (from one AUD, NAL type 35, to the next) */
static int next_au(const uint8_t *d, size_t n, size_t *pos, size_t *start, size_t *len) {
    size_t i = *pos, s = (size_t)-1;
    for (; i + 4 < n; i++) {
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            int type = (d[i + 3] >> 1) & 0x3f;
            size_t at = (i > 0 && d[i - 1] == 0) ? i - 1 : i;
            if (type == 35) { if (s == (size_t)-1) s = at; else { *start = s; *len = at - s; *pos = at; return 1; } }
            i += 2;
        }
    }
    if (s == (size_t)-1) return 0;
    *start = s; *len = n - s; *pos = n;
    return 1;
}

/* the parameter sets (VPS, SPS, PPS) of an access unit, Annex B */
static size_t param_sets(const uint8_t *au, size_t n, uint8_t *out) {
    size_t o = 0;
    for (size_t i = 0; i + 3 < n; i++) {
        if (!(au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1)) continue;
        int type = (au[i + 3] >> 1) & 0x3f;
        size_t e = i + 3;
        while (e + 3 < n && !(au[e] == 0 && au[e + 1] == 0 && (au[e + 2] == 1 || (au[e + 2] == 0 && au[e + 3] == 1)))) e++;
        if (e + 3 >= n) e = n;
        if (type >= 32 && type <= 34) { out[o++] = 0; out[o++] = 0; out[o++] = 0; out[o++] = 1; memcpy(out + o, au + i + 3, e - i - 3); o += e - i - 3; }
        i = e - 1;
    }
    return o;
}

/* an IRAP picture (IDR, CRA, BLA: NAL types 16–21) – a keyframe */
static int is_key(const uint8_t *au, size_t n) {
    for (size_t i = 0; i + 3 < n; i++)
        if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) { int t = (au[i + 3] >> 1) & 0x3f; if (t >= 16 && t <= 21) return 1; }
    return 0;
}

static int mux_check(Kp *k, const char *out, const char *hevc_path) {
    FILE *f = fopen(hevc_path, "rb");
    if (!f) { perror(hevc_path); return 1; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *d = malloc((size_t)n), *csd = malloc(4096);
    if (fread(d, 1, (size_t)n, f) != (size_t)n) { fclose(f); return 1; }
    fclose(f);
    k->out_fd = open(out, O_RDWR | O_CREAT | O_TRUNC, 0644);
    size_t pos = 0, s, len;
    int frames = 0;
    AVCodecParameters *vp = k->ic->streams[k->vidx]->codecpar;
    for (;;) {
        int64_t us = kp_read_frame(k, NULL);
        if (us < -1) { fprintf(stderr, "read: %s\n", k->err); return 1; }
        if (us == -1) break;
        if (!next_au(d, (size_t)n, &pos, &s, &len)) { fprintf(stderr, "the HEVC stream has fewer pictures than the ProRes\n"); return 1; }
        if (!k->header) {
            size_t cl = param_sets(d + s, len, csd);
            /* BT.709 limited, as ProResPlan.colors gives for an unspecified or BT.709 source */
            if (kp_start_mux(k, csd, (int)cl, vp->width, vp->height, 1, 1, 1, 1) < 0) { fprintf(stderr, "start: %s\n", k->err); return 1; }
        }
        if (kp_write_video(k, d + s, (int)len, us, is_key(d + s, len)) < 0) { fprintf(stderr, "write: %s\n", k->err); return 1; }
        frames++;
    }
    if (kp_finish(k) < 0) { fprintf(stderr, "finish: %s\n", k->err); return 1; }
    printf("muxed %d pictures and %lld audio samples into %s\n", frames, (long long)k->audio_out, out);
    free(d); free(csd);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s file.mov [threads] [--frame N out.p010] [--audio out.s16]\n", argv[0]); return 2; }
    int threads = 0, dump_n = -1;
    const char *dump_frame = NULL, *dump_audio = NULL, *mux_out = NULL, *mux_hevc = NULL;
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], "--mux") && i + 2 < argc) { mux_out = argv[i + 1]; mux_hevc = argv[i + 2]; i += 2; continue; }
        if (!strcmp(argv[i], "--frame") && i + 2 < argc) { dump_n = atoi(argv[i + 1]); dump_frame = argv[i + 2]; i += 2; }
        else if (!strcmp(argv[i], "--audio") && i + 1 < argc) dump_audio = argv[++i];
        else threads = atoi(argv[i]);
    }
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror(argv[1]); return 1; }
    char err[512];
    double t0 = now();
    Kp *k = kp_open(fd, err, sizeof err);
    close(fd);
    if (!k) { fprintf(stderr, "open: %s\n", err); return 1; }
    char *info = kp_info(k);
    printf("%s\n", info);
    free(info);
    int streams[KP_MAX_AUDIO], ns = 0, cols = 0;
    for (unsigned i = 0; i < k->ic->nb_streams && ns < KP_MAX_AUDIO; i++) {
        AVCodecParameters *p = k->ic->streams[i]->codecpar;
        if (p->codec_type == AVMEDIA_TYPE_AUDIO && avcodec_find_decoder(p->codec_id)) { streams[ns++] = (int)i; cols += p->ch_layout.nb_channels; }
    }
    /* every channel kept, in order (Klipp's plan, ProResPlan.audioPlan): 4 mono tracks → one 4-channel track */
    static float mix[KP_MAX_OUT * KP_MAX_OUT];
    int out_ch = cols > 0 ? (cols > KP_MAX_OUT ? KP_MAX_OUT : cols) : 1;
    for (int c = 0; c < out_ch; c++) mix[c * cols + c] = 1.0f;
    if (kp_prepare(k, NULL, threads, streams, ns, mix, out_ch, -1) < 0) { fprintf(stderr, "prepare: %s\n", k->err); kp_close(k); return 1; }
    if (mux_out) { int r = mux_check(k, mux_out, mux_hevc); kp_close(k); return r; }
    AVCodecParameters *vp = k->ic->streams[k->vidx]->codecpar;
    int w = vp->width & ~1, h = vp->height & ~1;
    size_t ysz = (size_t)w * h * 2, csz = (size_t)w * (h / 2) * 2;
    uint8_t *y = malloc(ysz), *uv = malloc(csz);
    KpPlanes pl = { .y = y, .u = uv, .v = uv + 2, .ycap = ysz, .ucap = csz, .vcap = csz - 2,
                    .ys = w * 2, .us = w * 2, .up = 4, .vs = w * 2, .vp = 4, .w = w, .h = h, .bits = 10 };
    double t1 = now();
    int n = 0;
    int64_t last = 0;
    for (;;) {
        int64_t r = kp_read_frame(k, &pl);
        if (r == -1) break;
        if (r < -1) { fprintf(stderr, "frame %d: %s\n", n, k->err); kp_close(k); return 1; }
        if (n == dump_n && dump_frame) {
            FILE *f = fopen(dump_frame, "wb");
            if (f) { fwrite(y, 1, ysz, f); fwrite(uv, 1, csz, f); fclose(f); }
        }
        last = r;
        n++;
    }
    /* the rest of the audio (no muxer here: the mixed packets stay in the queue) */
    if (!k->demux_eof) kp_demux_eof(k);
    kp_mix(k, 1);
    double t2 = now();
    int64_t samples = 0;
    FILE *af = dump_audio ? fopen(dump_audio, "wb") : NULL;
    for (int i = 0; i < k->nq; i++) { samples += k->queue[i]->duration; if (af) fwrite(k->queue[i]->data, 1, k->queue[i]->size, af); }
    if (af) fclose(af);
    printf("%d frames %dx%d (last at %.3f s) in %.2f s: %.1f fps (open %.2f s, threads %d)\n", n, w, h, last / 1e6, t2 - t1,
           n / (t2 - t1), t1 - t0, threads);
    printf("audio: %d tracks, %d channels → %d, %lld samples at %d Hz (%.3f s)\n", k->na, cols, out_ch, (long long)samples, k->rate,
           k->rate ? (double)samples / k->rate : 0.0);
    free(y);
    free(uv);
    kp_close(k);
    return 0;
}
