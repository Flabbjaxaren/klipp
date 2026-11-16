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

PROJECT = "Klipp regress"
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
    """Per frame of a file: (pts s, code)."""
    w, h = 320, 180
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-fps_mode", "passthrough", "-vf", f"scale={w}:{h}:flags=area,format=gray",
                          "-f", "rawvideo", "-"], capture_output=True).stdout
    ts = [float(x.split(",")[0]) for x in subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time",
                                                          "-of", "csv=p=0", path], capture_output=True, text=True).stdout.split() if x.split(",")[0].strip()]
    a = np.frombuffer(raw, np.uint8).reshape(-1, h, w)
    return [(ts[i], decode_gray(a[i]), a[i]) for i in range(min(len(a), len(ts)))]


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
            if c["name"] not in CLIPS or c.get("reverse") or c.get("hold") is not None or not c.get("enabled", True):
                continue
            cid, sfps = CLIPS[c["name"]]
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

    def identity_file(self, test, check, path, exp, skip, first_slot=0):
        codes = video_codes(path)
        bad = []
        for i, (pts, code, img) in enumerate(codes):
            k = first_slot + round(pts * FPS)
            if k in skip or k not in exp:
                continue
            if exp[k] // 2048 in CENTRAL:
                code = decode_central(img)
            if code != exp[k]:
                bad.append({"slot": k, "t": round(k / FPS, 3), "want": describe(exp[k]), "got": describe(code), "frame": i})
        checked = sum(1 for (pts, _, _) in codes if (first_slot + round(pts * FPS)) in exp and (first_slot + round(pts * FPS)) not in skip)
        for b in bad[:6]:
            p = os.path.join(self.out, f"{test}_{check}_slot{b['slot']}.png".replace(" ", "_"))
            subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-i", path, "-vf", f"select=eq(n\\,{b['frame']}),scale=640:-1", "-frames:v", "1", p])
            b["image"] = os.path.basename(p)
        self.result(test, check, not bad and checked > 0, f"{checked - len(bad)}/{checked} slots right" + (f", first wrong at {bad[0]['t']} s: {bad[0]['want']} expected, {bad[0]['got']} shown" if bad else ""), bad or None)

    def program_rect(self):
        r = self.ph.node_bounds("#programStack")
        if not r:
            raise RuntimeError("program view not found (is the editor showing?)")
        return r

    def paused(self, test, exp, skip, slots, label):
        r = self.program_rect()
        bad, n = [], 0
        for k in slots:
            if k in skip or k not in exp:
                continue
            self.ph.call("set_playhead", {"seconds": k / FPS})
            time.sleep(2.2)
            p = os.path.join(self.out, f"{test}_paused_{label}_{k}.png".replace(" ", "_"))
            self.ph.screencap(p)
            w = int(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width", "-of", "csv=p=0", p], capture_output=True, text=True).stdout.split()[0])
            k_ = w / (r[0] + r[2])            # the program view is centred: its left + right edge = the window's width
            code = decode(png_gray(p, tuple(round(v * k_) for v in r)), exp[k] // 2048 in CENTRAL)
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
    ap.add_argument("--apv", default="", help="comma-separated paths on the phone of extra camera clips (test A1)")
    ap.add_argument("--only", default="", help="e.g. R1,R3")
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--out", default="")
    ap.add_argument("--restore-to", default="", help="the project to open afterwards when the test project is the open one")
    args = ap.parse_args()
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
        missing = [f for f in CLIPS if f not in names]
        if missing:
            ph.adb("shell", "mkdir", "-p", f"{ph.files}/regress")
            for f in missing:
                ph.adb("push", os.path.join(args.clips, f), f"{ph.files}/regress/{f}")
            ph.call("import_files", {"paths": [f"/storage/emulated/0/Android/data/{ph.pkg}/files/regress/{f}" for f in missing]})
            for _ in range(60):
                time.sleep(2)
                names = items()
                if all(f in names for f in CLIPS):
                    break
        ph.call("set_mode", {"mode": "edit"})
        tests = [("R1 straight", "R1"), ("R2 layers", "R2"), ("R3 push", "R3"), ("R4 stab cache", "R4")]
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
            run.identity_file(tag, "export", run.render(f"{tag}_export", 0, end, "export"), exp, skip)
            run.identity_file(tag, "chunk", run.render(f"{tag}_chunk", 0, end, "chunk"), exp, skip)
            if tag == "R3":
                # transitions play only pre-rendered: wait for them
                for _ in range(90):
                    if "ready=false heavy=true" not in ph.call("render_diag", {"op": "chunks"}):
                        break
                    time.sleep(2)
            if tag == "R4":
                # playback reads the film clips from their node cache once it is there: wait for it
                st = wait_node_cache(ph)
                run.result(tag, "node cache", all(v and ("cached" in v.lower() or "cachad" in v.lower()) for v in st.values()),
                           ", ".join(f"clip {c}: {v}" for c, v in st.items()))
            # the paused picture at every cut (the slot before, on and after it) and a few slots inside clips
            cuts = sorted(set(round(c["start"] * FPS) for t in tl["sequence"]["video"] for c in t["clips"]))
            slots = sorted(set([k + d for k in cuts for d in (-1, 0, 1)] + [round(end * FPS * f) for f in (0.2, 0.5, 0.8)]))
            run.paused(tag, exp, skip, slots, "as opened")
            run.log(f"{tag}: chunks before playback:\n" + ph.call("render_diag", {"op": "chunks"}))
            ph.call("render_diag", {"op": "rebuild"})
            run.log(f"{tag}: change before 'after rebuild': render_diag op=rebuild (the player builds its composition again, nothing deleted)")
            time.sleep(2)
            run.paused(tag, exp, skip, slots[::2], "after rebuild")
            secs = int(min(end, 16) + 2)
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
