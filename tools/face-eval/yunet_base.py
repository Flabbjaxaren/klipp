"""The face following of Klipp ≤ 0.21 (the baseline): YuNet 2023mar on every frame of a 320 px grey work image
(rust/src/face.rs), the same face picked by nearness (FaceTracker.reset/step), then FaceSmooth's Gaussian
(σ 1.5 frames for position and turn, 4 for size; frames without the face left out).

cv2.FaceDetectorYN runs the same ONNX file (rust/models/face_detection_yunet_2023mar.onnx) with the same score
(sqrt(cls·obj)), threshold 0.5 and NMS 0.3 as the Rust port.
"""

import math

import cv2
import numpy as np

WORK = 320
SIGMA_POS = 1.5
SIGMA_SIZE = 4.0


class Face:
    __slots__ = ("cx", "cy", "w", "h", "angle", "kps")

    def size(self):
        return max(self.w, self.h)


def detector(model, w, h):
    s = WORK / max(w, h)
    ww, hh = max(16, round(w * s)), max(16, round(h * s))
    return cv2.FaceDetectorYN.create(model, "", (ww, hh), 0.5, 0.3, 50), ww, hh


def detect(det, ww, hh, frame):
    g = cv2.cvtColor(cv2.resize(frame, (ww, hh), interpolation=cv2.INTER_AREA), cv2.COLOR_RGB2GRAY)
    _, faces = det.detect(cv2.merge([g, g, g]))
    out = []
    if faces is None:
        return out
    for f in faces:
        x = Face()
        x.cx = (f[0] + f[2] / 2) / ww
        x.cy = (f[1] + f[3] / 2) / hh
        x.w = f[2] / ww
        x.h = f[3] / hh
        kp = [f[4 + i] / (ww if i % 2 == 0 else hh) for i in range(10)]
        x.kps = kp
        x.angle = math.atan2((kp[3] - kp[1]) * hh, (kp[2] - kp[0]) * ww)
        out.append(x)
    return out


def dist(ax, ay, bx, by):
    return math.hypot(ax - bx, ay - by)


def run(model, frames, want):
    """[want]: (x, y, r) in pixels, as for mp_source.run. → per frame (found, cx, cy, size, angle) in pixels
    (size: the box's larger side), the face's state as FaceTracker keeps it (the last one while not found)."""
    h, w = frames[0].shape[:2]
    det, ww, hh = detector(model, w, h)
    wx, wy = want[0] / w, want[1] / h
    wr = max(want[2] / w, want[2] / h, 0.02)
    last = None
    out = []
    for i, fr in enumerate(frames):
        faces = detect(det, ww, hh, fr)
        if last is None:
            cand = [f for f in faces if dist(f.cx, f.cy, wx, wy) < max(wr + f.size(), 0.08)]
            pick = min(cand, key=lambda f: dist(f.cx, f.cy, wx, wy)) if cand else None
        else:
            p = last
            cand = [f for f in faces if dist(f.cx, f.cy, p.cx, p.cy) < p.size() * 1.2 and 0.6 < f.size() / p.size() < 1.6]
            pick = min(cand, key=lambda f: dist(f.cx, f.cy, p.cx, p.cy)) if cand else None
        if pick is not None:
            last = pick
        out.append(pick)
    return out


def gauss(v, sigma):
    k = max(1, int(sigma * 3))
    wts = [math.exp(-(d * d) / (2 * sigma * sigma)) for d in range(-k, k + 1)]
    out = []
    for i in range(len(v)):
        a = n = 0.0
        for j in range(-k, k + 1):
            x = i + j
            if 0 <= x < len(v):
                a += v[x] * wts[j + k]
                n += wts[j + k]
        out.append(a / n)
    return out


def smooth(samples):
    """FaceSmooth.apply on (t, cx, cy, size, angle) of the found frames."""
    if len(samples) < 3:
        return list(samples)
    cx = gauss([s[1] for s in samples], SIGMA_POS)
    cy = gauss([s[2] for s in samples], SIGMA_POS)
    sz = gauss([s[3] for s in samples], SIGMA_SIZE)
    an = gauss([s[4] for s in samples], SIGMA_POS)
    return [(samples[i][0], cx[i], cy[i], sz[i], an[i]) for i in range(len(samples))]


def as_pixels(picks, w, h):
    """Detections (normalized) → (cx, cy, size, angle) in pixels, None where not found."""
    return [None if p is None else (p.cx * w, p.cy * h, max(p.w * w, p.h * h), p.angle)
            for p in picks]
