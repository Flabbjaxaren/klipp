"""Face following – the algorithm, without MediaPipe (pure Python, mirrored line by line in
app/src/main/java/se/gendut/klipp/engine/FaceFollow.kt; the Kotlin unit test replays fixtures made from this file).

1. observe(): one face's 478 MediaPipe landmarks → centre, size, roll angle and five keypoints. The size is the RMS
   radius of the landmarks in 3D (MediaPipe's z is on the x scale), which a head turn does not change – the box or
   the eye distance of a face shrink as it turns away, and a window following them pumped.
2. Follower: which face is the chosen one in every frame. Every face seen is a track (the chosen one and the others);
   faces and tracks are matched greedily by position (predicted with the track's velocity) and size, so another
   person is matched to their own track and never takes the chosen face's place – also while the chosen face is
   hidden. A lost face is predicted for a moment, and its search window grows; it is taken again only by a face that
   fits its position and size. It also says where to look in the next frame (a crop around the predicted face), and
   when to search wider (tiles around the last position, or the window at the start).
3. smooth(): One Euro filter (Casiez et al. 2012), run forwards and then backwards over the found frames – zero lag
   (the track is computed offline), little smoothing while the face moves fast, much while it is still.
"""

import math

# MediaPipe face mesh landmark indices
R_EYE = (33, 133)  # the person's right eye (image left): outer and inner corner
L_EYE = (362, 263)
NOSE = 1
MOUTH_R = 61
MOUTH_L = 291

# Matching
GATE_D0 = 0.8  # centre distance gate in face sizes (one frame after the last sighting)
GATE_D1 = 0.25  # … growing per frame missed
GATE_DMAX = 3.0
GATE_SIZE = math.log(1.6)  # size ratio gate
SIZE_WEIGHT = 2.0
HOLD = 15  # frames the velocity keeps predicting a lost face
OTHER_LIFE = 45  # frames another face's track is kept unseen
VEL_KEEP = 0.5  # velocity update: old * keep + new * (1 - keep)
VEL_DAMP = 0.8  # velocity of a missed track, per frame

# Where to look
CROP_K = 3.2  # crop side in face sizes
CROP_GROW = 0.25  # per frame missed
CROP_MIN = 48.0  # px of the analysed frame
SEARCH_EVERY = 5  # frames between wide searches while lost (or not yet found)
SEARCH_R = 4.0  # wide search radius in face sizes around the last position

# Smoothing (One Euro, forwards and backwards). Units: centre in face sizes, size as ln, angle in radians; per second.
POS_MIN_CUTOFF = 2.0
POS_BETA = 0.6
SIZE_MIN_CUTOFF = 0.4
SIZE_BETA = 0.5
ANGLE_MIN_CUTOFF = 0.6
ANGLE_BETA = 0.4
D_CUTOFF = 1.0


class Obs:
    """One face in a frame (display pixels of a w×h frame): centre, size (RMS radius), roll (radians, clockwise from
    the image x axis), five keypoints [x0, y0, …] normalized to the frame (eyes, nose tip, mouth corners)."""

    __slots__ = ("cx", "cy", "size", "angle", "pts")

    def __init__(self, cx, cy, size, angle, pts):
        self.cx, self.cy, self.size, self.angle, self.pts = cx, cy, size, angle, pts

    def to_list(self):
        return [self.cx, self.cy, self.size, self.angle] + list(self.pts)

    @staticmethod
    def from_list(v):
        return Obs(v[0], v[1], v[2], v[3], list(v[4:14]))


def observe(lm, w, h):
    """[lm]: 478 (or more) landmarks [x, y, z] normalized to the frame (x, z by width, y by height)."""
    n = len(lm)
    sx = sy = sz = 0.0
    for p in lm:
        sx += p[0] * w
        sy += p[1] * h
        sz += p[2] * w
    cx, cy, cz = sx / n, sy / n, sz / n
    acc = 0.0
    for p in lm:
        dx = p[0] * w - cx
        dy = p[1] * h - cy
        dz = p[2] * w - cz
        acc += dx * dx + dy * dy + dz * dz
    size = math.sqrt(acc / n)

    def mean(ids):
        return (sum(lm[i][0] for i in ids) / len(ids), sum(lm[i][1] for i in ids) / len(ids))

    er = mean(R_EYE)
    el = mean(L_EYE)
    angle = math.atan2((el[1] - er[1]) * h, (el[0] - er[0]) * w)
    pts = [er[0], er[1], el[0], el[1], lm[NOSE][0], lm[NOSE][1], lm[MOUTH_R][0], lm[MOUTH_R][1], lm[MOUTH_L][0], lm[MOUTH_L][1]]
    return Obs(cx, cy, size, angle, pts)


class Track:
    __slots__ = ("cx", "cy", "size", "vx", "vy", "missed")

    def __init__(self, o):
        self.cx, self.cy, self.size = o.cx, o.cy, o.size
        self.vx = self.vy = 0.0
        self.missed = 0

    def px(self):
        return self.cx + self.vx

    def py(self):
        return self.cy + self.vy

    def cost(self, o):
        """Matching cost, or None outside the gate."""
        d = math.hypot(o.cx - self.px(), o.cy - self.py()) / self.size
        r = abs(math.log(o.size / self.size))
        if d > min(GATE_D0 + GATE_D1 * self.missed, GATE_DMAX) or r > GATE_SIZE:
            return None
        return d + SIZE_WEIGHT * r

    def hit(self, o):
        n = self.missed + 1
        self.vx = self.vx * VEL_KEEP + (o.cx - self.cx) / n * (1 - VEL_KEEP)
        self.vy = self.vy * VEL_KEEP + (o.cy - self.cy) / n * (1 - VEL_KEEP)
        self.cx, self.cy, self.size = o.cx, o.cy, o.size
        self.missed = 0

    def miss(self):
        self.missed += 1
        if self.missed <= HOLD:
            self.cx += self.vx
            self.cy += self.vy
            self.vx *= VEL_DAMP
            self.vy *= VEL_DAMP
        else:
            self.vx = self.vy = 0.0


class Request:
    """Where to look in the next frame (display pixels): a square crop, and optionally a wide search region."""

    __slots__ = ("cx", "cy", "side", "search")

    def __init__(self, cx, cy, side, search):
        self.cx, self.cy, self.side, self.search = cx, cy, side, search

    def to_list(self):
        return [self.cx, self.cy, self.side] + (list(self.search) if self.search else [])


class Follower:
    """[want]: the window (display pixels: centre x, y and radius) – the face nearest it is the one followed."""

    def __init__(self, w, h, want_x, want_y, want_r):
        self.w, self.h = w, h
        self.want = (want_x, want_y, max(want_r, 0.02 * min(w, h)))
        self.target = None
        self.others = []
        self.frame = 0

    def acquire(self, faces):
        wx, wy, wr = self.want
        best, bd = None, None
        for o in faces:
            d = math.hypot(o.cx - wx, o.cy - wy)
            if d < max(wr + o.size * 2.0, 0.08 * min(self.w, self.h)) and (bd is None or d < bd):
                best, bd = o, d
        return best

    def step(self, faces):
        """The faces found in this frame → the chosen face (or None when it isn't found)."""
        self.frame += 1
        if self.target is None:
            pick = self.acquire(faces)
            if pick is not None:
                self.target = Track(pick)
            for o in faces:
                if o is not pick:
                    self.others.append(Track(o))
            return pick
        tracks = [self.target] + self.others
        pairs = []
        for ti, t in enumerate(tracks):
            for oi, o in enumerate(faces):
                c = t.cost(o)
                if c is not None:
                    pairs.append((c, ti, oi))
        pairs.sort(key=lambda p: (p[0], p[1], p[2]))
        used_t, used_o, match = set(), set(), {}
        for c, ti, oi in pairs:
            if ti in used_t or oi in used_o:
                continue
            used_t.add(ti)
            used_o.add(oi)
            match[ti] = oi
        for ti, t in enumerate(tracks):
            if ti in match:
                t.hit(faces[match[ti]])
            else:
                t.miss()
        self.others = [t for t in self.others if t.missed <= OTHER_LIFE]
        for oi, o in enumerate(faces):
            if oi not in used_o:
                self.others.append(Track(o))
        return faces[match[0]] if 0 in match else None

    def request(self):
        """Where to look in the next frame."""
        t = self.target
        if t is None:
            wx, wy, wr = self.want
            side = min(max(CROP_K * wr, CROP_MIN), max(self.w, self.h))
            # the whole window is searched at the start and then every few frames until a face is found
            search = (wx, wy, 2 * wr) if self.frame % SEARCH_EVERY == 0 else None
            return Request(wx, wy, side, search)
        m = min(t.missed, 8)
        side = min(max(CROP_K * t.size * (1 + CROP_GROW * m), CROP_MIN), max(self.w, self.h))
        search = None
        if t.missed >= 3 and t.missed % SEARCH_EVERY == 0:
            search = (t.px(), t.py(), SEARCH_R * t.size)
        return Request(t.px(), t.py(), side, search)


# ---- smoothing ----

def _alpha(cutoff, dt):
    tau = 1.0 / (2 * math.pi * cutoff)
    return 1.0 / (1.0 + tau / dt)


def one_euro(t, x, scale, min_cutoff, beta, d_cutoff=D_CUTOFF):
    """One pass over samples x at times t (seconds); the speed is measured in units of [scale] (per sample)."""
    out = [0.0] * len(x)
    if not x:
        return out
    prev = x[0]
    dprev = 0.0
    out[0] = prev
    for i in range(1, len(x)):
        dt = max(abs(t[i] - t[i - 1]), 1e-4)
        dx = (x[i] - prev) / dt
        a = _alpha(d_cutoff, dt)
        dprev = dprev + a * (dx - dprev)
        cutoff = min_cutoff + beta * abs(dprev) / scale[i]
        a = _alpha(cutoff, dt)
        prev = prev + a * (x[i] - prev)
        out[i] = prev
    return out


def one_euro_fb(t, x, scale, min_cutoff, beta):
    """Forwards, then backwards over the result: no lag."""
    f = one_euro(t, x, scale, min_cutoff, beta)
    rt = [-v for v in reversed(t)]
    b = one_euro(rt, list(reversed(f)), list(reversed(scale)), min_cutoff, beta)
    return list(reversed(b))


def unwrap(a):
    out = list(a)
    for i in range(1, len(out)):
        d = out[i] - out[i - 1]
        while d > math.pi:
            out[i] -= 2 * math.pi
            d -= 2 * math.pi
        while d < -math.pi:
            out[i] += 2 * math.pi
            d += 2 * math.pi
    return out


def smooth(samples):
    """[samples]: (t, cx, cy, size, angle) of the frames where the face was found, sorted by t (display pixels).
    → the same, smoothed."""
    if len(samples) < 3:
        return list(samples)
    t = [s[0] for s in samples]
    size = [s[3] for s in samples]
    ones = [1.0] * len(samples)
    ls = one_euro_fb(t, [math.log(s) for s in size], ones, SIZE_MIN_CUTOFF, SIZE_BETA)
    ssize = [math.exp(v) for v in ls]
    cx = one_euro_fb(t, [s[1] for s in samples], ssize, POS_MIN_CUTOFF, POS_BETA)
    cy = one_euro_fb(t, [s[2] for s in samples], ssize, POS_MIN_CUTOFF, POS_BETA)
    an = one_euro_fb(t, unwrap([s[4] for s in samples]), ones, ANGLE_MIN_CUTOFF, ANGLE_BETA)
    return [(t[i], cx[i], cy[i], ssize[i], an[i]) for i in range(len(samples))]
