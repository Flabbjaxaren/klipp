/*
 * Speed and correctness check of the ProRes import's FFmpeg part on a computer (build: tools/ffmpeg/build.sh --host):
 * decodes a ProRes file with the same configuration and code as the phone, converts every picture to P010 the way the
 * encoder gets it, and mixes the audio tracks the same way. Prints frames per second.
 *
 *   bench <file.mov> [threads] [--frame N out.p010] [--audio out.s16]
 *
 * --frame writes picture N as raw P010 (compare: ffmpeg -i file.mov -vf "select=eq(n\,N)" -pix_fmt p010le -f rawvideo);
 * --audio writes the mixed audio as raw 16-bit little-endian PCM. The mix here: two mono tracks → left/right, otherwise
 * every channel centered at 1/√n (Klipp's plan, ProResPlan.audioPlan, decides on the phone).
 */
#include <math.h>
#include <time.h>

#include "prores_core.h"

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s file.mov [threads] [--frame N out.p010] [--audio out.s16]\n", argv[0]); return 2; }
    int threads = 0, dump_n = -1;
    const char *dump_frame = NULL, *dump_audio = NULL;
    for (int i = 2; i < argc; i++) {
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
    float mix[2 * 64] = {0};
    int out_ch = cols == 1 ? 1 : 2;
    if (cols == 2) { mix[0] = 1; mix[cols + 1] = 1; }
    else for (int c = 0; c < cols; c++) for (int o = 0; o < out_ch; o++) mix[o * cols + c] = 1.0f / sqrtf((float)cols);
    if (kp_prepare(k, NULL, threads, streams, ns, mix, out_ch, -1) < 0) { fprintf(stderr, "prepare: %s\n", k->err); kp_close(k); return 1; }
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
