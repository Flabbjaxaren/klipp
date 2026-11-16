#!/bin/sh
# Regression test clips: 8 s, 1920x1080 H.264, a 16-bit code in a band across the middle of the picture
# (code = clip id * 2048 + frame index, 16 blocks, white = 1, least significant bit left), a moving ramp above and
# below it and a hue of its own per clip. The code survives the preview's downscale, compression and screenshots.
# Usage: make_clips.sh <out dir> [hevc]   (hevc: HEVC Main10 as phones record, instead of H.264)
set -e
OUT=${1:?out dir}
if [ "$2" = hevc ]; then
  ENC="-c:v libx265 -preset fast -crf 14 -pix_fmt yuv420p10le -x265-params log-level=error:bframes=2"; PIX=yuv420p10le
else
  ENC="-c:v libx264 -preset medium -crf 16 -bf 2"; PIX=yuv420p
fi
mkdir -p "$OUT"
make() { # name id fps cb cr gop
  NAME=$1; ID=$2; FPS=$3; CB=$4; CR=$5; GOP=$6
  ffmpeg -v error -y -f lavfi -i "color=c=black:s=320x180:r=$FPS:d=8" \
    -vf "format=yuv444p,geq=lum='if(between(Y,72,107),if(lt(mod(X,20),1),16,16+219*mod(floor(($ID*2048+N)/pow(2,floor(X/20))),2)),40+120*mod(X/320+N/$FPS*0.25+Y/720,1))':cb=$CB:cr=$CR,scale=1920:1080:flags=neighbor,format=$PIX" \
    $ENC -g "$GOP" \
    -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv "$OUT/$NAME.mp4"
}
make rq24   1 24         150 110 24
make rq25   2 25         110 150 25
make rq2997 3 30000/1001 100 100 30
make rq30   4 30         160 150 30
make rq50   5 50         128 170 50
make rq60   6 60         170 128 60
make rq120  7 120        100 160 120

# For stabilization (R4): the code in the middle of the width (a white marker, 16 blocks, a white marker over x 22–78 %,
# so a zoom up to 1.4 keeps it in the picture and the markers fix the zoom) on a still texture (nothing for the stabilizer to follow: its path stays put, it only zooms).
makec() { # name id fps gop
  NAME=$1; ID=$2; FPS=$3; GOP=$4
  ffmpeg -v error -y -f lavfi -i "color=c=black:s=320x180:r=$FPS:d=8" \
    -vf "format=yuv444p,geq=lum='if(between(Y,72,107)*between(X,70,249),if(lt(mod(X-70,10),1),16,if(eq(floor((X-70)/10),0)+eq(floor((X-70)/10),17),235,16+219*mod(floor(($ID*2048+N)/pow(2,floor((X-70)/10)-1)),2))),clip(85+40*sin(X/7)*sin(Y/5)+25*sin((X+2*Y)/17),30,150))':cb=128+20*sin(X/23):cr=128+20*cos(Y/19),scale=1920:1080:flags=neighbor,format=$PIX" \
    $ENC -g "$GOP" \
    -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv "$OUT/$NAME.mp4"
}
makec rs30 8 30 30
makec rs60 9 60 60
