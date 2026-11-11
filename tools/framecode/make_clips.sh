#!/bin/sh
# Frame-coded test clips for checking preview and export frame by frame (docs/media3-rendering.md).
# Each frame carries its number as 13 black/white blocks (bit k = block k, 49.23 px wide) in the rows 120–192 of a
# 640×360 picture; A has a blue background, B a red one. 25 fps, 300 s, H.264.
#   tools/framecode/make_clips.sh <out dir> [seconds]
set -e
out=${1:-.}; dur=${2:-300}
for c in A:180:110 B:90:220; do
  n=${c%%:*}; rest=${c#*:}; cb=${rest%%:*}; cr=${rest#*:}
  ffmpeg -v error -y -f lavfi -i "color=c=black:s=640x360:r=25:d=$dur" \
    -vf "format=yuv420p,geq=lum='if(between(Y,120,192),if(mod(floor(N/pow(2,floor(X/49.23))),2),235,16),90)':cb='if(between(Y,120,192),128,$cb)':cr='if(between(Y,120,192),128,$cr)'" \
    -c:v libx264 -preset veryfast -crf 18 -g 25 -pix_fmt yuv420p "$out/framecode_$n.mp4" &
done
wait
