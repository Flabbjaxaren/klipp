/*
 * Klipp's ProRes import, the part around FFmpeg (GPL-3.0-or-later, like the rest of Klipp's native code; FFmpeg itself
 * is LGPL-2.1-or-later, see build.sh): reads a ProRes MOV from a file descriptor, decodes the picture with FFmpeg's
 * ProRes decoder into the planes of the phone's video encoder (10-bit P010 or 8-bit 4:2:0), mixes the camera's audio
 * tracks into one, and writes the intermediate MOV (the encoder's HEVC + the mixed PCM) with FFmpeg's MOV muxer.
 * Shared by the JNI library (klipp_prores.c) and the speed check on a computer (bench.c).
 */
#ifndef KLIPP_PRORES_CORE_H
#define KLIPP_PRORES_CORE_H

#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>

#define KP_MAX_AUDIO 16
/* mixed audio is written in blocks of this many sample frames */
#define KP_BLOCK 1024
/* at most this much silence is put before an audio track that starts late (s) */
#define KP_MAX_LEAD 10

typedef struct {
    int stream;          /* index in the input */
    AVCodecContext *dec;
    int channels;
    int first;           /* the column of its first channel in the mix matrix */
    float *fifo;         /* decoded, interleaved, not yet mixed */
    int len, cap;        /* in sample frames */
    int started;         /* its start offset has been applied */
    int64_t skip;        /* samples still to drop (a track that starts before the picture's start) */
} KpAudio;

typedef struct {
    uint8_t *y, *u, *v;
    size_t ycap, ucap, vcap;
    int ys, us, up, vs, vp; /* row strides and pixel strides in bytes */
    int w, h;               /* the encoder's picture (even) */
    int bits;               /* 10: 16-bit samples, value in the top 10 bits (P010); 8: bytes */
} KpPlanes;

typedef struct {
    int fd;
    int64_t size, pos;
    AVFormatContext *ic;
    AVIOContext *in_io;
    int vidx;
    AVCodecContext *vdec;
    AVFrame *frame, *aframe;
    AVPacket *pkt;
    int pkt_pending;       /* a video packet the decoder refused for now: sent again first */
    int good, bad;         /* pictures decoded / damaged ones skipped */
    int demux_eof, video_eof;
    int64_t t0;            /* the file's start (µs): the intermediate's times count from it */

    KpAudio a[KP_MAX_AUDIO];
    int na;
    int out_ch, cols, rate;
    float *mix;            /* out_ch × cols gains */
    int copy_stream;       /* an audio stream copied as it is (not PCM, nothing to mix), −1 = none */
    int64_t audio_out;     /* sample frames written */

    int out_fd;
    AVIOContext *out_io;
    AVFormatContext *oc;
    int ovid, oaud;
    int header;
    AVRational vtb_src;    /* the source picture's time base (the intermediate's frames keep their exact times) */
    int64_t last_vdts;
    int64_t frame_dur;     /* in vtb_src */
    AVPacket **queue;      /* audio packets made before the muxer could start (it waits for the encoder's config) */
    int nq, capq;

    char err[512];
} Kp;

static int kp_fail(Kp *k, const char *what, int ret) {
    char e[128];
    av_strerror(ret, e, sizeof e);
    snprintf(k->err, sizeof k->err, "%s: %s", what, e);
    return ret < 0 ? ret : AVERROR(EINVAL);
}

static int kp_failf(Kp *k, const char *msg) {
    snprintf(k->err, sizeof k->err, "%s", msg);
    return AVERROR(EINVAL);
}

/* ------------------------------------------------------------------ input through the descriptor (pread) */

static int kp_in_read(void *op, uint8_t *buf, int n) {
    Kp *k = op;
    ssize_t r;
    do r = pread(k->fd, buf, (size_t)n, k->pos); while (r < 0 && errno == EINTR);
    if (r < 0) return AVERROR(errno);
    if (r == 0) return AVERROR_EOF;
    k->pos += r;
    return (int)r;
}

static int64_t kp_in_seek(void *op, int64_t off, int whence) {
    Kp *k = op;
    switch (whence & ~AVSEEK_FORCE) {
    case AVSEEK_SIZE: return k->size;
    case SEEK_SET: k->pos = off; break;
    case SEEK_CUR: k->pos += off; break;
    case SEEK_END: k->pos = k->size + off; break;
    default: return AVERROR(EINVAL);
    }
    return k->pos;
}

/* ------------------------------------------------------------------ output file */

static int kp_out_write(void *op, const uint8_t *buf, int n) {
    Kp *k = op;
    int done = 0;
    while (done < n) {
        ssize_t r = write(k->out_fd, buf + done, (size_t)(n - done));
        if (r < 0) { if (errno == EINTR) continue; return AVERROR(errno); }
        done += (int)r;
    }
    return n;
}

static int64_t kp_out_seek(void *op, int64_t off, int whence) {
    Kp *k = op;
    if (whence & AVSEEK_SIZE) {
        struct stat st;
        return fstat(k->out_fd, &st) == 0 ? st.st_size : AVERROR(errno);
    }
    off_t r = lseek(k->out_fd, off, whence & ~AVSEEK_FORCE);
    return r < 0 ? AVERROR(errno) : r;
}

/* ------------------------------------------------------------------ open / close */

static void kp_close(Kp *k) {
    if (!k) return;
    for (int i = 0; i < k->na; i++) { avcodec_free_context(&k->a[i].dec); av_free(k->a[i].fifo); }
    avcodec_free_context(&k->vdec);
    av_frame_free(&k->frame);
    av_frame_free(&k->aframe);
    av_packet_free(&k->pkt);
    for (int i = 0; i < k->nq; i++) av_packet_free(&k->queue[i]);
    av_free(k->queue);
    av_free(k->mix);
    if (k->oc) avformat_free_context(k->oc);
    if (k->out_io) { av_free(k->out_io->buffer); avio_context_free(&k->out_io); }
    if (k->out_fd >= 0) close(k->out_fd);
    if (k->ic) avformat_close_input(&k->ic);
    if (k->in_io) { av_free(k->in_io->buffer); avio_context_free(&k->in_io); }
    if (k->fd >= 0) close(k->fd);
    free(k);
}

/* Opens the container (the descriptor is duplicated; the caller keeps its own). Errors: NULL with the message in err. */
static Kp *kp_open(int fd, char *err, size_t errlen) {
    Kp *k = calloc(1, sizeof *k);
    if (!k) { snprintf(err, errlen, "out of memory"); return NULL; }
    k->fd = -1; k->out_fd = -1; k->vidx = -1; k->copy_stream = -1; k->ovid = -1; k->oaud = -1;
    k->last_vdts = INT64_MIN;
    int ret;
    k->fd = dup(fd);
    struct stat st;
    if (k->fd < 0 || fstat(k->fd, &st) != 0) { snprintf(err, errlen, "the file can't be read (%s)", strerror(errno)); kp_close(k); return NULL; }
    k->size = st.st_size;
    uint8_t *buf = av_malloc(1 << 16);
    k->in_io = buf ? avio_alloc_context(buf, 1 << 16, 0, k, kp_in_read, NULL, kp_in_seek) : NULL;
    k->ic = avformat_alloc_context();
    if (!k->in_io || !k->ic) { if (!k->in_io) av_free(buf); snprintf(err, errlen, "out of memory"); kp_close(k); return NULL; }
    k->ic->pb = k->in_io;
    k->ic->flags |= AVFMT_FLAG_CUSTOM_IO;
    if ((ret = avformat_open_input(&k->ic, NULL, NULL, NULL)) < 0) { kp_fail(k, "not a file FFmpeg can read", ret); goto fail; }
    if ((ret = avformat_find_stream_info(k->ic, NULL)) < 0) { kp_fail(k, "the file's tracks can't be read", ret); goto fail; }
    k->vidx = av_find_best_stream(k->ic, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    k->t0 = k->ic->start_time != AV_NOPTS_VALUE ? k->ic->start_time : 0;
    return k;
fail:
    snprintf(err, errlen, "%s", k->err);
    kp_close(k);
    return NULL;
}

/* ------------------------------------------------------------------ the file's description (JSON) */

typedef struct { char *s; size_t len, cap; } KpStr;

static void kp_put(KpStr *b, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void kp_put(KpStr *b, const char *fmt, ...) {
    for (;;) {
        va_list ap;
        va_start(ap, fmt);
        size_t room = b->cap - b->len;
        int n = vsnprintf(b->s ? b->s + b->len : NULL, b->s ? room : 0, fmt, ap);
        va_end(ap);
        if (n < 0) return;
        if (b->s && (size_t)n < room) { b->len += (size_t)n; return; }
        size_t cap = (b->cap + (size_t)n + 1) * 2;
        char *s = realloc(b->s, cap);
        if (!s) return;
        b->s = s; b->cap = cap;
    }
}

/* A JSON string (codec names, fourccs – nothing that needs more than this escaping). */
static void kp_put_str(KpStr *b, const char *v) {
    kp_put(b, "\"");
    for (const char *p = v ? v : ""; *p; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == '"' || c == '\\') kp_put(b, "\\%c", c);
        else if (c < 0x20) kp_put(b, "\\u%04x", c);
        else kp_put(b, "%c", c);
    }
    kp_put(b, "\"");
}

/* What Klipp needs to plan the intermediate: the picture's codec, size, rate, color and the audio tracks. Caller frees. */
static char *kp_info(Kp *k) {
    KpStr b = {0};
    AVFormatContext *ic = k->ic;
    kp_put(&b, "{\"format\":");
    kp_put_str(&b, ic->iformat ? ic->iformat->name : "");
    kp_put(&b, ",\"durationUs\":%lld,\"startUs\":%lld", (long long)(ic->duration != AV_NOPTS_VALUE ? ic->duration : -1), (long long)k->t0);
    if (k->vidx >= 0) {
        AVStream *st = ic->streams[k->vidx];
        AVCodecParameters *p = st->codecpar;
        char tag[AV_FOURCC_MAX_STRING_SIZE] = {0};
        av_fourcc_make_string(tag, p->codec_tag);
        AVRational fr = st->avg_frame_rate.num > 0 ? st->avg_frame_rate : st->r_frame_rate;
        double rot = 0;
        const AVPacketSideData *sd = av_packet_side_data_get(p->coded_side_data, p->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
        if (sd && sd->size >= 9 * 4) rot = av_display_rotation_get((const int32_t *)sd->data);
        const char *pix = av_get_pix_fmt_name((enum AVPixelFormat)p->format);
        kp_put(&b, ",\"video\":{\"index\":%d,\"codec\":", k->vidx);
        kp_put_str(&b, avcodec_get_name(p->codec_id));
        kp_put(&b, ",\"tag\":");
        kp_put_str(&b, tag);
        kp_put(&b, ",\"decodable\":%s,\"width\":%d,\"height\":%d,\"fpsNum\":%d,\"fpsDen\":%d,\"pixFmt\":",
               avcodec_find_decoder(p->codec_id) ? "true" : "false", p->width, p->height, fr.num, fr.den);
        kp_put_str(&b, pix ? pix : "");
        kp_put(&b, ",\"bits\":%d,\"primaries\":%d,\"transfer\":%d,\"matrix\":%d,\"range\":%d,\"fieldOrder\":%d,\"rotation\":%.0f,\"frames\":%lld,\"bitRate\":%lld}",
               p->bits_per_raw_sample, p->color_primaries, p->color_trc, p->color_space, p->color_range, p->field_order, rot,
               (long long)st->nb_frames, (long long)p->bit_rate);
    }
    kp_put(&b, ",\"audio\":[");
    int first = 1;
    for (unsigned i = 0; i < ic->nb_streams; i++) {
        AVCodecParameters *p = ic->streams[i]->codecpar;
        if (p->codec_type != AVMEDIA_TYPE_AUDIO) continue;
        kp_put(&b, "%s{\"index\":%u,\"codec\":", first ? "" : ",", i);
        kp_put_str(&b, avcodec_get_name(p->codec_id));
        kp_put(&b, ",\"channels\":%d,\"rate\":%d,\"decodable\":%s}", p->ch_layout.nb_channels, p->sample_rate,
               avcodec_find_decoder(p->codec_id) ? "true" : "false");
        first = 0;
    }
    kp_put(&b, "]}");
    return b.s;
}

/* ------------------------------------------------------------------ decoding setup */

/*
 * Opens the decoders and the output file. [streams]: the audio streams to mix (their channels are the matrix's columns,
 * in order); [mix]: out_ch rows × all those channels; [copy_stream]: an audio stream to copy instead (−1 = none).
 */
static int kp_prepare(Kp *k, const char *out_path, int threads, const int *streams, int nstreams, const float *mix, int out_ch, int copy_stream) {
    int ret;
    if (k->vidx < 0) return kp_failf(k, "the file has no picture");
    AVStream *vs = k->ic->streams[k->vidx];
    if (vs->codecpar->codec_id != AV_CODEC_ID_PRORES) return kp_failf(k, "the picture is not ProRes");
    const AVCodec *dec = avcodec_find_decoder(AV_CODEC_ID_PRORES);
    if (!dec) return kp_failf(k, "no ProRes decoder in this build");
    k->vdec = avcodec_alloc_context3(dec);
    if (!k->vdec) return AVERROR(ENOMEM);
    if ((ret = avcodec_parameters_to_context(k->vdec, vs->codecpar)) < 0) return kp_fail(k, "ProRes decoder", ret);
    k->vdec->pkt_timebase = vs->time_base;
    /* every core: whole frames in parallel (ProRes frames are independent) and slices within each */
    k->vdec->thread_count = threads > 0 ? threads : 0;
    k->vdec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    if ((ret = avcodec_open2(k->vdec, dec, NULL)) < 0) return kp_fail(k, "ProRes decoder", ret);
    k->vtb_src = vs->time_base;
    AVRational fr = vs->avg_frame_rate.num > 0 ? vs->avg_frame_rate : vs->r_frame_rate;
    k->frame_dur = fr.num > 0 ? av_rescale_q(1, av_inv_q(fr), vs->time_base) : 0;

    if (nstreams > KP_MAX_AUDIO) return kp_failf(k, "too many audio tracks");
    int cols = 0;
    for (int i = 0; i < nstreams; i++) {
        int s = streams[i];
        if (s < 0 || (unsigned)s >= k->ic->nb_streams || k->ic->streams[s]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) return kp_failf(k, "bad audio track");
        AVCodecParameters *p = k->ic->streams[s]->codecpar;
        const AVCodec *ad = avcodec_find_decoder(p->codec_id);
        if (!ad) return kp_failf(k, "an audio track can't be decoded");
        KpAudio *a = &k->a[k->na++];
        a->stream = s;
        a->dec = avcodec_alloc_context3(ad);
        if (!a->dec) return AVERROR(ENOMEM);
        if ((ret = avcodec_parameters_to_context(a->dec, p)) < 0 || (ret = avcodec_open2(a->dec, ad, NULL)) < 0) return kp_fail(k, "audio decoder", ret);
        a->dec->pkt_timebase = k->ic->streams[s]->time_base;
        a->channels = p->ch_layout.nb_channels > 0 ? p->ch_layout.nb_channels : 1;
        a->first = cols;
        cols += a->channels;
        if (i == 0) k->rate = p->sample_rate;
        else if (p->sample_rate != k->rate) return kp_failf(k, "the audio tracks have different sample rates");
    }
    if (k->na > 0) {
        if (out_ch < 1 || out_ch > 2) return kp_failf(k, "bad audio plan");
        k->out_ch = out_ch;
        k->cols = cols;
        k->mix = av_malloc_array((size_t)out_ch * (size_t)cols, sizeof(float));
        if (!k->mix) return AVERROR(ENOMEM);
        memcpy(k->mix, mix, (size_t)out_ch * (size_t)cols * sizeof(float));
    }
    k->copy_stream = k->na == 0 ? copy_stream : -1;
    /* the streams nobody reads are skipped by the demuxer */
    for (unsigned i = 0; i < k->ic->nb_streams; i++) {
        int used = (int)i == k->vidx || (int)i == k->copy_stream;
        for (int j = 0; j < k->na; j++) used |= k->a[j].stream == (int)i;
        if (!used) k->ic->streams[i]->discard = AVDISCARD_ALL;
    }
    k->frame = av_frame_alloc();
    k->aframe = av_frame_alloc();
    k->pkt = av_packet_alloc();
    if (!k->frame || !k->aframe || !k->pkt) return AVERROR(ENOMEM);
    if (out_path) {
        k->out_fd = open(out_path, O_RDWR | O_CREAT | O_TRUNC, 0644);
        if (k->out_fd < 0) { snprintf(k->err, sizeof k->err, "the intermediate file can't be written (%s)", strerror(errno)); return AVERROR(errno); }
    }
    return 0;
}

/* ------------------------------------------------------------------ picture → the encoder's planes */

static inline void kp_store(uint8_t *dst, int bits, int depth, unsigned v) {
    if (bits == 10) *(uint16_t *)dst = (uint16_t)(v << (16 - depth));
    else { unsigned s = depth - 8; unsigned o = (v + (1u << (s - 1))) >> s; *dst = (uint8_t)(o > 255 ? 255 : o); }
}

/* bilinear resampling of one plane row into [row] (source depth), centers aligned */
static void kp_resample_row(const AVFrame *f, int plane, int sw, int sh, int dw, int dh, int y, uint16_t *row) {
    int64_t fy = (((int64_t)(2 * y + 1) * sh << 16) / (2 * dh)) - (1 << 15);
    if (fy < 0) fy = 0;
    int y0 = (int)(fy >> 16), wy = (int)(fy & 0xffff);
    int y1 = y0 + 1 < sh ? y0 + 1 : sh - 1;
    if (y0 >= sh) { y0 = y1 = sh - 1; wy = 0; }
    const uint16_t *r0 = (const uint16_t *)(f->data[plane] + (size_t)y0 * f->linesize[plane]);
    const uint16_t *r1 = (const uint16_t *)(f->data[plane] + (size_t)y1 * f->linesize[plane]);
    for (int x = 0; x < dw; x++) {
        int64_t fx = (((int64_t)(2 * x + 1) * sw << 16) / (2 * dw)) - (1 << 15);
        if (fx < 0) fx = 0;
        int x0 = (int)(fx >> 16), wx = (int)(fx & 0xffff);
        int x1 = x0 + 1 < sw ? x0 + 1 : sw - 1;
        if (x0 >= sw) { x0 = x1 = sw - 1; wx = 0; }
        int64_t top = (int64_t)r0[x0] * (65536 - wx) + (int64_t)r0[x1] * wx;
        int64_t bot = (int64_t)r1[x0] * (65536 - wx) + (int64_t)r1[x1] * wx;
        row[x] = (uint16_t)(((top >> 16) * (65536 - wy) + (bot >> 16) * wy + (1 << 15)) >> 16);
    }
}

static int kp_fits(size_t cap, int rows, int stride, int cols, int pix, int bytes) {
    if (rows <= 0 || cols <= 0) return 1;
    return (size_t)(rows - 1) * (size_t)stride + (size_t)(cols - 1) * (size_t)pix + (size_t)bytes <= cap;
}

/*
 * A decoded picture (planar YUV, 4:2:2 or 4:4:4, 10–16 bits) into 4:2:0 planes: the same size (odd edges cropped) by
 * shifting and averaging the chroma; another size bilinearly (only when the encoder can't take the source's size).
 */
static int kp_convert(Kp *k, const AVFrame *f, const KpPlanes *p) {
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    if (!d || !(d->flags & AV_PIX_FMT_FLAG_PLANAR) || (d->flags & AV_PIX_FMT_FLAG_RGB) || d->nb_components < 3 ||
        d->comp[0].depth <= 8 || d->comp[0].depth > 16 || d->log2_chroma_w > 1 || d->log2_chroma_h > 1)
        return kp_failf(k, "unsupported decoded picture format");
    int depth = d->comp[0].depth, cw = d->log2_chroma_w, ch = d->log2_chroma_h;
    int w = p->w & ~1, h = p->h & ~1, bytes = p->bits == 10 ? 2 : 1;
    if (w <= 0 || h <= 0) return kp_failf(k, "bad picture size");
    if (!kp_fits(p->ycap, h, p->ys, w, bytes, bytes) || !kp_fits(p->ucap, h / 2, p->us, w / 2, p->up, bytes) ||
        !kp_fits(p->vcap, h / 2, p->vs, w / 2, p->vp, bytes))
        return kp_failf(k, "the encoder's buffer is too small");
    int same = w <= f->width && h <= f->height && f->width - w <= 1 && f->height - h <= 1;
    if (same) {
        int sh16 = 16 - depth;
        for (int y = 0; y < h; y++) {
            const uint16_t *s = (const uint16_t *)(f->data[0] + (size_t)y * f->linesize[0]);
            uint8_t *o = p->y + (size_t)y * p->ys;
            if (p->bits == 10) { uint16_t *o16 = (uint16_t *)o; for (int x = 0; x < w; x++) o16[x] = (uint16_t)(s[x] << sh16); }
            else for (int x = 0; x < w; x++) kp_store(o + x, 8, depth, s[x]);
        }
        int p010 = p->bits == 10 && p->up == 4 && p->vp == 4 && p->v == p->u + 2;
        for (int j = 0; j < h / 2; j++) {
            int r0 = ch ? j : 2 * j, r1 = ch ? j : 2 * j + 1;
            const uint16_t *u0 = (const uint16_t *)(f->data[1] + (size_t)r0 * f->linesize[1]);
            const uint16_t *u1 = (const uint16_t *)(f->data[1] + (size_t)r1 * f->linesize[1]);
            const uint16_t *v0 = (const uint16_t *)(f->data[2] + (size_t)r0 * f->linesize[2]);
            const uint16_t *v1 = (const uint16_t *)(f->data[2] + (size_t)r1 * f->linesize[2]);
            if (p010) {
                /* the usual case: 4:2:2 → interleaved UV, 16-bit */
                uint16_t *o = (uint16_t *)(p->u + (size_t)j * p->us);
                if (cw) for (int i = 0; i < w / 2; i++) {
                    o[2 * i] = (uint16_t)(((u0[i] + u1[i] + 1) >> 1) << sh16);
                    o[2 * i + 1] = (uint16_t)(((v0[i] + v1[i] + 1) >> 1) << sh16);
                }
                else for (int i = 0; i < w / 2; i++) {
                    o[2 * i] = (uint16_t)(((u0[2 * i] + u0[2 * i + 1] + u1[2 * i] + u1[2 * i + 1] + 2) >> 2) << sh16);
                    o[2 * i + 1] = (uint16_t)(((v0[2 * i] + v0[2 * i + 1] + v1[2 * i] + v1[2 * i + 1] + 2) >> 2) << sh16);
                }
                continue;
            }
            uint8_t *ou = p->u + (size_t)j * p->us, *ov = p->v + (size_t)j * p->vs;
            for (int i = 0; i < w / 2; i++) {
                unsigned su, sv;
                if (cw) { su = (u0[i] + u1[i] + 1) >> 1; sv = (v0[i] + v1[i] + 1) >> 1; }
                else {
                    su = (u0[2 * i] + u0[2 * i + 1] + u1[2 * i] + u1[2 * i + 1] + 2) >> 2;
                    sv = (v0[2 * i] + v0[2 * i + 1] + v1[2 * i] + v1[2 * i + 1] + 2) >> 2;
                }
                kp_store(ou + (size_t)i * p->up, p->bits, depth, su);
                kp_store(ov + (size_t)i * p->vp, p->bits, depth, sv);
            }
        }
        return 0;
    }
    /* another size: each plane resampled to its place in 4:2:0 */
    uint16_t *row = av_malloc_array((size_t)(w > 2 ? w : 2), sizeof(uint16_t));
    if (!row) return AVERROR(ENOMEM);
    int sw = f->width, sh = f->height, csw = AV_CEIL_RSHIFT(sw, cw), csh = AV_CEIL_RSHIFT(sh, ch);
    for (int y = 0; y < h; y++) {
        kp_resample_row(f, 0, sw, sh, w, h, y, row);
        uint8_t *o = p->y + (size_t)y * p->ys;
        for (int x = 0; x < w; x++) kp_store(o + (size_t)x * bytes, p->bits, depth, row[x]);
    }
    for (int pl = 1; pl <= 2; pl++) {
        uint8_t *base = pl == 1 ? p->u : p->v;
        int stride = pl == 1 ? p->us : p->vs, pix = pl == 1 ? p->up : p->vp;
        for (int j = 0; j < h / 2; j++) {
            kp_resample_row(f, pl, csw, csh, w / 2, h / 2, j, row);
            uint8_t *o = base + (size_t)j * stride;
            for (int i = 0; i < w / 2; i++) kp_store(o + (size_t)i * pix, p->bits, depth, row[i]);
        }
    }
    av_free(row);
    return 0;
}

/* ------------------------------------------------------------------ audio: decode, line up, mix */

static int kp_emit_packet(Kp *k, AVPacket *pkt, int stream);

static int kp_fifo_grow(KpAudio *a, int frames) {
    if (a->len + frames <= a->cap) return 0;
    int cap = (a->len + frames) * 2;
    float *f = av_realloc_array(a->fifo, (size_t)cap * (size_t)a->channels, sizeof(float));
    if (!f) return AVERROR(ENOMEM);
    a->fifo = f; a->cap = cap;
    return 0;
}

static float kp_sample(const AVFrame *f, int planar, enum AVSampleFormat fmt, int ch, int channels, int i) {
    const uint8_t *base = planar ? f->extended_data[ch] : f->extended_data[0];
    int idx = planar ? i : i * channels + ch;
    switch (fmt) {
    case AV_SAMPLE_FMT_U8: return (((const uint8_t *)base)[idx] - 128) / 128.0f;
    case AV_SAMPLE_FMT_S16: return ((const int16_t *)base)[idx] / 32768.0f;
    case AV_SAMPLE_FMT_S32: return (float)(((const int32_t *)base)[idx] / 2147483648.0);
    case AV_SAMPLE_FMT_FLT: return ((const float *)base)[idx];
    case AV_SAMPLE_FMT_DBL: return (float)((const double *)base)[idx];
    default: return 0.0f;
    }
}

static int kp_audio_frame(Kp *k, KpAudio *a, const AVFrame *f) {
    int n = f->nb_samples, ret;
    if (n <= 0) return 0;
    if (!a->started) {
        a->started = 1;
        int64_t ts = f->best_effort_timestamp != AV_NOPTS_VALUE ? f->best_effort_timestamp : f->pts;
        if (ts != AV_NOPTS_VALUE) {
            int64_t us = av_rescale_q(ts, k->ic->streams[a->stream]->time_base, AV_TIME_BASE_Q) - k->t0;
            int64_t off = av_rescale(us, k->rate, AV_TIME_BASE);
            if (off > 0) {
                /* a track that starts after the file's start: silence before it */
                if (off > (int64_t)k->rate * KP_MAX_LEAD) off = (int64_t)k->rate * KP_MAX_LEAD;
                if ((ret = kp_fifo_grow(a, (int)off)) < 0) return ret;
                memset(a->fifo + (size_t)a->len * a->channels, 0, (size_t)off * a->channels * sizeof(float));
                a->len += (int)off;
            } else if (off < 0) a->skip = -off;
        }
    }
    int from = 0;
    if (a->skip > 0) { int s = a->skip > n ? n : (int)a->skip; from = s; a->skip -= s; }
    if (from >= n) return 0;
    if ((ret = kp_fifo_grow(a, n - from)) < 0) return ret;
    enum AVSampleFormat fmt = (enum AVSampleFormat)f->format;
    int planar = av_sample_fmt_is_planar(fmt);
    enum AVSampleFormat packed = av_get_packed_sample_fmt(fmt);
    int fch = f->ch_layout.nb_channels > 0 ? f->ch_layout.nb_channels : a->channels;
    float *dst = a->fifo + (size_t)a->len * a->channels;
    for (int i = from; i < n; i++)
        for (int c = 0; c < a->channels; c++)
            *dst++ = c < fch ? kp_sample(f, planar, packed, c, fch, i) : 0.0f;
    a->len += n - from;
    return 0;
}

/* Mixes what all tracks have (at the end: everything, the shorter tracks padded with silence) into 16-bit packets. */
static int kp_mix(Kp *k, int final) {
    if (k->na == 0) return 0;
    for (;;) {
        int lo = INT32_MAX, hi = 0;
        for (int i = 0; i < k->na; i++) { if (k->a[i].len < lo) lo = k->a[i].len; if (k->a[i].len > hi) hi = k->a[i].len; }
        int n;
        if (!final) { if (lo < KP_BLOCK) return 0; n = KP_BLOCK; }
        else { if (hi == 0) return 0; n = hi < KP_BLOCK ? hi : KP_BLOCK; }
        AVPacket *pkt = av_packet_alloc();
        if (!pkt) return AVERROR(ENOMEM);
        int ret = av_new_packet(pkt, n * k->out_ch * 2);
        if (ret < 0) { av_packet_free(&pkt); return ret; }
        int16_t *out = (int16_t *)pkt->data;
        for (int s = 0; s < n; s++)
            for (int o = 0; o < k->out_ch; o++) {
                float acc = 0.0f;
                for (int i = 0; i < k->na; i++) {
                    KpAudio *a = &k->a[i];
                    if (s >= a->len) continue;
                    for (int c = 0; c < a->channels; c++) {
                        float g = k->mix[o * k->cols + a->first + c];
                        if (g != 0.0f) acc += g * a->fifo[(size_t)s * a->channels + c];
                    }
                }
                float v = acc * 32767.0f;
                v = v > 32767.0f ? 32767.0f : (v < -32768.0f ? -32768.0f : v);
                /* little-endian 16-bit ("sowt") */
                int16_t iv = (int16_t)lrintf(v);
                uint8_t *b = (uint8_t *)&out[s * k->out_ch + o];
                b[0] = (uint8_t)(iv & 0xff); b[1] = (uint8_t)((uint16_t)iv >> 8);
            }
        for (int i = 0; i < k->na; i++) {
            KpAudio *a = &k->a[i];
            int take = a->len < n ? a->len : n;
            memmove(a->fifo, a->fifo + (size_t)take * a->channels, (size_t)(a->len - take) * a->channels * sizeof(float));
            a->len -= take;
        }
        pkt->pts = pkt->dts = k->audio_out;
        pkt->duration = n;
        pkt->flags |= AV_PKT_FLAG_KEY;
        k->audio_out += n;
        if ((ret = kp_emit_packet(k, pkt, -1)) < 0) return ret;
    }
}

static int kp_audio_packet(Kp *k, KpAudio *a, AVPacket *pkt) {
    int ret = avcodec_send_packet(a->dec, pkt);
    if (ret < 0 && ret != AVERROR_EOF) return kp_fail(k, "audio", ret);
    for (;;) {
        ret = avcodec_receive_frame(a->dec, k->aframe);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return 0;
        if (ret < 0) return kp_fail(k, "audio", ret);
        ret = kp_audio_frame(k, a, k->aframe);
        av_frame_unref(k->aframe);
        if (ret < 0) return kp_fail(k, "audio", ret);
    }
}

/* ------------------------------------------------------------------ the muxer */

/* stream −1 = the mixed audio (pts in samples); otherwise the copied audio stream (its own time base) */
static int kp_emit_packet(Kp *k, AVPacket *pkt, int stream) {
    int ret;
    if (!k->header) {
        if (k->nq == k->capq) {
            int cap = k->capq ? k->capq * 2 : 64;
            AVPacket **q = av_realloc_array(k->queue, (size_t)cap, sizeof *q);
            if (!q) { av_packet_free(&pkt); return AVERROR(ENOMEM); }
            k->queue = q; k->capq = cap;
        }
        pkt->stream_index = stream;
        k->queue[k->nq++] = pkt;
        return 0;
    }
    if (k->oaud < 0) { av_packet_free(&pkt); return 0; }
    AVRational src = stream < 0 ? (AVRational){1, k->rate} : k->ic->streams[stream]->time_base;
    if (stream >= 0) {
        int64_t shift = av_rescale_q(k->t0, AV_TIME_BASE_Q, src);
        if (pkt->pts != AV_NOPTS_VALUE) pkt->pts -= shift;
        if (pkt->dts != AV_NOPTS_VALUE) pkt->dts -= shift;
    }
    pkt->stream_index = k->oaud;
    av_packet_rescale_ts(pkt, src, k->oc->streams[k->oaud]->time_base);
    pkt->pos = -1;
    ret = av_interleaved_write_frame(k->oc, pkt);
    av_packet_free(&pkt);
    return ret < 0 ? kp_fail(k, "writing the audio", ret) : 0;
}

/*
 * Starts the intermediate file once the encoder has given its parameter sets ([csd]: VPS/SPS/PPS, Annex B): the HEVC
 * track (hvc1, colors as given – the encoder's VUI says the same) and the audio track; audio made before is written.
 */
static int kp_start_mux(Kp *k, const uint8_t *csd, int csd_len, int w, int h, int primaries, int trc, int space, int range) {
    int ret;
    if (k->header) return 0;
    if (k->out_fd < 0) return kp_failf(k, "no output file");
    if ((ret = avformat_alloc_output_context2(&k->oc, NULL, "mov", NULL)) < 0) return kp_fail(k, "the MOV muxer", ret);
    uint8_t *buf = av_malloc(1 << 16);
    k->out_io = buf ? avio_alloc_context(buf, 1 << 16, 1, k, NULL, kp_out_write, kp_out_seek) : NULL;
    if (!k->out_io) { av_free(buf); return AVERROR(ENOMEM); }
    k->oc->pb = k->out_io;
    k->oc->flags |= AVFMT_FLAG_CUSTOM_IO;

    AVStream *vs = avformat_new_stream(k->oc, NULL);
    if (!vs) return AVERROR(ENOMEM);
    AVStream *src = k->ic->streams[k->vidx];
    AVCodecParameters *p = vs->codecpar;
    p->codec_type = AVMEDIA_TYPE_VIDEO;
    p->codec_id = AV_CODEC_ID_HEVC;
    p->codec_tag = MKTAG('h', 'v', 'c', '1');
    p->width = w;
    p->height = h;
    p->color_primaries = (enum AVColorPrimaries)primaries;
    p->color_trc = (enum AVColorTransferCharacteristic)trc;
    p->color_space = (enum AVColorSpace)space;
    p->color_range = (enum AVColorRange)range;
    p->sample_aspect_ratio = src->codecpar->sample_aspect_ratio;
    p->extradata = av_mallocz((size_t)csd_len + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!p->extradata) return AVERROR(ENOMEM);
    memcpy(p->extradata, csd, (size_t)csd_len);
    p->extradata_size = csd_len;
    const AVPacketSideData *dm = av_packet_side_data_get(src->codecpar->coded_side_data, src->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (dm) {
        AVPacketSideData *o = av_packet_side_data_new(&p->coded_side_data, &p->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX, dm->size, 0);
        if (o) memcpy(o->data, dm->data, dm->size);
    }
    vs->time_base = k->vtb_src;
    vs->avg_frame_rate = src->avg_frame_rate;
    k->ovid = vs->index;

    if (k->na > 0 || k->copy_stream >= 0) {
        AVStream *as = avformat_new_stream(k->oc, NULL);
        if (!as) return AVERROR(ENOMEM);
        if (k->na > 0) {
            AVCodecParameters *ap = as->codecpar;
            ap->codec_type = AVMEDIA_TYPE_AUDIO;
            ap->codec_id = AV_CODEC_ID_PCM_S16LE;
            av_channel_layout_default(&ap->ch_layout, k->out_ch);
            ap->sample_rate = k->rate;
            ap->bits_per_coded_sample = 16;
            ap->block_align = 2 * k->out_ch;
            ap->frame_size = 1;
            as->time_base = (AVRational){1, k->rate};
        } else {
            AVStream *cs = k->ic->streams[k->copy_stream];
            if ((ret = avcodec_parameters_copy(as->codecpar, cs->codecpar)) < 0) return kp_fail(k, "the audio track", ret);
            as->codecpar->codec_tag = 0;
            as->time_base = cs->time_base;
        }
        k->oaud = as->index;
    }
    if ((ret = avformat_write_header(k->oc, NULL)) < 0) return kp_fail(k, "starting the intermediate file", ret);
    k->header = 1;
    for (int i = 0; i < k->nq; i++) {
        AVPacket *q = k->queue[i];
        k->queue[i] = NULL;
        if ((ret = kp_emit_packet(k, q, q->stream_index)) < 0) return ret;
    }
    k->nq = 0;
    return 0;
}

/* One encoded picture (Annex B from the encoder; the muxer turns it into hvc1's length-prefixed form). */
static int kp_write_video(Kp *k, const uint8_t *data, int size, int64_t pts_us, int key) {
    if (!k->header) return kp_failf(k, "picture before the encoder's configuration");
    AVPacket *pkt = av_packet_alloc();
    if (!pkt) return AVERROR(ENOMEM);
    int ret = av_new_packet(pkt, size);
    if (ret < 0) { av_packet_free(&pkt); return ret; }
    memcpy(pkt->data, data, (size_t)size);
    /* back to the source's own time base: the frames keep exactly the times they had (µs only on the way through) */
    int64_t ts = av_rescale_q_rnd(pts_us, AV_TIME_BASE_Q, k->vtb_src, AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX);
    if (ts <= k->last_vdts) { av_packet_free(&pkt); return kp_failf(k, "the encoder reordered the frames (B-frames)"); }
    k->last_vdts = ts;
    pkt->pts = pkt->dts = ts;
    pkt->duration = k->frame_dur;
    if (key) pkt->flags |= AV_PKT_FLAG_KEY;
    pkt->stream_index = k->ovid;
    av_packet_rescale_ts(pkt, k->vtb_src, k->oc->streams[k->ovid]->time_base);
    ret = av_interleaved_write_frame(k->oc, pkt);
    av_packet_free(&pkt);
    return ret < 0 ? kp_fail(k, "writing the picture", ret) : 0;
}

/* ------------------------------------------------------------------ the read loop */

static KpAudio *kp_audio_of(Kp *k, int stream) {
    for (int i = 0; i < k->na; i++) if (k->a[i].stream == stream) return &k->a[i];
    return NULL;
}

static int kp_demux_eof(Kp *k) {
    int ret;
    k->demux_eof = 1;
    for (int i = 0; i < k->na; i++) if ((ret = kp_audio_packet(k, &k->a[i], NULL)) < 0) return ret;
    return kp_mix(k, 0);
}

/* A picture the decoder refused as damaged: skipped, unless there are more damaged ones than good ones. */
static int kp_damaged(Kp *k) {
    k->bad++;
    if (k->bad > 12 && k->bad > k->good) return kp_failf(k, "the ProRes pictures are damaged");
    return 0;
}

/*
 * The next picture into [p] (NULL: decoded and dropped – the speed check). Audio read on the way is decoded and mixed.
 * Returns its time (µs, from the file's start), −1 after the last picture, or an AVERROR (message in err).
 */
static int64_t kp_read_frame(Kp *k, const KpPlanes *p) {
    int ret;
    if (k->video_eof) return -1;
    for (;;) {
        if (k->pkt_pending) {
            ret = avcodec_send_packet(k->vdec, k->pkt);
            if (ret == 0) { k->pkt_pending = 0; av_packet_unref(k->pkt); }
            else if (ret == AVERROR_INVALIDDATA) { k->pkt_pending = 0; av_packet_unref(k->pkt); if ((ret = kp_damaged(k)) < 0) return ret; }
            else if (ret != AVERROR(EAGAIN)) { av_packet_unref(k->pkt); k->pkt_pending = 0; return kp_fail(k, "ProRes", ret); }
        }
        ret = avcodec_receive_frame(k->vdec, k->frame);
        if (ret == 0) {
            int64_t ts = k->frame->best_effort_timestamp != AV_NOPTS_VALUE ? k->frame->best_effort_timestamp : k->frame->pts;
            int64_t us = ts == AV_NOPTS_VALUE ? 0 : av_rescale_q(ts, k->vtb_src, AV_TIME_BASE_Q) - k->t0;
            if (p && (ret = kp_convert(k, k->frame, p)) < 0) { av_frame_unref(k->frame); return ret; }
            av_frame_unref(k->frame);
            k->good++;
            return us < 0 ? 0 : us;
        }
        if (ret == AVERROR_EOF) {
            k->video_eof = 1;
            if (k->good == 0) return kp_failf(k, "no ProRes picture in the file could be decoded");
            return -1;
        }
        /* (a damaged picture is skipped – the one before it stays on screen – unless most of them are) */
        if (ret == AVERROR_INVALIDDATA) { if ((ret = kp_damaged(k)) < 0) return ret; continue; }
        if (ret != AVERROR(EAGAIN)) return kp_fail(k, "ProRes", ret);
        if (k->pkt_pending) continue;
        if (k->demux_eof) {
            if ((ret = avcodec_send_packet(k->vdec, NULL)) < 0 && ret != AVERROR_EOF) return kp_fail(k, "ProRes", ret);
            continue;
        }
        ret = av_read_frame(k->ic, k->pkt);
        if (ret == AVERROR_EOF) { if ((ret = kp_demux_eof(k)) < 0) return ret; continue; }
        if (ret < 0) return kp_fail(k, "reading the file", ret);
        int s = k->pkt->stream_index;
        if (s == k->vidx) { k->pkt_pending = 1; continue; }
        KpAudio *a = kp_audio_of(k, s);
        if (a) {
            ret = kp_audio_packet(k, a, k->pkt);
            av_packet_unref(k->pkt);
            if (ret < 0 || (ret = kp_mix(k, 0)) < 0) return ret;
        } else if (s == k->copy_stream) {
            AVPacket *c = av_packet_clone(k->pkt);
            av_packet_unref(k->pkt);
            if (!c) return AVERROR(ENOMEM);
            if ((ret = kp_emit_packet(k, c, s)) < 0) return ret;
        } else av_packet_unref(k->pkt);
    }
}

/* After the last picture: the rest of the audio, then the file's index (moov). */
static int kp_finish(Kp *k) {
    int ret;
    if (!k->demux_eof) {
        /* (the picture ended before the file: the audio after it is still read) */
        while ((ret = av_read_frame(k->ic, k->pkt)) >= 0) {
            KpAudio *a = kp_audio_of(k, k->pkt->stream_index);
            if (a) { ret = kp_audio_packet(k, a, k->pkt); if (ret >= 0) ret = kp_mix(k, 0); }
            else if (k->pkt->stream_index == k->copy_stream) { AVPacket *c = av_packet_clone(k->pkt); ret = c ? kp_emit_packet(k, c, k->copy_stream) : AVERROR(ENOMEM); }
            av_packet_unref(k->pkt);
            if (ret < 0) return ret;
        }
        if ((ret = kp_demux_eof(k)) < 0) return ret;
    }
    if ((ret = kp_mix(k, 1)) < 0) return ret;
    if (!k->header) return kp_failf(k, "the encoder gave no picture");
    if ((ret = av_write_trailer(k->oc)) < 0) return kp_fail(k, "finishing the intermediate file", ret);
    avio_flush(k->out_io);
    if (fsync(k->out_fd) != 0) return kp_failf(k, "the intermediate file couldn't be saved");
    return 0;
}

#endif
