"""MediaPipe Face Landmarker as the face source for follow.Follower (mirrors engine/FaceFollow.kt's MediaPipe part).

Every frame, a square crop around where the chosen face is expected (Follower.request) is cut from the frame at
CROP_PX×CROP_PX and given to the landmarker in VIDEO mode: it finds the face once and then follows its landmarks
from frame to frame, and the crop moves with the face – so a small, far face is as large in the crop as a near one
(the landmarker's own detector only finds faces that fill a good part of its picture). When the face is lost the
crop grows; a wide search (an IMAGE-mode landmarker on tiles over the region) runs at the start and every few frames
while the face is missing.
"""

import math

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions, vision

import follow

CROP_PX = 256
NUM_FACES = 2  # in the crop: the chosen face and possibly a neighbour (matched to its own track)
MIN_DETECTION = 0.5
MIN_PRESENCE = 0.5
MIN_TRACKING = 0.5
TILE_OVERLAP = 0.5


def crop(frame, cx, cy, side, px=CROP_PX, gray=False):
    """A side×side square around (cx, cy) of the frame (pixels), resampled to px×px; grey outside the frame."""
    s = px / side
    m = np.array([[s, 0, px / 2 - cx * s], [0, s, px / 2 - cy * s]], np.float32)
    interp = cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR
    if s < 1:
        # area filter: warpAffine has no area mode – downscale the region first, then place it
        x0, y0 = int(math.floor(cx - side / 2)), int(math.floor(cy - side / 2))
        x1, y1 = int(math.ceil(cx + side / 2)), int(math.ceil(cy + side / 2))
        h, w = frame.shape[:2]
        pad = np.full((y1 - y0, x1 - x0, 3), 128, np.uint8)
        sx0, sy0, sx1, sy1 = max(x0, 0), max(y0, 0), min(x1, w), min(y1, h)
        if sx1 > sx0 and sy1 > sy0:
            pad[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = frame[sy0:sy1, sx0:sx1]
        small = cv2.resize(pad, (max(1, round((x1 - x0) * s)), max(1, round((y1 - y0) * s))), interpolation=cv2.INTER_AREA)
        m2 = np.array([[1, 0, (x0 - cx) * s + px / 2], [0, 1, (y0 - cy) * s + px / 2]], np.float32)
        out = cv2.warpAffine(small, m2, (px, px), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(128, 128, 128))
    else:
        out = cv2.warpAffine(frame, m, (px, px), flags=interp, borderMode=cv2.BORDER_CONSTANT, borderValue=(128, 128, 128))
    if gray:
        g = cv2.cvtColor(out, cv2.COLOR_RGB2GRAY)
        out = cv2.merge([g, g, g])
    return out


def _options(model, mode, num_faces):
    return vision.FaceLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=model), running_mode=mode, num_faces=num_faces,
        min_face_detection_confidence=MIN_DETECTION, min_face_presence_confidence=MIN_PRESENCE,
        min_tracking_confidence=MIN_TRACKING)


class MpSource:
    def __init__(self, model, w, h, gray=False):
        self.w, self.h, self.gray = w, h, gray
        self.video = vision.FaceLandmarker.create_from_options(_options(model, vision.RunningMode.VIDEO, NUM_FACES))
        self.image = vision.FaceLandmarker.create_from_options(_options(model, vision.RunningMode.IMAGE, 4))
        self.ts = 0
        self.last_landmarks = []

    def close(self):
        self.video.close()
        self.image.close()

    def _faces(self, result, cx, cy, side):
        """Landmarks in crop coordinates → observations in the frame."""
        out, raw = [], []
        for face in result.face_landmarks:
            lm = []
            for p in face:
                x = cx + (p.x - 0.5) * side
                y = cy + (p.y - 0.5) * side
                lm.append((x / self.w, y / self.h, p.z * side / self.w))
            raw.append(lm)
            out.append(follow.observe(lm, self.w, self.h))
        return out, raw

    def look(self, frame, req):
        """The faces for Follower.step in this frame, looking where [req] says."""
        img = crop(frame, req.cx, req.cy, req.side, gray=self.gray)
        r = self.video.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=img), self.ts)
        self.ts += 33
        faces, raw = self._faces(r, req.cx, req.cy, req.side)
        self.last_landmarks = raw
        if req.search is not None:
            for o in self.search(frame, *req.search):
                if all(math.hypot(o.cx - f.cx, o.cy - f.cy) > 0.5 * max(o.size, f.size) for f in faces):
                    faces.append(o)
        return faces

    def search(self, frame, cx, cy, r):
        """Tiles over the square region (cx ± r, cy ± r), clipped to the frame, at two tile sizes."""
        found = []
        x0, x1 = max(0.0, cx - r), min(float(self.w), cx + r)
        y0, y1 = max(0.0, cy - r), min(float(self.h), cy + r)
        rw, rh = x1 - x0, y1 - y0
        if rw <= 1 or rh <= 1:
            return found
        for side in tiles(rw, rh):
            for (tx, ty) in tile_centres(x0, y0, rw, rh, side):
                img = crop(frame, tx, ty, side, gray=self.gray)
                res = self.image.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=img))
                faces, _ = self._faces(res, tx, ty, side)
                for o in faces:
                    if all(math.hypot(o.cx - f.cx, o.cy - f.cy) > 0.5 * max(o.size, f.size) for f in found):
                        found.append(o)
        return found


def tiles(rw, rh):
    """Tile sides for a region: the whole region, and one third of its short side (at least 64 px) when smaller."""
    big = max(rw, rh)
    small = max(min(rw, rh) / 3.0, 64.0)
    return [big] if small >= big * 0.6 else [big, small]


def tile_centres(x0, y0, rw, rh, side):
    step = side * (1 - TILE_OVERLAP)
    nx = max(1, int(math.ceil((rw - side) / step)) + 1) if rw > side else 1
    ny = max(1, int(math.ceil((rh - side) / step)) + 1) if rh > side else 1
    out = []
    for j in range(ny):
        for i in range(nx):
            tx = x0 + rw / 2 if nx == 1 else x0 + side / 2 + (rw - side) * i / (nx - 1)
            ty = y0 + rh / 2 if ny == 1 else y0 + side / 2 + (rh - side) * j / (ny - 1)
            out.append((tx, ty))
    return out


def run(model, frames, want, gray=False, record=None):
    """Follow the face nearest [want] (display px: x, y, r) through [frames] (RGB arrays).
    → per frame the observation of the chosen face or None. [record]: a list that gets per frame the request, the
    faces given to the follower and the result (the Kotlin fixtures)."""
    h, w = frames[0].shape[:2]
    src = MpSource(model, w, h, gray)
    f = follow.Follower(w, h, *want)
    out = []
    try:
        for frame in frames:
            req = f.request()
            faces = src.look(frame, req)
            pick = f.step(faces)
            out.append(pick)
            if record is not None:
                record.append({"req": req.to_list(), "faces": [o.to_list() for o in faces],
                               "pick": pick.to_list() if pick else None,
                               "landmarks": src.last_landmarks if len(record) == 10 else None})
    finally:
        src.close()
    return out
