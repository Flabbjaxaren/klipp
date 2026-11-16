#!/opt/homebrew/bin/python3
"""
The regression run's own checks against deliberately broken files: each must fail, the unbroken file must pass.

From a run's output (R1_export.mp4 + R1_timeline.json, R6_export_0.mp4 + R6_timeline.json) it makes, losslessly, the
file as it is and broken variants of it, and runs them through the same decoding and checks as the phone run:

  R1 as rendered                         → pass
  R1 without its last two frames         → completeness (missing_slots)
  R1 with its last frame the one before  → identity
  R6 as rendered                         → pass
  R6 cut straight instead of the push    → push reference
  R6 with the incoming picture black     → push reference
  R6 pushing the other way               → push reference
  R6's black-incoming push, five times   → each of the five

Usage: selftest.py <run output dir>
"""
import json, math, os, subprocess, sys, tempfile

import numpy as np

sys.argv, ARGS = sys.argv[:1], sys.argv[1:]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regress as r  # noqa: E402

W, H = 640, 360


def frames_of(path):
    """The file's frames (gray, W×H) and their times."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-fps_mode", "passthrough", "-vf", f"scale={W}:{H}:flags=area,format=gray",
                          "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    imgs = np.frombuffer(raw, np.uint8).reshape(-1, H, W).copy()
    ts = [c[0] for c in r.video_codes(path)]
    assert len(ts) == len(imgs)
    return list(imgs), ts


def write(imgs, path):
    """Losslessly, at 25 fps from 0: frame k at k / 25 s."""
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}", "-r", "25", "-i", "-",
                          "-c:v", "libx264", "-qp", "0", "-pix_fmt", "yuv444p", path], stdin=subprocess.PIPE)
    for im in imgs:
        p.stdin.write(np.ascontiguousarray(im, np.uint8).tobytes())
    p.stdin.close()
    if p.wait() != 0:
        raise RuntimeError("ffmpeg failed")


class Sink(r.Run):
    def __init__(self, out):
        self.ph, self.out, self.results = None, out, []
        os.makedirs(out, exist_ok=True)


def main():
    src = ARGS[0]
    work = tempfile.mkdtemp(prefix="selftest_")
    run = Sink(work)
    cases = []

    # R1: completeness and identity
    tl1 = json.load(open(os.path.join(src, "R1_timeline.json")))
    exp, skip, _ = r.expected(tl1)
    end = max(c["start"] + c["duration"] for t in tl1["sequence"]["video"] for c in t["clips"])
    end_slot = math.ceil(end * r.FPS - 1e-6)
    imgs, _ = frames_of(os.path.join(src, "R1_export.mp4"))
    variants = {
        "R1 as rendered": (imgs, True),
        "R1 without its last two frames": (imgs[:-2], False),
        "R1 with its last frame the one before": (imgs[:-1] + [imgs[-2]], False),
    }
    for name, (v, should_pass) in variants.items():
        p = os.path.join(work, name.replace(" ", "_") + ".mp4")
        write(v, p)
        run.identity_file("R1", name, p, exp, skip, end_slot=end_slot)
        cases.append((name, should_pass, run.results[-1]))

    # R6: the push against its reference
    tl6 = json.load(open(os.path.join(src, "R6_timeline.json")))
    track = tl6["sequence"]["video"][0]
    tr = track["transitions"][0]
    clips = {c["id"]: c for c in track["clips"]}
    t0, t1, cut = tr["start"], tr["start"] + tr["duration"], clips[tr["to"]]["start"]
    imgs, ts = frames_of(os.path.join(src, "R6_export_0.mp4"))
    window = [i for i, t in enumerate(ts) if t0 <= t < t1]
    plain_dark = imgs[[i for i, t in enumerate(ts) if t < t0 - 0.2][-1]]
    plain_light = imgs[[i for i, t in enumerate(ts) if t > t1 + 0.2][0]]

    straight = list(imgs)
    for i in window:
        straight[i] = plain_dark if ts[i] < cut else plain_light
    black = list(imgs)
    for i in window:
        e = r.push_progress((ts[i] - t0) / (t1 - t0), (cut - t0) / (t1 - t0))
        im = imgs[i].copy()
        im[:, int(math.ceil((1.0 - e) * W)):] = 0
        black[i] = im
    other_way = list(imgs)
    for i in window:
        other_way[i] = imgs[i][:, ::-1]
    variants = {
        "R6 as rendered": (imgs, True),
        "R6 cut straight instead of the push": (straight, False),
        "R6 with the incoming picture black": (black, False),
        "R6 pushing the other way": (other_way, False),
    }
    for name, (v, should_pass) in variants.items():
        p = os.path.join(work, name.replace(" ", "_") + ".mp4")
        write(v, p)
        run.push_file("R6", name, p, tl6)
        cases.append((name, should_pass, run.results[-1]))
    p = os.path.join(work, "R6_with_the_incoming_picture_black.mp4")
    for k in range(5):
        run.push_file("R6", f"the same black push, render {k + 1}", p, tl6)
        cases.append((f"R6 black incoming, render {k + 1} of 5", False, run.results[-1]))

    print()
    ok = True
    for name, should_pass, res in cases:
        right = res["ok"] == should_pass
        ok &= right
        kinds = sorted({e["kind"] for e in (res.get("detail") or {}).get("layout", [])}) if isinstance(res.get("detail"), dict) else []
        print(f"{'RIGHT' if right else 'WRONG'}  {name}: {'passes' if res['ok'] else 'fails'} – {res['summary'][:110]}" + (f" {kinds}" if kinds else ""))
    print(f"\n{'all checks behave' if ok else 'A CHECK DID NOT BEHAVE'} ({work})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
