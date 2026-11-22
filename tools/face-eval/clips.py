"""Test clips for the face-following evaluation: YUV4MPEG2 reading and the synthesized cases.

The source clips are Xiph's derf test sequences (https://media.xiph.org/video/derf/y4m/): foreman_cif.y4m (head
turns, camera pan) and akiyo_cif.y4m (a still news reader). They are not in the repository; `evaluate.py --fetch`
downloads them into the clip folder.

Synthesized from them (frames as RGB arrays, nothing is written to disk):
- two_faces: akiyo and foreman side by side on one canvas; foreman slides towards akiyo, and the chosen face (foreman)
  is covered by a grey card for 12 frames while akiyo stays visible – the classic moment a follower jumps to the
  other person.
- far: foreman scaled to 30 % on a 1280x720 canvas (the face ~ 30 px wide, ~2.5 % of the frame), drifting across it –
  a person far from a back camera.
- mid: foreman scaled to 55 % on 1280x720 (face ~ 55 px).
The truth for a synthesized clip is the same pipeline's result on the source clip, moved/scaled the same way.
"""

import os
import urllib.request

import numpy as np

XIPH = "https://media.xiph.org/video/derf/y4m/"
SOURCES = ["foreman_cif.y4m", "akiyo_cif.y4m"]


def fetch(folder):
    os.makedirs(folder, exist_ok=True)
    for name in SOURCES:
        path = os.path.join(folder, name)
        if not os.path.exists(path):
            print("downloading", name)
            urllib.request.urlretrieve(XIPH + name, path)


def read_y4m(path, limit=None):
    """Frames as uint8 RGB arrays (BT.601 limited range, 4:2:0, as the derf clips are)."""
    data = open(path, "rb").read()
    head_end = data.index(b"\n")
    head = data[:head_end].decode().split(" ")
    w = int(next(t for t in head if t.startswith("W"))[1:])
    h = int(next(t for t in head if t.startswith("H"))[1:])
    fr = next((t for t in head if t.startswith("F")), "F30:1")[1:].split(":")
    fps = int(fr[0]) / int(fr[1])
    size = w * h * 3 // 2
    pos = head_end + 1
    frames = []
    while pos < len(data):
        fe = data.index(b"\n", pos) + 1
        if fe + size > len(data):
            break
        y = np.frombuffer(data, np.uint8, w * h, fe).reshape(h, w).astype(np.float32)
        u = np.frombuffer(data, np.uint8, w * h // 4, fe + w * h).reshape(h // 2, w // 2).astype(np.float32)
        v = np.frombuffer(data, np.uint8, w * h // 4, fe + w * h * 5 // 4).reshape(h // 2, w // 2).astype(np.float32)
        u = u.repeat(2, 0).repeat(2, 1) - 128
        v = v.repeat(2, 0).repeat(2, 1) - 128
        yy = (y - 16) * 1.164
        rgb = np.stack([yy + 1.596 * v, yy - 0.392 * u - 0.813 * v, yy + 2.017 * u], -1)
        frames.append(np.clip(rgb, 0, 255).astype(np.uint8))
        pos = fe + size
        if limit and len(frames) >= limit:
            break
    return frames, fps


def resize(img, w, h):
    import cv2
    return cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA if w < img.shape[1] else cv2.INTER_LINEAR)


class Placement:
    """Where a source frame is put on the canvas in frame i: top-left corner (px) and scale."""

    def __init__(self, x, y, s):
        self.x, self.y, self.s = x, y, s

    def map(self, cx, cy, size, sw, sh, cw, ch):
        """A face in the source (normalized centre, size as fraction of the source width) → normalized on the canvas."""
        return ((self.x + cx * sw * self.s) / cw, (self.y + cy * sh * self.s) / ch, size * sw * self.s / cw)


def two_faces(foreman, akiyo):
    """akiyo left (still), foreman right sliding towards her; foreman covered in frames 150–161."""
    cw, ch = 960, 400
    sh, sw = foreman[0].shape[:2]
    n = min(len(foreman), len(akiyo))
    frames, fore_pl, aki_pl = [], [], []
    for i in range(n):
        c = np.full((ch, cw, 3), 96, np.uint8)
        ax, ay = 40, 56
        c[ay:ay + sh, ax:ax + sw] = akiyo[i]
        fx = int(round(560 - 160 * min(1.0, i / 150)))
        fy = 56
        c[fy:fy + sh, fx:fx + sw] = foreman[i]
        if 150 <= i < 162:
            c[fy:fy + sh, fx:fx + sw] = 128
        frames.append(c)
        fore_pl.append(Placement(fx, fy, 1.0))
        aki_pl.append(Placement(ax, ay, 1.0))
    return frames, (cw, ch), fore_pl, aki_pl


def scaled(src, scale, cw=1280, ch=720):
    """[src] scaled by [scale] on a cw×ch canvas, drifting from left to right."""
    sh, sw = src[0].shape[:2]
    w, h = int(round(sw * scale)), int(round(sh * scale))
    frames, pl = [], []
    n = len(src)
    for i, f in enumerate(src):
        c = np.full((ch, cw, 3), 90, np.uint8)
        x = int(round(200 + (cw - 400 - w) * i / max(1, n - 1)))
        y = (ch - h) // 2 + 60
        c[y:y + h, x:x + w] = resize(f, w, h)
        frames.append(c)
        pl.append(Placement(x, y, w / sw))
    return frames, (cw, ch), pl
