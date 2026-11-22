#!/bin/bash
# Synthetic ProRes test files for the ProRes import (never committed – test media is generated). Needs a full ffmpeg
# (prores_ks encoder), e.g. Homebrew's.
#
#   tools/ffmpeg/make_test_media.sh [out dir]     (default: tools/ffmpeg/build/media, git-ignored)
#
# Shapes after real camera files: a Panasonic S5II-style ProRes 422 DCI 4K 25p clip with four separate mono 24-bit
# big-endian PCM tracks, colour tagged BT.709 and the true curve (V-Log) only in the
# com.panasonic.SemiPro.metadata.xml key; plus the other ProRes flavours, audio layouts, a damaged file and a stand-in
# for a camera file whose ProRes the phone can't read at all.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/build/media}
mkdir -p "$OUT"
FF="ffmpeg -hide_banner -loglevel error -y"
D=${KLIPP_TEST_SECONDS:-6}

# (colour tags: set on the frames with setparams – prores_ks drops -color_primaries/-color_trc)
# 10-bit test picture with motion (testsrc2 is 8-bit; the 10-bit gradient bars make banding visible)
pic() { echo "testsrc2=s=$1:r=$2:d=$D,format=yuv422p10le"; }
tone() { echo "sine=f=$1:sample_rate=48000:d=$D"; }

PANA_XML='<?xml version="1.0" encoding="UTF-8"?><NonRealTimeMeta xmlns="urn:schemas-professionalDisc:nonRealTimeMeta:ver.2.20"><Device manufacturer="Panasonic" modelName="DC-S5M2X"/><AcquisitionRecord><Group name="CameraUnitMetadataSet"><Item name="CaptureGammaEquation" value="V-Log"/></Group></AcquisitionRecord><CameraUnitMetadata><CaptureGamma>V-Log</CaptureGamma><CaptureGamut>V-Gamut</CaptureGamut></CameraUnitMetadata><AudioFormat numOfChannel="4"/></NonRealTimeMeta>'

echo "== 1. ProRes 422 (apcn) 4096x2160 25p, 4 mono s24be tracks, V-Log metadata, BT.709 tags"
$FF -f lavfi -i "$(pic 4096x2160 25)" -f lavfi -i "$(tone 440)" -f lavfi -i "$(tone 660)" -f lavfi -i "$(tone 880)" -f lavfi -i "$(tone 1100)" \
  -map 0 -map 1 -map 2 -map 3 -map 4 -c:v prores_ks -profile:v 2 -vendor apl0 -pix_fmt yuv422p10le \
  -vf setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv \
  -c:a pcm_s24be -ac 1 -movflags use_metadata_tags -metadata "com.panasonic.SemiPro.metadata.xml=$PANA_XML" \
  "$OUT/p422_dci4k25_4mono_vlog.mov"

echo "== 2. ProRes 422 HQ (apch) 3840x2160 23.976p, 2 mono s24le tracks (→ stereo left/right)"
$FF -f lavfi -i "$(pic 3840x2160 24000/1001)" -f lavfi -i "$(tone 300)" -f lavfi -i "$(tone 500)" \
  -map 0 -map 1 -map 2 -c:v prores_ks -profile:v 3 -pix_fmt yuv422p10le -c:a pcm_s24le -ac 1 \
  "$OUT/p422hq_uhd2398_2mono.mov"

echo "== 3. ProRes 422 LT (apcs) 1920x1080 29.97p, one stereo s16le track, BT.2020 HLG tags"
$FF -f lavfi -i "$(pic 1920x1080 30000/1001)" -f lavfi -i "$(tone 440)" \
  -map 0 -map 1 -c:v prores_ks -profile:v 1 -pix_fmt yuv422p10le \
  -vf setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv \
  -c:a pcm_s16le -ac 2 "$OUT/p422lt_1080p2997_stereo_hlg.mov"

echo "== 4. ProRes 4444 (ap4h) with alpha 1920x1080 24p, one mono s24be track"
$FF -f lavfi -i "testsrc2=s=1920x1080:r=24:d=$D,format=yuva444p10le" -f lavfi -i "$(tone 440)" \
  -map 0 -map 1 -c:v prores_ks -profile:v 4 -pix_fmt yuva444p10le -alpha_bits 16 -c:a pcm_s24be -ac 1 \
  "$OUT/p4444_1080p24_alpha_mono.mov"

echo "== 5. ProRes 422 Proxy (apco) 1280x720 50p, no audio"
$FF -f lavfi -i "$(pic 1280x720 50)" -map 0 -c:v prores_ks -profile:v 0 -pix_fmt yuv422p10le "$OUT/p422proxy_720p50_noaudio.mov"

echo "== 6. ProRes 422 1920x1080 25p, 6 mono tracks (→ every track centered in stereo)"
$FF -f lavfi -i "$(pic 1920x1080 25)" -f lavfi -i "$(tone 200)" -f lavfi -i "$(tone 300)" -f lavfi -i "$(tone 400)" \
  -f lavfi -i "$(tone 500)" -f lavfi -i "$(tone 600)" -f lavfi -i "$(tone 700)" \
  -map 0 -map 1 -map 2 -map 3 -map 4 -map 5 -map 6 -c:v prores_ks -profile:v 2 -pix_fmt yuv422p10le -c:a pcm_s24be -ac 1 \
  "$OUT/p422_1080p25_6mono.mov"

echo "== 7. damaged: file 6's index first, every picture's bytes overwritten (FFmpeg opens it, the decoder fails)"
$FF -i "$OUT/p422_1080p25_6mono.mov" -map 0 -c copy -movflags +faststart "$OUT/.faststart.mov"
python3 - "$OUT/.faststart.mov" "$OUT/p422_damaged.mov" <<'EOF'
import struct, sys
data = bytearray(open(sys.argv[1], "rb").read())
pos = 0
while pos + 8 <= len(data):
    size, kind = struct.unpack(">I4s", data[pos:pos + 8])
    if size == 1: size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
    if kind == b"mdat":
        # every 'icpf' (a ProRes frame's start) and the bytes after it scrambled: no frame header survives
        i = data.find(b"icpf", pos)
        while 0 <= i < pos + size:
            for j in range(i - 4, min(i + 4000, pos + size)): data[j] = (data[j] * 37 + 11) & 0xFF
            i = data.find(b"icpf", i + 4000)
    if size == 0: break
    pos += size
open(sys.argv[2], "wb").write(data)
EOF
rm -f "$OUT/.faststart.mov"

echo "== 8. ProRes 422 in an MP4 container (.mp4), one mono track"
$FF -f lavfi -i "$(pic 1920x1080 25)" -f lavfi -i "$(tone 440)" -map 0 -map 1 -c:v prores_ks -profile:v 2 -pix_fmt yuv422p10le \
  -c:a pcm_s16le -ac 1 -f mov "$OUT/p422_1080p25_in_mp4.mp4"

ls -l "$OUT"
