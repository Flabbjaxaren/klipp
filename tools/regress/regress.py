#!/opt/homebrew/bin/python3
"""
Klipp regression run: picture identity and timing on a phone, through the app's MCP bridge.

It works in a project of its own ("Klipp regress"), builds small test timelines from frame-coded clips (make_clips.sh)
and checks, separately:

  identity  – the source frame every slot shows, by the app's own rule (a port of SourceSampler), decoded from the code
              band in the picture: in the export, in a pre-rendered chunk and in the paused player;
  timing    – whether the main player's frames reached the screen on time (CutTrace), from a cold start and right after
              the composition was rebuilt;
  repeat    – the same export rendered several times must give the same frames (an intermittent missing picture).

The user's open project, playhead, mode and selection are put back at the end. Nothing is deleted: the rebuild is the
bridge's render_diag op=rebuild, rendering is held with op=hold.

Needs: adb, ffmpeg/ffprobe, Python 3 with numpy; on the phone Klipp with "Let the computer control Klipp" on.
Usage:  regress.py --serial <adb serial> [--port 8765] [--pkg se.gendut.klipp.designtest] [--clips <dir of rq*.mp4>]
                   [--apv /sdcard/DCIM/Camera/a.mp4,...] [--only R1,R2] [--repeat 5] [--out <dir>]
"""
import argparse, datetime, json, math, os, re, subprocess, sys, time, urllib.request

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
if not os.path.exists(ADB):
    ADB = "adb"

PROJECT = "Klipp regress"            # (--project: another, e.g. for the HEVC clips)
FPS = 25.0
LEAD_US = 1000            # FrameGrid.LEAD_US and Playback's VIDEO_LEAD_US
BITS = 14                  # bits read (codes stay below 2^14; the program view's audio meter covers the right edge)
CLIPS = {                  # file → (clip id in the code, frame rate)
    "rq24.mp4": (1, 24.0), "rq25.mp4": (2, 25.0), "rq2997.mp4": (3, 30000 / 1001), "rq30.mp4": (4, 30.0),
    "rq50.mp4": (5, 50.0), "rq60.mp4": (6, 60.0), "rq120.mp4": (7, 120.0),
    # the code in the middle half of the width on a still texture (stabilization zooms in: decode_central)
    "rs30.mp4": (8, 30.0), "rs60.mp4": (9, 60.0),
}
CENTRAL = {8, 9}
# R6: a dark and a light plain picture (make_clips.sh): the push between them has a reference of its own (push_errors)
PUSH_FILES = ("push_dark.mp4", "push_light.mp4")
CAMERA = []                # the camera clips of test A1 (--apv): file names, slowest frame rate first


# ------------------------------------------------------------------ phone and bridge

class Phone:
    def __init__(self, serial, port, pkg):
        self.serial, self.port, self.pkg = serial, port, pkg
        self.files = f"/sdcard/Android/data/{pkg}/files"

    def adb(self, *args, binary=False, check=True, timeout=600):
        r = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')[:300]}")
        return r.stdout if binary else r.stdout.decode(errors="replace")

    def forward(self):
        self.adb("forward", f"tcp:{self.port}", "localabstract:klipp-mcp")

    def call(self, tool, args=None, timeout=300):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args or {}}}).encode()
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/mcp", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            res = json.loads(r.read())
        if "error" in res:
            raise RuntimeError(f"{tool}: {res['error']}")
        res = res["result"]
        text = "\n".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
        if res.get("isError"):
            raise RuntimeError(f"{tool}: {text[:300]}")
        return text

    def callj(self, tool, args=None, timeout=300):
        return json.loads(self.call(tool, args, timeout))

    def exec(self, command, params=None):
        return self.callj("engine_exec", {"command": command, "params": params or {}})

    def screencap(self, path):
        open(path, "wb").write(self.adb("exec-out", "screencap", "-p", binary=True))

    def node_bounds(self, tag):
        for line in self.call("ui_nodes", {"filter": tag}).splitlines():
            m = re.search(r"\[(\d+),(\d+),(\d+),(\d+)\]", line)
            if m and tag in line:
                return tuple(int(v) for v in m.groups())
        return None


# ------------------------------------------------------------------ the frame code

def decode_gray(img):
    """The code in a picture (2D gray array of the whole frame, any size), -1 if unreadable."""
    h, w = img.shape
    y = int(h * 0.5)
    v = 0
    for b in range(BITS):
        x = int((b + 0.5) * w / 16)
        p = float(img[max(0, y - h // 60):y + h // 60 + 1, max(0, x - w // 120):x + w // 120 + 1].mean())
        if 70 < p < 185:
            return -1
        if p >= 128:
            v |= 1 << b
    return v


def video_codes(path):
    """Every decoded frame of a file's first video stream: (pts s, code, gray image). Strict: a decoding error, a frame
    without a time or a different count of images and times is an error – never truncated to the shorter."""
    w, h = 320, 180
    decoded = subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", path, "-map", "0:v:0", "-fps_mode", "passthrough",
                              "-vf", f"scale={w}:{h}:flags=area,format=gray", "-f", "rawvideo", "-"], capture_output=True, check=True)
    probed = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries", "frame=pts_time",
                             "-of", "json", path], capture_output=True, check=True, text=True)
    frames = json.loads(probed.stdout).get("frames", [])
    if not frames:
        raise ValueError(f"{path}: no video frames")
    if any("pts_time" not in f for f in frames):
        raise ValueError(f"{path}: a decoded frame has no time")
    ts = [float(f["pts_time"]) for f in frames]
    if not all(math.isfinite(t) for t in ts):
        raise ValueError(f"{path}: an invalid frame time")
    raw = decoded.stdout
    if len(raw) % (w * h):
        raise ValueError(f"{path}: an incomplete frame")
    images = np.frombuffer(raw, np.uint8).reshape(-1, h, w)
    if len(images) != len(ts):
        raise ValueError(f"{path}: {len(images)} decoded images but {len(ts)} frame times")
    return [(pts, decode_gray(img), img) for pts, img in zip(ts, images)]


def layout_errors(codes, first_slot, end_slot, fps=FPS):
    """
    Completeness, apart from what the frames show: exactly one frame for every slot of [first_slot, end_slot) – the range
    asked for, not the file's own length (a short file must not define its own pass) – each on its slot (within half a
    millisecond: the file's time base) and in rising order. File times start at 0 at first_slot.
    """
    from collections import Counter
    errors, slots, previous = [], [], None
    eps_s = 0.0005
    for i, (pts, _, _) in enumerate(codes):
        local = round(pts * fps)
        slot = first_slot + local
        slots.append(slot)
        if abs(pts - local / fps) > eps_s:
            errors.append({"kind": "off_grid", "frame": i, "pts": pts, "nearest_slot": slot})
        if previous is not None and pts <= previous:
            errors.append({"kind": "non_increasing_pts", "frame": i, "pts": pts, "previous": previous})
        previous = pts
    counts = Counter(slots)
    wanted = set(range(first_slot, end_slot))
    seen = set(slots)
    missing = sorted(wanted - seen)
    unexpected = sorted(seen - wanted)
    duplicates = sorted(k for k, n in counts.items() if n > 1)
    if missing:
        errors.append({"kind": "missing_slots", "slots": missing})
    if unexpected:
        errors.append({"kind": "unexpected_slots", "slots": unexpected})
    if duplicates:
        errors.append({"kind": "duplicate_slots", "slots": duplicates})
    return errors


def push_progress(p, pc):
    """An independent reference for the push's move (Transitions.kt: thrown, gather, settle): how far (0..1) both pictures
    have moved at progress p of the window, with the edit point at pc."""
    p = min(1.0, max(0.0, p))
    thrown, gather = 0.68, 1.15
    if p < pc:
        x = p / pc
        return thrown * (math.exp(gather * x) - 1.0 - gather * x) / (math.exp(gather) - 1.0 - gather)
    x = (p - pc) / (1.0 - pc)

    def bez(t, a, b):
        r = 1.0 - t
        return 3 * r * r * t * a + 3 * r * t * t * b + t ** 3
    lo, hi = 0.0, 1.0
    for _ in range(40):
        t = (lo + hi) / 2
        if bez(t, 0.11, 0.0) < x:
            lo = t
        else:
            hi = t
    return thrown + (1.0 - thrown) * bez((lo + hi) / 2, 0.72, 1.0)


def push_errors(codes, timeline):
    """
    What a push from a dark to a light picture must look like on every frame of its window: dark left of the edge, light
    right of it, the edge at (1 − e)·width (the incoming picture comes from the right, the outgoing one leaves to the left),
    e from push_progress – the levels calibrated from the file's plain parts. Returns (frames checked, layout errors,
    wrong frames); raises on a timeline or file that can't be judged.
    """
    track = timeline["sequence"]["video"][0]
    transitions = track.get("transitions", [])
    if len(transitions) != 1:
        raise ValueError("R6 must contain exactly one transition")
    tr = transitions[0]
    clips = {c["id"]: c for c in track["clips"]}
    outgoing, incoming = clips[tr["from"]], clips[tr["to"]]
    if outgoing["name"] != "push_dark.mp4" or incoming["name"] != "push_light.mp4":
        raise ValueError("unexpected R6 clip pair")
    t0, t1, cut = tr["start"], tr["start"] + tr["duration"], incoming["start"]
    if not t0 < cut < t1:
        raise ValueError("R6 cut is outside the transition")
    end = max(c["start"] + c["duration"] for c in clips.values())
    layout = layout_errors(codes, 0, math.ceil(end * FPS - 1e-6))
    dark_s = [float(img.mean()) for pts, _, img in codes if 0.2 <= pts <= t0 - 0.1]
    light_s = [float(img.mean()) for pts, _, img in codes if t1 + 0.1 <= pts <= end - 0.2]
    if not dark_s or not light_s:
        raise ValueError("no plain frames to calibrate with")
    dark, light = float(np.median(dark_s)), float(np.median(light_s))
    if not (20 <= dark <= 90 and 160 <= light <= 240 and light - dark >= 80):
        raise ValueError(f"invalid calibration levels: {dark:.1f}/{light:.1f}")
    bad, checked = [], 0
    pc = (cut - t0) / (t1 - t0)
    for i, (pts, _, img) in enumerate(codes):
        if not t0 <= pts < t1:
            continue
        checked += 1
        h, w = img.shape
        e = push_progress((pts - t0) / (t1 - t0), pc)
        boundary = (1.0 - e) * w
        x = np.arange(w) + 0.5
        valid = np.abs(x - boundary) > 4.0
        profile = img[h // 4:3 * h // 4].astype(np.float32).mean(axis=0)
        error = np.abs(profile - np.where(x < boundary, dark, light))[valid]
        wrong = float(np.mean(error > 18.0))
        mean = float(error.mean())
        if wrong > 0.02 or mean > 6.0:
            bad.append({"frame": i, "slot": round(pts * FPS), "pts": pts, "expected_boundary_px": round(boundary, 1),
                        "wrong_fraction": round(wrong, 3), "mean_error": round(mean, 1)})
    return checked, layout, bad


def png_gray(path, crop=None, size=(320, 180)):
    vf = (f"crop={crop[2] - crop[0]}:{crop[3] - crop[1]}:{crop[0]}:{crop[1]}," if crop else "") + f"scale={size[0]}:{size[1]}:flags=area,format=gray"
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-vf", vf, "-f", "rawvideo", "-"], capture_output=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(size[1], size[0])


def decode_central(img):
    """The code of an rs clip under an unknown zoom (stabilization) and shift: a white marker, 16 blocks and a white
    marker, 10/320 of the width each from x = 70/320, on a texture that is never white. The leftmost and rightmost white
    runs across the band are the markers; their centres give the zoom and shift. -1 if unreadable."""
    h, w = img.shape
    prof = img[int(h * 0.47):int(h * 0.53) + 1].astype(np.float32).mean(axis=0)
    white = np.flatnonzero(prof > 185)
    if len(white) < 2:
        return -1
    def run_centre(i, step):
        j = i
        while 0 <= j + step < w and prof[j + step] > 185:
            j += step
        return (i + j) / 2
    left, right = run_centre(white[0], 1), run_centre(white[-1], -1)
    m0, m17 = 70 / 320 + 0.5 / 32, 70 / 320 + 17.5 / 32           # the markers' centres, as fractions of the width
    scale = (right - left) / (m17 - m0)                              # px per unit of the source width
    if scale < 0.9 * w or scale > 1.6 * w:
        return -1
    code = 0
    for k in range(16):
        vals = [prof[int(round(left + (k + 1 + q - 0.5) / 32 * scale))] for q in (0.35, 0.6, 0.85)]
        if all(v > 185 for v in vals):
            code |= 1 << k
        elif not all(v < 70 for v in vals):
            return -1
    return code


def decode(img, central=False):
    return decode_central(img) if central else decode_gray(img)


def describe(code):
    if code is None:
        return "nothing"
    if code < 0:
        return "unreadable"
    cid, n = divmod(code, 2048)
    name = next((k for k, (i, _) in CLIPS.items() if i == cid), f"clip {cid}")
    return f"{name} frame {n}"


# ------------------------------------------------------------------ the rule (a port of SourceSampler)

def segment_slots(start_s, dur_s, src_in_s, speed, src_fps, fps=FPS):
    """The source frame index every slot of a segment shows: {slot: frame index}, as the export's rule picks it."""
    step = 1e6 / fps
    tol = round(step / 2)
    seg_start, seg_end = round(start_s * 1e6), round((start_s + dur_s) * 1e6)
    at_or_after = lambda t: math.ceil(t * fps / 1e6 - 1e-6)
    slot_of = lambda tau: at_or_after(tau - LEAD_US - tol)
    # FrameSnap: the in point on the first frame at or after (in − lead); the item is read from there less the lead
    i0 = math.ceil((src_in_s - LEAD_US / 1e6) * src_fps - 1e-9)
    nominal = round(1e6 / (src_fps * speed))
    k0 = at_or_after(seg_start - LEAD_US)
    shown, first, last = [], True, None
    j = 0
    while True:
        # FrameSnap puts the in point on frame i0; the item is read from LEAD (source time) before it and placed at the
        # segment's start, so frame i0 + j is on the timeline at segStart + (LEAD + j / srcFps) / speed
        tau = seg_start + round((LEAD_US / 1e6 + j / src_fps) / speed * 1e6)
        if tau > seg_end + 2 * step:
            break
        c = slot_of(tau)
        if first and c > k0 and tau < seg_start + nominal:
            c = k0
        if c < k0:
            c = k0
        first = False
        j += 1
        if last is not None and c <= last:
            continue
        if round(c * step) >= seg_end - LEAD_US:
            continue
        last = c
        shown.append((c, i0 + j - 1))
    out, k, cur = {}, k0, None
    it = iter(shown)
    nxt = next(it, None)
    while round(k * step) < seg_end - LEAD_US:
        while nxt is not None and nxt[0] <= k:
            cur, nxt = nxt[1], next(it, None)
        if cur is not None:
            out[k] = cur
        k += 1
    return out


def expected(timeline, fps=FPS):
    """{slot: code} for the topmost clip of each slot, the slots inside transition windows (not checked for identity) and
    {slot: code} of the bottom track alone (the main player's)."""
    seq = timeline["sequence"]
    exp, skip, base = {}, set(), {}
    for ti, track in enumerate(seq["video"]):                     # bottom first: upper tracks overwrite
        if not track.get("enabled", True):
            continue
        for c in track["clips"]:
            if c["name"] not in CLIPS or c.get("reverse") or not c.get("enabled", True):
                continue
            cid, sfps = CLIPS[c["name"]]
            if c.get("hold") is not None:
                # a freeze frame: the source frame nearest its time on every slot (FrameHolds: the closest frame)
                n = round(c["hold"] * sfps)
                for k in range(math.ceil((c["start"] * 1e6 - LEAD_US) * fps / 1e6 - 1e-6),
                               math.ceil(((c["start"] + c["duration"]) * 1e6 - LEAD_US) * fps / 1e6 - 1e-6)):
                    exp[k] = cid * 2048 + n
                    if ti == 0:
                        base[k] = cid * 2048 + n
                continue
            for k, n in segment_slots(c["start"], c["duration"], c["sourceIn"], c["speed"], sfps, fps).items():
                exp[k] = cid * 2048 + n
                if ti == 0:
                    base[k] = cid * 2048 + n
        for tr in track.get("transitions", []):
            a, b = tr["start"], tr["start"] + tr["duration"]
            skip.update(range(math.floor(a * fps) - 1, math.ceil(b * fps) + 2))
    return exp, skip, base


# ------------------------------------------------------------------ the test timelines

def place(ph, item, track, t, src_in, dur):
    return ph.exec("timeline.place", {"item": item, "track": track, "seconds": t, "insert": False,
                                      "sourceIn": {"seconds": src_in}, "duration": {"seconds": dur}})["clips"][0]


def build(ph, name, items):
    """The sequence [name] (made once): returns its id."""
    for r in ph.exec("project.items")["rows"]:
        if r["name"] == name:
            ph.exec("sequence.open", {"item": r["item"]})
            return r["item"]
    ph.exec("file.newSequence", {"name": name, "width": 1920, "height": 1080, "fps": FPS})
    sid = next(r["item"] for r in ph.exec("project.items")["rows"] if r["name"] == name)
    tracks = [t["id"] for t in ph.callj("timeline")["sequence"]["video"]]
    if name == "R1 straight":
        # mixed frame rates, in points between source frames, one clip at 25 %
        t = 0.0
        for f, si, d in [("rq24.mp4", 1.013, 1.6), ("rq2997.mp4", 2.02, 1.52), ("rq30.mp4", 0.517, 1.44), ("rq50.mp4", 3.01, 1.6),
                         ("rq60.mp4", 1.507, 1.2), ("rq25.mp4", 0.5, 1.2)]:
            place(ph, items[f], "V1", t, si, d)
            t = round(t + d, 6)
        slow = place(ph, items["rq120.mp4"], "V1", t, 2.0, 0.5)
        ph.exec("clip.speedDuration", {"clips": [slow], "speed": 25, "reverse": False, "ripple": True})
    elif name == "R2 layers":
        place(ph, items["rq25.mp4"], "V1", 0.0, 0.5, 6.0)
        place(ph, items["rq30.mp4"], tracks[1], 1.36, 2.013, 2.0)
        place(ph, items["rq60.mp4"], tracks[1], 3.36, 1.0, 0.64)
        place(ph, items["rq120.mp4"], tracks[2], 2.0, 3.0, 0.8)
    elif name == "R3 push":
        ids = [place(ph, items[f], "V1", 2.0 * i, 1.5, 2.0) for i, f in enumerate(["rq30.mp4", "rq25.mp4", "rq60.mp4", "rq24.mp4", "rq50.mp4"])]
        for cid in ids[1:]:
            ph.exec("sequence.applyVideoTransition", {"clip": cid, "effect": "push", "frames": 15, "edge": "in", "align": "center"})
    elif name == "R5 freeze":
        # freeze frames inside clips (the app's own command), so plain clips run into and out of them
        t = 0.0
        for f, si in [("rq30.mp4", 1.0), ("rq25.mp4", 0.5), ("rq60.mp4", 1.5), ("rq24.mp4", 1.0)]:
            place(ph, items[f], "V1", t, si, 1.6)
            t = round(t + 1.6, 6)
        def clip_at(name):
            return next(c for c in ph.callj("timeline")["sequence"]["video"][0]["clips"] if c["name"] == name and c.get("hold") is None)
        for f, off, dur in [("rq30.mp4", 0.8, 0.8), ("rq60.mp4", 0.6, 0.6)]:
            c = clip_at(f)
            ph.exec("clip.insertFrameHoldSegment", {"clip": c["id"], "time": round((c["start"] + off) * 25) * 254016000000 // 25, "seconds": dur})
    elif name == "R6 push oracle":
        place(ph, items["push_dark.mp4"], "V1", 0.0, 1.0, 2.0)
        incoming = place(ph, items["push_light.mp4"], "V1", 2.0, 1.0, 2.0)
        ph.exec("sequence.applyVideoTransition", {"clip": incoming, "effect": "push", "frames": 15, "edge": "in", "align": "center"})
    elif name == "A1 camera":
        # the main case: camera clips trimmed and in a row, film with node cache on two, a push between two, an upper layer
        # (seven clips; fewer files are used in turn, each with an in point of its own)
        ids, t = [], 0.0
        for i in range(max(7, len(CAMERA))):
            ids.append(place(ph, items[CAMERA[i % len(CAMERA)]], "V1", t, 1.013 + 0.3 * i, 1.4))
            t = round(t + 1.4, 6)
        film = {"nodes": [{"id": 1, "label": "Film", "film": {}, "cache": True}]}
        for i in (1, 4):
            if i < len(ids):
                ph.callj("grade", {"op": "set", "clip": ids[i], "grade": film})
        if len(ids) > 4:
            ph.exec("sequence.applyVideoTransition", {"clip": ids[4], "effect": "push", "frames": 15, "edge": "in", "align": "center"})
        place(ph, items[CAMERA[len(CAMERA) // 2]], tracks[1], 2.52, 2.0, 1.6)
    elif name == "R4 stab cache":
        # plain → stabilized → film with node cache → plain → stabilized + film cached → plain
        ids = []
        t = 0.0
        for f, si in [("rq30.mp4", 1.0), ("rs60.mp4", 1.0), ("rq25.mp4", 2.0), ("rq50.mp4", 1.0), ("rs30.mp4", 2.0), ("rq24.mp4", 1.0)]:
            ids.append(place(ph, items[f], "V1", t, si, 1.6))
            t = round(t + 1.6, 6)
        film = {"nodes": [{"id": 1, "label": "Film", "film": {}, "cache": True}]}
        ph.callj("grade", {"op": "set", "clip": ids[2], "grade": film})
        ph.callj("grade", {"op": "set", "clip": ids[4], "grade": film})
        for cid in (ids[1], ids[4]):
            ph.call("grade", {"op": "stabilize", "clip": cid, "strength": 0.6})
            for _ in range(300):
                time.sleep(1)
                st = ph.callj("grade", {"op": "status"})
                if st["clip"] is None and st["progress"] is None:
                    break
            if ph.callj("grade", {"op": "get", "clip": cid})["grade"].get("stab") is None:
                raise RuntimeError(f"clip {cid} wasn't stabilized: {st}")
    return sid


def wait_node_cache(ph, timeout=600):
    """Until every clip with a cached node has its cache file (or the time is up): the clips' statuses."""
    clips = [c["id"] for t in ph.callj("timeline")["sequence"]["video"] for c in t["clips"]]
    t_end = time.time() + timeout
    while True:
        st = {}
        for c in clips:
            g = ph.callj("grade", {"op": "get", "clip": c})
            if any(n.get("cache") for n in g["grade"].get("nodes", [])):
                st[c] = g["nodeCache"]
        done = all(v is not None and ("cached" in v.lower() or "cachad" in v.lower() or "failed" in v.lower() or "misslyck" in v.lower()) for v in st.values())
        if done or time.time() > t_end:
            return st
        time.sleep(3)


# ------------------------------------------------------------------ checks

class Run:
    def __init__(self, ph, out):
        self.ph, self.out = ph, out
        self.results = []
        os.makedirs(out, exist_ok=True)

    def log(self, line):
        with open(os.path.join(self.out, "steps.log"), "a") as f:
            f.write(time.strftime("%H:%M:%S ") + line + "\n")

    def result(self, test, check, ok, summary, detail=None):
        r = {"test": test, "check": check, "ok": ok, "summary": summary}
        if detail:
            r["detail"] = detail
        self.results.append(r)
        print(("PASS " if ok else "FAIL ") + f"{test} / {check}: {summary}", flush=True)

    def render(self, name, a, b, mode):
        self.ph.call("render_diag", {"op": "render", "a": a, "b": b, "mode": mode, "name": name}, timeout=1800)
        local = os.path.join(self.out, f"{name}.mp4")
        self.ph.adb("pull", f"{self.ph.files}/diag/{name}.mp4", local)
        return local

    def identity_file(self, test, check, path, exp, skip, first_slot=0, *, end_slot):
        """Two verdicts in one result: completeness (layout_errors: one frame per slot of the range asked for, transitions
        included) and identity (each slot's source frame by the rule; transition windows aren't read – see R6)."""
        try:
            codes = video_codes(path)
        except Exception as e:
            self.result(test, check, False, f"decode failed: {e}")
            return
        layout = layout_errors(codes, first_slot, end_slot)
        bad, checked = [], 0
        for i, (pts, code, img) in enumerate(codes):
            k = first_slot + round(pts * FPS)
            if not first_slot <= k < end_slot or k in skip or k not in exp:
                continue
            checked += 1
            if exp[k] // 2048 in CENTRAL:
                code = decode_central(img)
            if code != exp[k]:
                bad.append({"slot": k, "t": round(k / FPS, 6), "want": describe(exp[k]), "got": describe(code), "frame": i})
        for b in bad[:6]:
            p = os.path.join(self.out, f"{test}_{check}_slot{b['slot']}.png".replace(" ", "_"))
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-vf", f"select=eq(n\\,{b['frame']}),scale=640:-1",
                            "-frames:v", "1", p], capture_output=True, check=True)
            b["image"] = os.path.basename(p)
        self.result(test, check, not layout and not bad and checked > 0,
                    f"{len(codes)}/{end_slot - first_slot} frames; {checked - len(bad)}/{checked} identities right; {len(layout)} layout errors"
                    + (f"; first wrong at {bad[0]['t']} s: {bad[0]['want']} expected, {bad[0]['got']} shown" if bad else ""),
                    {"layout": layout, "identity": bad} if layout or bad else None)

    def push_file(self, test, check, path, timeline):
        """R6: every frame of the push against its reference (push_errors), not against another render."""
        try:
            checked, layout, bad = push_errors(video_codes(path), timeline)
        except Exception as e:
            self.result(test, check, False, f"push check failed: {e}")
            return
        for b in bad[:6]:
            p = os.path.join(self.out, f"{test}_{check}_slot{b['slot']}.png")
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-vf", f"select=eq(n\\,{b['frame']}),scale=640:-1",
                            "-frames:v", "1", p], capture_output=True, check=True)
            b["image"] = os.path.basename(p)
        self.result(test, check, not layout and not bad and checked > 0,
                    f"{checked} transition frames checked; {len(bad)} wrong; {len(layout)} layout errors",
                    {"layout": layout, "transition": bad} if layout or bad else None)

    def program_rect(self):
        r = self.ph.node_bounds("#programStack")
        if not r:
            raise RuntimeError("program view not found (is the editor showing?)")
        return r

    def shot_code(self, p, r, central):
        self.ph.screencap(p)
        w = int(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width", "-of", "csv=p=0", p], capture_output=True, text=True).stdout.split()[0])
        k_ = w / (r[0] + r[2])                # the program view is centred: its left + right edge = the window's width
        return decode(png_gray(p, tuple(round(v * k_) for v in r)), central)

    def paused(self, test, exp, skip, slots, label):
        r = self.program_rect()
        bad, n = [], 0
        for k in slots:
            if k in skip or k not in exp:
                continue
            self.ph.call("set_playhead", {"seconds": k / FPS})
            time.sleep(2.2)
            p = os.path.join(self.out, f"{test}_paused_{label}_{k}.png".replace(" ", "_"))
            code = self.shot_code(p, r, exp[k] // 2048 in CENTRAL)
            if code != exp[k]:
                # (a notification banner can cover the picture: the paused picture stays, the banner goes)
                time.sleep(6)
                code = self.shot_code(p, r, exp[k] // 2048 in CENTRAL)
                if code == exp[k]:
                    self.log(f"{test}: paused {k} right on a second screenshot (the first was covered)")
            n += 1
            if code != exp[k]:
                bad.append({"slot": k, "t": round(k / FPS, 3), "want": describe(exp[k]), "got": describe(code), "image": os.path.basename(p)})
            else:
                os.remove(p)
        self.result(test, f"paused ({label})", not bad and n > 0, f"{n - len(bad)}/{n} paused pictures right" + (f", first wrong at {bad[0]['t']} s: {bad[0]['want']} expected, {bad[0]['got']} shown" if bad else ""), bad or None)

    def play(self, test, start, secs, end, label, base, skip):
        """Plays [start, start + secs) with CutTrace on: every new frame of the main layer (base: {slot: code} of the
        bottom track) and every slot of a transition must reach the GL chain before its presentation time."""
        ph = self.ph
        ph.call("set_playhead", {"seconds": start})
        time.sleep(2.5)
        ph.call("cut_trace", {"trace": True, "mode": 0})
        ph.call("cut_trace", {"read": True})
        play = ph.node_bounds("desc:Play")
        ph.call("tap", {"x": (play[0] + play[2]) / 2, "y": (play[1] + play[3]) / 2})
        lines = []
        t_end = time.time() + secs
        while time.time() < t_end:
            time.sleep(1.5)
            lines += ph.call("cut_trace", {"read": True}).splitlines()
        ph.call("set_playhead", {"seconds": start})
        time.sleep(0.4)
        lines += ph.call("cut_trace", {"read": True}).splitlines()
        ph.call("cut_trace", {"trace": False})
        tf = os.path.join(self.out, f"{test}_trace_{label}.txt".replace(" ", "_"))
        open(tf, "w").write("\n".join(lines))
        rows = []
        for l in lines:
            m = re.match(r"(\d+) gl chain=(\S+) pts=(-?\d+) present=(-?\d+)", l)
            if m:
                rows.append((int(m.group(1)), m.group(2), int(m.group(3)), int(m.group(4))))
        if not rows:
            self.result(test, f"timing ({label})", False, "no frames traced", {"trace": os.path.basename(tf)})
            return
        from collections import Counter
        main = Counter(r[1] for r in rows if r[2] > 500_000).most_common(1)[0][0]
        lo, hi = start + 0.2, min(end - 0.1, start + secs - 2.0)
        mr = [r for r in rows if r[1] == main and lo <= r[2] / 1e6 <= hi]
        seen = set(round(r[2] / 1e6 * FPS) for r in mr)
        # the slots the main player must draw: a new frame of the bottom track, every slot of a transition (pre-rendered)
        want = [k for k in range(math.ceil(lo * FPS), math.floor(hi * FPS) + 1)
                if k in skip or (k in base and base.get(k - 1) != base[k])]
        missing = [round(k / FPS, 3) for k in want if k not in seen]
        # present=-1: the frame was released to be shown at once – it was already late
        late = [(round(r[2] / 1e6, 3), round((r[0] - r[3]) / 1e6, 1) if r[3] >= 0 else "at once") for r in mr if r[3] < 0 or r[0] > r[3]]
        worst = max((l[1] for l in late if l[1] != "at once"), default=0)
        at_once = sum(1 for l in late if l[1] == "at once")
        self.result(test, f"timing ({label})", not missing and not late,
                    f"{len(want)} new frames expected in {lo:.1f}–{hi:.1f} s: {len(missing)} missing, {len(late)} late"
                    + (f" (worst {worst} ms" + (f", {at_once} released at once" if at_once else "") + ")" if late else ""),
                    {"missing": missing[:30], "late": late[:30], "trace": os.path.basename(tf)} if (missing or late) else None)

    def appearance(self, test, ref, slots, label, tol):
        """The paused picture against the export (no code in camera clips): it must look like the export's frame of its
        slot – that frame the closest of the slots around it (identity by appearance; a still picture is ambiguous, not
        wrong) and within [tol] (mean absolute difference, 8-bit gray at 320×180)."""
        refs = {round(pts * FPS): img.astype(np.int16) for pts, _, img in video_codes(ref)}
        r = self.program_rect()
        bad, rows = [], []
        for k in slots:
            if k not in refs:
                continue
            self.ph.call("set_playhead", {"seconds": k / FPS})
            time.sleep(2.5)
            p = os.path.join(self.out, f"{test}_look_{label}_{k}.png".replace(" ", "_"))
            def judge():
                self.ph.screencap(p)
                w = int(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width", "-of", "csv=p=0", p], capture_output=True, text=True).stdout.split()[0])
                k_ = w / (r[0] + r[2])
                shot = png_gray(p, tuple(round(v * k_) for v in r)).astype(np.int16)
                d = {j: float(np.abs(shot - refs[j]).mean()) for j in range(k - 3, k + 4) if j in refs}
                best = min(d, key=d.get)
                return d, best, best == k or d[k] <= d[best] * 1.03 + 0.2
            d, best, same = judge()
            if not same or d[k] > tol:
                # (a notification banner can cover the picture: the paused picture stays, the banner goes)
                time.sleep(6)
                d, best, same = judge()
                if same and d[k] <= tol:
                    self.log(f"{test}: paused {k} matches on a second screenshot (the first was covered)")
            row = {"slot": k, "t": round(k / FPS, 3), "diff": round(d[k], 2), "closest": best, "closest_diff": round(d[best], 2), "image": os.path.basename(p)}
            rows.append(row)
            if not same or d[k] > tol:
                bad.append(row)
            else:
                os.remove(p)
        self.result(test, f"appearance ({label})", not bad and rows != [],
                    f"{len(rows) - len(bad)}/{len(rows)} paused pictures match the export (tolerance {tol}, worst {max((x['diff'] for x in rows), default=0)})"
                    + (f", first off at {bad[0]['t']} s: closest is slot {bad[0]['closest']}, diff {bad[0]['diff']}" if bad else ""), bad or None)

    def repeat(self, test, a, b, n, mode):
        """The same range rendered [n] times: every render must give the same frames as the first (an intermittent
        missing picture shows as a render that differs)."""
        base, diffs = None, []
        for i in range(n):
            p = self.render(f"{test}_rep_{mode}{i}".replace(" ", "_"), a, b, mode)
            raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", p, "-vf", "scale=320:180:flags=area,format=gray", "-f", "rawvideo", "-"], capture_output=True).stdout
            fr = np.frombuffer(raw, np.uint8).reshape(-1, 180, 320).astype(np.int16)
            if base is None:
                base = fr
                continue
            m = min(len(base), len(fr))
            d = np.abs(base[:m] - fr[:m]).mean(axis=(1, 2))
            worst = int(np.argmax(d))
            diffs.append({"render": i, "frames": int(len(fr)), "worst_frame": worst, "t": round(worst / FPS + a, 3), "mean_diff": round(float(d[worst]), 2), "differs": bool(d[worst] > 2.0 or len(fr) != len(base))})
        bad = [d for d in diffs if d["differs"]]
        self.result(test, f"repeat ({mode}, {n} renders)", not bad, f"{len(bad)} of {n - 1} attempts differ from the first render" + (f" (worst at {bad[0]['t']} s, mean diff {bad[0]['mean_diff']})" if bad else ""), diffs)


# ------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--pkg", default="se.gendut.klipp.designtest")
    ap.add_argument("--clips", default=os.path.join(HERE, "clips"))
    ap.add_argument("--apv", default="", help="comma-separated paths on the phone of camera clips (test A1) – in the app's files dir (adb shell cp them there): the app may not read DCIM")
    ap.add_argument("--only", default="", help="e.g. R1,R3")
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--tol", type=float, default=6.0, help="A1: the paused picture's mean difference to the export (8-bit gray)")
    ap.add_argument("--out", default="")
    ap.add_argument("--restore-to", default="", help="the project to open afterwards when the test project is the open one")
    ap.add_argument("--project", default=PROJECT, help="the test project's name (one per set of clips)")
    args = ap.parse_args()
    globals()["PROJECT"] = args.project
    ph = Phone(args.serial, args.port, args.pkg)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = args.out or os.path.join(HERE, "out", f"{stamp}-{args.serial}")
    run = Run(ph, out)
    only = set(x.strip() for x in args.only.split(",") if x.strip())
    want = lambda t: not only or t in only

    ph.forward()
    state0 = ph.callj("app_state")
    projects = ph.callj("projects", {"op": "list"})
    user = next((p["id"] for p in projects if p["current"]), None)
    if any(p["current"] and p["name"] == PROJECT for p in projects):
        # (a run that stopped half way: the project to go back to is not known)
        user = args.restore_to or sys.exit(f"the test project is open: pass --restore-to <project id> ({[(p['id'], p['name']) for p in projects]})")
    model = ph.adb("shell", "getprop", "ro.product.model").strip()
    info = {"build": state0.get("version"), "serial": args.serial, "phone": model, "started": stamp, "user_project": user,
            "user_playhead": state0.get("playhead"), "user_mode": state0.get("mode"), "user_selected": state0.get("selected")}
    print(json.dumps(info), flush=True)
    try:
        reg = next((p["id"] for p in projects if p["name"] == PROJECT), None)
        if reg:
            ph.callj("projects", {"op": "open", "id": reg})
        else:
            ph.callj("projects", {"op": "new", "name": PROJECT, "width": 1920, "height": 1080, "fps": FPS})
        time.sleep(2)
        # the test clips: on the phone once, imported once
        items = lambda: {r["name"]: r["item"] for r in ph.exec("project.items")["rows"]}
        names = items()
        required_files = list(CLIPS) + list(PUSH_FILES)
        missing = [f for f in required_files if f not in names]
        if missing:
            # (a folder of its own per test project: another set of clips under the same names)
            sub = "regress" if PROJECT == "Klipp regress" else "regress_" + re.sub(r"[^A-Za-z0-9]+", "_", PROJECT)
            ph.adb("shell", "mkdir", "-p", f"{ph.files}/{sub}")
            for f in missing:
                ph.adb("push", os.path.join(args.clips, f), f"{ph.files}/{sub}/{f}")
            ph.call("import_files", {"paths": [f"/storage/emulated/0/Android/data/{ph.pkg}/files/{sub}/{f}" for f in missing]})
            for _ in range(60):
                time.sleep(2)
                names = items()
                if all(f in names for f in required_files):
                    break
            if any(f not in names for f in required_files):
                raise RuntimeError(f"test clips not imported: {[f for f in required_files if f not in names]}")
        if args.apv:
            paths = [x.strip() for x in args.apv.split(",") if x.strip()]
            want_names = [os.path.basename(x) for x in paths]
            todo = [x for x in paths if os.path.basename(x) not in names]
            if todo:
                ph.call("import_files", {"paths": todo})
                for _ in range(300):
                    time.sleep(2)
                    names = items()
                    if all(f in names for f in want_names):
                        break
            lost = [f for f in want_names if f not in names]
            if lost:
                raise RuntimeError(f"not imported: {lost} – the app reads only files it may (its own files dir, or the picker's)")
            rows = {r["name"]: r for r in ph.exec("project.items")["rows"]}
            fps_of = lambda f: float(re.match(r"[\d.]+", rows[f]["cells"].get("Frame Rate", "0")).group(0))
            CAMERA[:] = sorted(want_names, key=fps_of)
            for i, f in enumerate(CAMERA):
                CLIPS[f] = (100 + i, fps_of(f))
            run.log("camera clips: " + ", ".join(f"{f} {CLIPS[f][1]} fps" for f in CAMERA))
        ph.call("set_mode", {"mode": "edit"})
        tests = [("R1 straight", "R1"), ("R2 layers", "R2"), ("R3 push", "R3"), ("R4 stab cache", "R4"), ("R5 freeze", "R5"), ("R6 push oracle", "R6")] + ([("A1 camera", "A1")] if args.apv else [])
        for name, tag in tests:
            if not want(tag):
                continue
            build(ph, name, names)
            time.sleep(3)
            tl = ph.callj("timeline")
            exp, skip, base = expected(tl)
            json.dump(tl, open(os.path.join(out, f"{tag}_timeline.json"), "w"))
            run.log(f"{tag}: built sequence {name!r} (steps: tools/regress/regress.py build()); clips: " + "; ".join(
                f"{c['name']} {c['start']:.3f}+{c['duration']:.3f} in {c['sourceIn']:.4f} ×{c['speed']}" for t in tl["sequence"]["video"] for c in t["clips"]))
            end = max(c["start"] + c["duration"] for t in tl["sequence"]["video"] for c in t["clips"])
            if tag == "R6":
                # the push against its reference in every render (an error repeated in each would pass a repeat test)
                for mode in ("export", "chunk"):
                    for attempt in range(max(1, args.repeat)):
                        label = f"{mode}_{attempt}"
                        run.push_file(tag, label, run.render(f"{tag}_{label}", 0, end, mode), tl)
                continue
            if tag == "A1":
                # proxies, node caches and the transition first (the user's own order: pre-render, then look)
                # (heavy pieces render only on Render unless the user turned auto render on: press it, as the user does)
                pressed = False
                for _ in range(600):
                    st = ph.callj("render_diag", {"op": "status"})
                    busy = st["proxies"] or st["nodeCache"] or st["render"] is not None
                    pending = "ready=false heavy=true" in ph.call("render_diag", {"op": "chunks"})
                    if not busy and not pending:
                        break
                    if not busy and pending:
                        if pressed:
                            run.log("A1: heavy pieces still not ready after Render, nothing in progress")
                            break
                        b = ph.node_bounds("#renderChip")
                        if b:
                            ph.call("tap", {"x": (b[0] + b[2]) / 2, "y": (b[1] + b[3]) / 2})
                            run.log("A1: pressed Render")
                            pressed = True
                    time.sleep(3)
                run.log(f"A1: made: {ph.call('render_diag', {'op': 'status'})}")
                ref = run.render(f"{tag}_export", 0, end, "export")
            else:
                end_slot = math.ceil(end * FPS - 1e-6)
                for mode in ("export", "chunk"):
                    run.identity_file(tag, mode, run.render(f"{tag}_{mode}", 0, end, mode), exp, skip, end_slot=end_slot)
            if tag == "R3":
                # transitions play only pre-rendered: wait for them
                for _ in range(90):
                    if "ready=false heavy=true" not in ph.call("render_diag", {"op": "chunks"}):
                        break
                    time.sleep(2)
            if tag == "R5":
                # (live playback plays freeze frames as short videos made in the background: a few seconds each)
                time.sleep(12)
            if tag == "R4":
                # playback reads the film clips from their node cache once it is there: wait for it
                st = wait_node_cache(ph)
                run.result(tag, "node cache", all(v and ("cached" in v.lower() or "cachad" in v.lower()) for v in st.values()),
                           ", ".join(f"clip {c}: {v}" for c, v in st.items()))
            # the paused picture at every cut (the slot before, on and after it) and a few slots inside clips
            cuts = sorted(set(round(c["start"] * FPS) for t in tl["sequence"]["video"] for c in t["clips"]))
            slots = sorted(set([k + d for k in cuts for d in (-1, 0, 1)] + [round(end * FPS * f) for f in (0.2, 0.5, 0.8)]))
            if tag == "A1":
                run.appearance(tag, ref, slots, "as opened", args.tol)
            else:
                run.paused(tag, exp, skip, slots, "as opened")
            run.log(f"{tag}: chunks before playback:\n" + ph.call("render_diag", {"op": "chunks"}))
            ph.call("render_diag", {"op": "rebuild"})
            run.log(f"{tag}: change before 'after rebuild': render_diag op=rebuild (the player builds its composition again, nothing deleted)")
            time.sleep(2)
            if tag == "A1":
                run.appearance(tag, ref, slots[::2], "after rebuild", args.tol)
            else:
                run.paused(tag, exp, skip, slots[::2], "after rebuild")
            secs = int(min(end, 16) + 2)
            # (the timing is the live preview's: playback renders parts it didn't keep up with ahead – held during the plays)
            ph.call("render_diag", {"op": "hold"})
            run.play(tag, 0.0, secs, end, "first play", base, skip)
            ph.call("render_diag", {"op": "rebuild"})
            time.sleep(1.5)
            run.play(tag, 0.0, secs, end, "right after rebuild", base, skip)
            if tag == "R3" and args.repeat > 1:
                run.repeat(tag, 0, end, args.repeat, "chunk")
                run.repeat(tag, 0, end, args.repeat, "export")
                # playback, n times from the start right after a rebuild: failures of attempts
                fails = 0
                for i in range(args.repeat):
                    ph.call("render_diag", {"op": "rebuild"})
                    time.sleep(1.5)
                    n0 = len(run.results)
                    run.play(tag, 0.0, secs, end, f"attempt {i + 1}", base, skip)
                    fails += not run.results[-1]["ok"]
                run.result(tag, f"playback attempts", fails == 0, f"{fails} of {args.repeat} attempts failed")
    finally:
        # (an error before any failed check must not leave a green report and exit code 0)
        pending_error = sys.exc_info()[1]
        if pending_error is not None:
            run.result("runner", "execution", False, f"{type(pending_error).__name__}: {pending_error}")
        try:
            ph.call("render_diag", {"op": "resume"})
        except Exception:
            pass
        try:
            if user:
                ph.callj("projects", {"op": "open", "id": user})
                time.sleep(2)
                if info["user_playhead"] is not None:
                    ph.call("set_playhead", {"seconds": info["user_playhead"]})
                if info["user_mode"]:
                    ph.call("set_mode", {"mode": info["user_mode"]})
                ph.call("select", {"clip": info["user_selected"]} if info["user_selected"] is not None else {})
        except Exception as e:
            print("restore failed:", e, file=sys.stderr)
        info["finished"] = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        json.dump({"info": info, "results": run.results}, open(os.path.join(out, "report.json"), "w"), indent=1)
        with open(os.path.join(out, "report.md"), "w") as f:
            f.write(f"# Klipp regression {info['build']} on {info.get('phone')} ({args.serial}), {stamp}\n\n"
                    "Identity = the exact source frame per slot (FrameGrid rule); timing = CutTrace (main player, GL chain before "
                    "presentation); repeat = failures of attempts. Steps and the chunks: steps.log; timelines: *_timeline.json; "
                    "traces: *_trace_*.txt; pictures of wrong slots: *.png.\n\n| test | check | result | summary |\n|---|---|---|---|\n")
            for r in run.results:
                f.write(f"| {r['test']} | {r['check']} | {'PASS' if r['ok'] else '**FAIL**'} | {r['summary']} |\n")
            for r in run.results:
                if not r["ok"] and r.get("detail"):
                    f.write(f"\n## {r['test']} / {r['check']}\n\n```\n{json.dumps(r['detail'], indent=1)[:4000]}\n```\n")
        fails = sum(1 for r in run.results if not r["ok"])
        print(f"\n{len(run.results) - fails}/{len(run.results)} checks passed – report in {out}")
        sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
