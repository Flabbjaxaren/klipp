"""
Reads the frame code of tools/framecode/make_clips.sh clips.

  python3 framecode.py image.png X0 Y0 SCALE      the code of a clip whose (0,0) is at (X0,Y0) on the image, clip px × SCALE
  python3 framecode.py --export file.mp4 X0 Y0 SCALE [OFFSET]
                                                 every frame of an export: frames whose code isn't frame + OFFSET

Needs ffmpeg/ffprobe. Blocks under an overlay (the preview's level meter covers the right edge) read as 0: compare
differences, or keep the codes below 2048 there.
"""
import subprocess, sys

BLOCK = 49.23

def _size(path):
    w, h = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=width,height",
                           "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip().split(",")[:2]
    return int(w), int(h)

def decode_image(path, x0, y0, scale):
    w, h = _size(path)
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
                         capture_output=True).stdout
    n = 0
    for k in range(13):
        cx = int(x0 + (k * BLOCK + BLOCK / 2) * scale); cy = int(y0 + 156 * scale)
        v = sum(raw[(cy + dy) * w + cx + dx] for dy in range(-3, 4) for dx in range(-3, 4)) / 49
        if v > 128: n |= 1 << k
    return n

def check_export(path, x0, y0, scale, offset=0):
    w, h = _size(path)
    top = int(y0 + 156 * scale) - 2
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vf", f"crop={w}:4:0:{top}", "-f", "rawvideo",
                          "-pix_fmt", "gray", "-"], capture_output=True).stdout
    size = w * 4; bad = []
    for i in range(len(raw) // size):
        fr = raw[i * size:(i + 1) * size]; n = 0
        for k in range(13):
            if fr[2 * w + int(x0 + (k * BLOCK + BLOCK / 2) * scale)] > 128: n |= 1 << k
        if n != i + offset: bad.append((i, n - i - offset))
    return len(raw) // size, bad

if __name__ == "__main__":
    if sys.argv[1] == "--export":
        frames, bad = check_export(sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]),
                                   int(sys.argv[6]) if len(sys.argv) > 6 else 0)
        print(f"{frames} frames, {len(bad)} not as expected", bad[:20])
    else:
        print(decode_image(sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])))
