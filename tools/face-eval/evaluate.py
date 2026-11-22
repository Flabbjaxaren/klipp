"""Face following before/after: the YuNet baseline (yunet_base.py) against MediaPipe + identity + One Euro
(mp_source.py, follow.py) on the derf clips and synthesized cases (clips.py).

    uv run --python 3.12 --with mediapipe==1.2.3 --with opencv-python --with numpy \
        python -I tools/face-eval/evaluate.py --clips <folder> --model <face_landmarker.task> [--fetch] [--fixtures <dir>]

--fetch downloads the two derf clips into the folder; the model is the same file the app bundles
(app/src/main/assets/face_landmarker.task). --fixtures writes the Kotlin unit test's fixtures (recorded face lists and
the prototype's results) into <dir>.

Truth: the face as MediaPipe's landmarker finds it on every frame of the full source clip (IMAGE mode, whole frame,
no smoothing): centre = the landmarks' mean, size = their RMS radius in 3D. Each pipeline's constant offset from it
(a box centre is not the landmarks' mean) is removed before the error is taken. In the synthesized clips the truth
is moved and scaled with the source.

Metrics (frames where the face is visible):
  lost     frames where the follower had no face (the track interpolates over them)
  wrong    frames where the result is on another face or > 1 face size off
  err      RMS centre error, % of the face size (offset removed)
  fast     the same over frames where the face moves > 4 % of its size per frame
  jit      RMS frame-to-frame acceleration of the centre, % of the face size (noise; low = calm)
  sjit     the same for ln(size), % (size pumping from frame to frame)
  serr     RMS of ln(size / true size), % (offset removed; slow size errors, e.g. when the head turns)
  lag      frames by which the result trails the truth (cross-correlation of the velocities)
  ms       Mac CPU time per frame
"""

import argparse
import json
import math
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clips  # noqa: E402
import follow  # noqa: E402
import mp_source  # noqa: E402
import yunet_base  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
YUNET = os.path.join(ROOT, "rust", "models", "face_detection_yunet_2023mar.onnx")
FOREMAN_VISIBLE = 187  # foreman leaves the picture at frame 187 (the camera pans to the crane)


def truth_of(model, frames):
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision
    h, w = frames[0].shape[:2]
    o = vision.FaceLandmarkerOptions(base_options=BaseOptions(model_asset_path=model), running_mode=vision.RunningMode.IMAGE, num_faces=1)
    out = []
    with vision.FaceLandmarker.create_from_options(o) as lm:
        for f in frames:
            r = lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(f)))
            if r.face_landmarks:
                ob = follow.observe([(p.x, p.y, p.z) for p in r.face_landmarks[0]], w, h)
                out.append((ob.cx, ob.cy, ob.size))
            else:
                out.append(None)
    return out


def fill(truth, n):
    """Truth over a range where the face is visible but the reference landmarker missed a frame: interpolated."""
    idx = [i for i in range(n) if truth[i] is not None]
    arr = np.array([truth[i] for i in idx])
    return np.stack([np.interp(range(n), idx, arr[:, k]) for k in range(3)], 1)


def finish(per_frame, fps, smoother):
    """per-frame (cx, cy, size, angle) or None → smoothed, interpolated over the missing frames (as WindowTracker.merge)."""
    n = len(per_frame)
    found = [(i / fps,) + tuple(p) for i, p in enumerate(per_frame) if p is not None]
    if not found:
        return None
    sm = smoother(found)
    t = [s[0] for s in sm]
    out = np.stack([np.interp(np.arange(n) / fps, t, [s[k] for s in sm]) for k in (1, 2, 3, 4)], 1)
    return out


def metrics(track, raw_found, truth, visible, other=None):
    """track: n×4 smoothed (cx, cy, size, angle); truth: n×3; visible: frame indices."""
    vis = np.array(visible)
    lost = int(sum(1 for i in vis if not raw_found[i]))
    if track is None:
        return dict(lost=lost, wrong=len(vis), err=float("nan"), fast=float("nan"), jit=float("nan"), sjit=float("nan"), serr=float("nan"), lag=float("nan"))
    tr, tt = track[vis], truth[vis]
    off = np.median(tr[:, :2] - tt[:, :2], 0)
    d = np.hypot(*(tr[:, :2] - off - tt[:, :2]).T) / tt[:, 2]
    wrong = d > 1.0
    if other is not None:
        do = np.hypot(*(tr[:, :2] - other[vis][:, :2]).T)
        wrong |= do < np.hypot(*(tr[:, :2] - tt[:, :2]).T)
    good = ~wrong
    err = float(np.sqrt(np.mean(d[good] ** 2)) * 100) if good.any() else float("nan")
    speed = np.r_[0, np.hypot(*np.diff(tt[:, :2], axis=0).T) / tt[1:, 2]]
    fastm = good & (speed > 0.04)
    fast = float(np.sqrt(np.mean(d[fastm] ** 2)) * 100) if fastm.any() else float("nan")
    acc = np.hypot(*np.diff(tr[:, :2], 2, axis=0).T) / tt[1:-1, 2]
    jit = float(np.sqrt(np.mean(acc ** 2)) * 100)
    ls = np.log(tr[:, 2])
    sjit = float(np.sqrt(np.mean(np.diff(ls, 2) ** 2)) * 100)
    rel = ls - np.log(tt[:, 2])
    serr = float(np.sqrt(np.mean((rel - np.median(rel)) ** 2)) * 100)
    vt = np.diff(tt[:, 0])
    vr = np.diff(tr[:, 0])
    lags = range(-5, 6)
    cc = [np.dot(vt[max(0, -k):len(vt) - max(0, k)], vr[max(0, k):len(vr) - max(0, -k)]) for k in lags]
    lag = int(list(lags)[int(np.argmax(cc))])
    return dict(lost=lost, wrong=int(wrong.sum()), err=err, fast=fast, jit=jit, sjit=sjit, serr=serr, lag=lag)


def run_yunet(frames, want, fps, work=yunet_base.WORK):
    h, w = frames[0].shape[:2]
    t0 = time.time()
    yunet_base.WORK = work
    picks = yunet_base.run(YUNET, frames, want)
    yunet_base.WORK = 320
    ms = (time.time() - t0) * 1000 / len(frames)
    px = yunet_base.as_pixels(picks, w, h)
    return finish(px, fps, yunet_base.smooth), [p is not None for p in px], ms


def run_mp(model, frames, want, fps, gray=False, record=None):
    t0 = time.time()
    picks = mp_source.run(model, frames, want, gray, record)
    ms = (time.time() - t0) * 1000 / len(frames)
    px = [None if p is None else (p.cx, p.cy, p.size, p.angle) for p in picks]
    return finish(px, fps, follow.smooth), [p is not None for p in px], ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--fixtures")
    ap.add_argument("--gray", action="store_true", help="also MediaPipe on grey frames")
    ap.add_argument("--yunet640", action="store_true", help="also the baseline with a 640 px work image (instead of 320)")
    ap.add_argument("--only")
    ap.add_argument("--json")
    a = ap.parse_args()
    if a.fetch:
        clips.fetch(a.clips)
    fore, fps = clips.read_y4m(os.path.join(a.clips, "foreman_cif.y4m"))
    aki, _ = clips.read_y4m(os.path.join(a.clips, "akiyo_cif.y4m"))
    sh, sw = fore[0].shape[:2]
    t_fore = fill(truth_of(a.model, fore[:FOREMAN_VISIBLE]), FOREMAN_VISIBLE)
    t_aki = fill(truth_of(a.model, aki), len(aki))

    def mapped(truth, placements):
        return np.array([pl.map(tx / sw, ty / sh, ts / sw, sw, sh, 1, 1) for (tx, ty, ts), pl in zip(truth, placements)])

    cases = []
    # (name, frames, want (x, y, r) px, truth n×3 px, visible frames, other face's truth)
    cases.append(("akiyo", aki, (t_aki[0, 0], t_aki[0, 1], t_aki[0, 2] * 1.2), t_aki, range(len(aki)), None))
    tf = np.vstack([t_fore, np.zeros((len(fore) - FOREMAN_VISIBLE, 3)) + t_fore[-1]])
    cases.append(("foreman", fore, (t_fore[0, 0], t_fore[0, 1], t_fore[0, 2] * 1.2), tf, range(FOREMAN_VISIBLE), None))
    two, (cw, ch), fpl, apl = clips.two_faces(fore, aki)
    n2 = len(two)
    tfm = mapped(np.vstack([tf[:n2]]), fpl)
    tam = mapped(t_aki[:n2], apl)
    vis2 = [i for i in range(min(n2, FOREMAN_VISIBLE)) if not 150 <= i < 162]
    cases.append(("two faces", two, (tfm[0, 0], tfm[0, 1], tfm[0, 2] * 1.2), tfm, vis2, tam))
    for name, sc in (("mid 55%", 0.55), ("far 30%", 0.3)):
        fr, (cw, ch), pl = clips.scaled(fore[:FOREMAN_VISIBLE], sc)
        tm = mapped(t_fore, pl)
        cases.append((name, fr, (tm[0, 0], tm[0, 1], tm[0, 2] * 1.2), tm, range(len(fr)), None))
        if sc == 0.55:
            cases.append((name + " (reframe)", fr, (cw / 2, ch / 2, max(cw, ch) / 2), tm, range(len(fr)), None))

    rows = []
    fixtures = {}
    for name, frames, want, truth, vis, other in cases:
        if a.only and a.only not in name:
            continue
        variants = [("YuNet (before)", lambda: run_yunet(frames, want, fps))]
        if a.yunet640:
            variants.append(("YuNet 640 px", lambda: run_yunet(frames, want, fps, 640)))
        rec = []
        variants.append(("MediaPipe (after)", lambda: run_mp(a.model, frames, want, fps, False, rec)))
        if a.gray:
            variants.append(("MediaPipe grey", lambda: run_mp(a.model, frames, want, fps, True)))
        for vname, fn in variants:
            track, found, ms = fn()
            m = metrics(track, found, truth, list(vis), other)
            m["ms"] = ms
            rows.append((name, vname, m))
            print(f"{name:20s} {vname:18s} " + " ".join(f"{k}={v:.1f}" if isinstance(v, float) else f"{k}={v}" for k, v in m.items()), flush=True)
        if rec:
            h, w = frames[0].shape[:2]
            fixtures[name] = {"w": w, "h": h, "fps": fps, "want": list(want), "frames": rec,
                              "landmarks": next((r["landmarks"][0] for r in rec if r.get("landmarks")), None)}
    print()
    print("| clip | pipeline | lost | wrong | err % | fast % | jit % | sjit % | serr % | lag | ms |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for name, vname, m in rows:
        print(f"| {name} | {vname} | {m['lost']} | {m['wrong']} | {m['err']:.1f} | {m['fast']:.1f} | {m['jit']:.2f} | {m['sjit']:.2f} | {m['serr']:.1f} | {m['lag']} | {m['ms']:.1f} |")
    if a.json:
        json.dump([{"clip": n, "pipeline": v, **m} for n, v, m in rows], open(a.json, "w"), indent=1)
    if a.fixtures:
        write_fixtures(a.fixtures, fixtures)


def write_fixtures(folder, fixtures):
    """The Kotlin test's fixtures (FaceFollowTest): per case the face lists MediaPipe gave the follower in every frame
    (rounded), and what this prototype makes of exactly those – the request and pick in every frame and the smoothed
    track. Plus one face's raw landmarks with its observation (for observe())."""
    os.makedirs(folder, exist_ok=True)
    keep = {"akiyo": 90, "two faces": 200, "far 30%": 120, "mid 55% (reframe)": 60}
    for name, hi in keep.items():
        if name not in fixtures:
            continue
        fx = fixtures[name]
        frames = fx["frames"][:hi]
        f = follow.Follower(fx["w"], fx["h"], *fx["want"])
        steps = []
        for fr in frames:
            faces = [[round(v, 5) for v in o] for o in fr["faces"]]
            req = f.request()
            pick = f.step([follow.Obs.from_list(v) for v in faces])
            steps.append({"req": req.to_list(), "faces": faces, "pick": None if pick is None else pick.to_list()})
        found = [(i / fx["fps"],) + tuple(s["pick"][:4]) for i, s in enumerate(steps) if s["pick"]]
        out = {"w": fx["w"], "h": fx["h"], "fps": fx["fps"], "want": fx["want"],
               "note": "faces: MediaPipe Face Landmarker 1.2.3 in the prototype (tools/face-eval/evaluate.py --fixtures)",
               "steps": steps, "smoothed": [list(s) for s in follow.smooth(found)]}
        lm = fx.get("landmarks")
        if lm:
            out["landmarks"] = [[round(v, 7) for v in p] for p in lm]
            ob = follow.observe(out["landmarks"], fx["w"], fx["h"])
            out["observed"] = ob.to_list()
        name_f = name.replace(" ", "_").replace("%", "").replace("(", "").replace(")", "")
        json.dump(out, open(os.path.join(folder, f"face_{name_f}.json"), "w"), separators=(",", ":"))


if __name__ == "__main__":
    main()
