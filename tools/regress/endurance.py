#!/usr/bin/env -S uv run --no-project --python 3.13 --with numpy==2.3.5 python
"""
Endurance and resources: one test project played, sought, edited and exported over and over for --minutes, with the
app's resources sampled every cycle – to see whether they settle or keep growing, and whether background work starts
again after an export.

The sequence "E1 endurance" (made once in the test project): five clips on V1 with pushes between them, film with node
cache on two, an upper clip on V2 and one on V3. Every cycle:
  seeks to five places (paused), plays 8 s with the cut trace on (late and dropped frames of the main chain),
  every 3rd cycle an edit (the V3 clip moved, back and forth), every 6th an export through the dialog,
and samples: PSS and its parts (dumpsys meminfo), threads, the codecs the app holds (media.resource_manager), the
thermal status and the hottest sensor, pre-render progress, errors in the log.

Exports go to Movies/Klipp: the ones this run made (new files there) are removed at the end.

Usage: endurance.py --serial S --port P --clips DIR [--minutes 22] [--out DIR] [--project NAME]
"""
import argparse, datetime, json, os, random, re, sys, time

sys.argv, _argv = sys.argv[:1], sys.argv[1:]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regress as r  # noqa: E402
sys.argv += _argv

FILES = ["rq30.mp4", "rq25.mp4", "rq60.mp4", "rq24.mp4", "rq50.mp4"]
SEQ = "E1 endurance"


def build(ph, items):
    for row in ph.exec("project.items")["rows"]:
        if row["name"] == SEQ:
            ph.exec("sequence.open", {"item": row["item"]})
            # (set again: sequences built before the fix carry a film node the app couldn't take, see regress.FILM)
            v1 = ph.callj("timeline")["sequence"]["video"][0]["clips"]
            for i in (1, 3):
                r.set_film(ph, v1[i]["id"], cache=True)
            return
    ph.exec("file.newSequence", {"name": SEQ, "width": 1920, "height": 1080, "fps": r.FPS})
    tracks = [t["id"] for t in ph.callj("timeline")["sequence"]["video"]]
    ids = [r.place(ph, items[f], "V1", 2.0 * i, 1.5, 2.0) for i, f in enumerate(FILES)]
    for cid in ids[1:]:
        ph.exec("sequence.applyVideoTransition", {"clip": cid, "effect": "push", "frames": 15, "edge": "in", "align": "center"})
    for i in (1, 3):
        r.set_film(ph, ids[i], cache=True)
    r.place(ph, items["rq60.mp4"], tracks[1], 1.2, 2.0, 2.4)
    r.place(ph, items["rq120.mp4"], tracks[2], 6.0, 3.0, 1.6)


class Sampler:
    def __init__(self, ph, out):
        self.ph, self.out = ph, out
        self.rows = []
        self.f = open(os.path.join(out, "samples.jsonl"), "a")

    def pid(self):
        return self.ph.adb("shell", "pidof", self.ph.pkg, check=False).strip().split(" ")[0]

    def sample(self, cycle, phase, extra=None):
        ph, pid = self.ph, self.pid()
        row = {"t": round(time.time(), 1), "cycle": cycle, "phase": phase, "pid": pid}
        mem = ph.adb("shell", "dumpsys", "meminfo", pid, check=False) if pid else ""
        def kb(label):
            m = re.search(rf"^\s*{label}\s+(\d+)", mem, re.M)
            return int(m.group(1)) if m else None
        m = re.search(r"TOTAL PSS:\s+(\d+)", mem) or re.search(r"^\s*TOTAL\s+(\d+)", mem, re.M)
        row["pss_kb"] = int(m.group(1)) if m else None
        def summary(label):
            m = re.search(rf"^\s*{label}:\s+(\d+)", mem, re.M)
            return int(m.group(1)) if m else None
        row["native_kb"] = summary("Native Heap")
        row["java_kb"] = summary("Java Heap")
        row["graphics_kb"] = summary("Graphics")
        row["gfx_dev_kb"] = kb("Gfx dev")
        row["gl_kb"] = kb("GL mtrack")
        st = ph.adb("shell", "cat", f"/proc/{pid}/status", check=False) if pid else ""
        m = re.search(r"Threads:\s+(\d+)", st)
        row["threads"] = int(m.group(1)) if m else None
        m = re.search(r"VmRSS:\s+(\d+)", st)
        row["rss_kb"] = int(m.group(1)) if m else None
        rm = ph.adb("shell", "dumpsys", "media.resource_manager", check=False)
        # the codecs the app's process holds (its block in the resource manager: one Client per codec)
        block = re.search(rf"^\s*Pid: {pid}\n(.*?)(?=^\s*Pid: |\Z)", rm, re.M | re.S) if pid else None
        names = re.findall(r"Name: (\S+)", block.group(1)) if block else []
        row["codecs"] = len(names)
        row["video_codecs"] = sum(1 for n in names if any(k in n for k in ("avc", "hevc", "vp9", "av1", "apv", "video")))
        row["codec_names"] = names
        th = ph.adb("shell", "dumpsys", "thermalservice", check=False)
        m = re.search(r"Thermal Status:\s*(\d+)", th)
        row["thermal_status"] = int(m.group(1)) if m else None
        hal = th.split("Current temperatures from HAL")[-1].split("Current cooling devices")[0]
        temps = [(float(v), int(t), n) for v, t, n in re.findall(r"Temperature\{mValue=([-\d.]+), mType=(\d+), mName=([^,]+)", hal)]
        # types: 0 CPU, 1 GPU, 3 skin (android.os.Temperature)
        for key, typ in (("temp_cpu", 0), ("temp_gpu", 1), ("temp_skin", 3)):
            v = [x[0] for x in temps if x[1] == typ]
            row[key] = max(v) if v else None
        try:
            ds = ph.callj("render_diag", {"op": "status"})
            row["render"] = ds
        except Exception as e:
            row["render"] = f"error: {e}"[:120]
        row.update(extra or {})
        self.rows.append(row)
        self.f.write(json.dumps(row) + "\n")
        self.f.flush()
        print(f"[{cycle:3d} {phase:8s}] pss {row['pss_kb'] and row['pss_kb'] // 1024} MB  gfx {row['graphics_kb'] and row['graphics_kb'] // 1024}  "
              f"native {row['native_kb'] and row['native_kb'] // 1024}  threads {row['threads']}  codecs {row['codecs']} (video {row['video_codecs']})  "
              f"thermal {row['thermal_status']} skin {row.get('temp_skin')} cpu {row.get('temp_cpu')}  {extra or ''}", flush=True)
        return row


def play(ph, start, secs):
    """Plays secs from start with the cut trace: the main chain's frames, late ones, dropped ones."""
    ph.call("set_playhead", {"seconds": start})
    time.sleep(1.5)
    ph.call("cut_trace", {"trace": True, "mode": 0})
    ph.call("cut_trace", {"read": True})
    b = ph.node_bounds("desc:Play")
    ph.call("tap", {"x": (b[0] + b[2]) / 2, "y": (b[1] + b[3]) / 2})
    lines = []
    t_end = time.time() + secs
    while time.time() < t_end:
        time.sleep(1.5)
        lines += ph.call("cut_trace", {"read": True}).splitlines()
    ph.call("set_playhead", {"seconds": start})
    time.sleep(0.4)
    lines += ph.call("cut_trace", {"read": True}).splitlines()
    ph.call("cut_trace", {"trace": False})
    rows = []
    for l in lines:
        m = re.match(r"(\d+) gl chain=(\S+) pts=(-?\d+) (?:present=(-?\d+)|(DROPPED))", l)
        if m:
            rows.append((int(m.group(1)), m.group(2), int(m.group(3)), int(m.group(4)) if m.group(4) else None, bool(m.group(5))))
    if not rows:
        return {"frames": 0, "late": 0, "dropped": 0}
    from collections import Counter
    main = Counter(x[1] for x in rows).most_common(1)[0][0]
    mr = [x for x in rows if x[1] == main]
    t0 = mr[0][0]
    settled = [x for x in mr if x[0] - t0 > 300_000_000]   # the start's own latency left out (as PlaybackMargin)
    # per timeline slot: the first frame for it (a 60 fps source gives more frames than slots; those are dropped on purpose)
    first = {}
    for x in settled:
        first.setdefault(round(x[2] / 1e6 * r.FPS), x)
    late = [x for x in first.values() if not x[4] and (x[3] is None or x[3] < 0 or x[0] > x[3])]
    dropped = [x for x in first.values() if x[4]]
    return {"slots": len(first), "late": len(late), "dropped": len(dropped)}


def overlay(ph):
    """Whether the export overlay ("Exporting n %") is up."""
    return any(ph.node_bounds(t) for t in ("Exporting", "Exporterar"))


def notified(ph):
    """The export's notification is posted, or its foreground service runs (Android 16 doesn't list the records in
    dumpsys notification: `cmd notification list` gives "0|pkg|4711|null|uid")."""
    n = ph.adb("shell", "cmd", "notification", "list", check=False)
    if re.search(rf"\|{re.escape(ph.pkg)}\|4711\|", n):
        return True
    svc = ph.adb("shell", "dumpsys", "activity", "services", ph.pkg, check=False)
    return re.search(r"isForeground=true foregroundId=4711", svc) is not None


def export(ph, timeout=900):
    """Through the dialog: Export, then Export in it; until the overlay is gone. The export's file must be new in
    Movies/Klipp, and its notification gone afterwards. Returns a summary dict."""
    before = movies(ph)
    b = ph.node_bounds("export")
    ph.call("tap", {"x": (b[0] + b[2]) / 2, "y": (b[1] + b[3]) / 2})
    for _ in range(20):
        time.sleep(0.5)
        g = ph.node_bounds("exp:go")
        if g:
            break
    else:
        raise RuntimeError("the export dialog didn't open")
    t0 = time.time()
    ph.call("tap", {"x": (g[0] + g[2]) / 2, "y": (g[1] + g[3]) / 2})
    seen_overlay = seen_note = False
    while time.time() - t0 < timeout:
        time.sleep(1.0)
        up = overlay(ph)
        seen_overlay |= up
        seen_note |= notified(ph)
        if seen_overlay and not up:
            break
        if not seen_overlay and time.time() - t0 > 15:
            raise RuntimeError("the export overlay never showed")
    else:
        raise RuntimeError("the export didn't end")
    secs = round(time.time() - t0, 1)
    time.sleep(1.0)
    new = [v for k, v in movies(ph).items() if k not in before]
    left = notified(ph)
    return {"seconds": secs, "saved": new, "notification_during": seen_note, "notification_left": left}


def movies(ph):
    out = ph.adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name:relative_path",
                 check=False)
    return {m.group(1): m.group(2) for m in re.finditer(r"_id=(\d+), _display_name=([^,]+), relative_path=Movies/Klipp/", out)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--pkg", default="se.gendut.klipp.designtest")
    ap.add_argument("--clips", required=True)
    ap.add_argument("--minutes", type=float, default=22)
    ap.add_argument("--project", default="Klipp endurance")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    ph = r.Phone(args.serial, args.port, args.pkg)
    out = args.out or os.path.join(r.HERE, "out", datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "-endurance")
    os.makedirs(out, exist_ok=True)
    ph.forward()
    projects = ph.callj("projects", {"op": "list"})
    user = next((p["id"] for p in projects if p["current"]), None)
    if any(p["current"] and p["name"] == args.project for p in projects):
        sys.exit("the test project is open – open the user's first")
    state0 = ph.callj("app_state")
    info = {"build": state0.get("version"), "user_project": user, "user_playhead": state0.get("playhead"),
            "phone": ph.adb("shell", "getprop", "ro.product.model").strip(), "android": ph.adb("shell", "getprop", "ro.build.version.release").strip()}
    json.dump(info, open(os.path.join(out, "info.json"), "w"))
    print(json.dumps(info), flush=True)
    before = movies(ph)
    events = open(os.path.join(out, "events.log"), "a")
    def event(s):
        line = f"{time.strftime('%H:%M:%S')} {s}"
        print(line, flush=True)
        events.write(line + "\n"); events.flush()
    failure = None
    try:
        reg = next((p["id"] for p in projects if p["name"] == args.project), None)
        if reg:
            ph.callj("projects", {"op": "open", "id": reg})
        else:
            ph.callj("projects", {"op": "new", "name": args.project, "width": 1920, "height": 1080, "fps": r.FPS})
        time.sleep(2)
        names = {x["name"]: x["item"] for x in ph.exec("project.items")["rows"]}
        need = FILES + ["rq120.mp4"]
        missing = [f for f in need if f not in names]
        if missing:
            sub = "regress_" + re.sub(r"[^A-Za-z0-9]+", "_", args.project)
            ph.adb("shell", "mkdir", "-p", f"{ph.files}/{sub}")
            for f in missing:
                ph.adb("push", os.path.join(args.clips, f), f"{ph.files}/{sub}/{f}")
            ph.call("import_files", {"paths": [f"/storage/emulated/0/Android/data/{ph.pkg}/files/{sub}/{f}" for f in missing]})
            for _ in range(60):
                time.sleep(2)
                names = {x["name"]: x["item"] for x in ph.exec("project.items")["rows"]}
                if all(f in names for f in need):
                    break
            else:
                raise RuntimeError(f"not imported: {[f for f in need if f not in names]}")
        build(ph, names)
        ph.adb("logcat", "-c", check=False)
        s = Sampler(ph, out)
        s.sample(0, "cold")
        rnd = random.Random(7)
        t_end = time.time() + args.minutes * 60
        cycle = 0
        v3_at = 6.0
        while time.time() < t_end:
            cycle += 1
            for _ in range(5):
                ph.call("set_playhead", {"seconds": round(rnd.uniform(0.0, 9.8), 3)})
                time.sleep(0.6)
            p = play(ph, round(rnd.choice([0.0, 1.0, 3.0, 5.0]), 1), 8)
            extra = {"play": p}
            if cycle % 3 == 0:
                tl = ph.callj("timeline")["sequence"]["video"]
                v3 = tl[2]["clips"][0]
                v3_at = 5.6 if v3_at == 6.0 else 6.0
                ph.exec("timeline.move", {"moves": [{"clip": v3["id"], "track": tl[2]["id"], "time": {"seconds": v3_at}}], "insert": False})
                extra["edit"] = f"V3 to {v3_at}"
            if cycle % 6 == 0:
                ex = export(ph)
                extra["export"] = ex
                event(f"cycle {cycle}: export {ex}")
                if not ex["saved"] or ex["notification_left"]:
                    event(f"cycle {cycle}: EXPORT PROBLEM {ex}")
                time.sleep(5)
                extra["after_export"] = s.sample(cycle, "export+5")["render"]
            errs = ph.adb("logcat", "-d", "-v", "brief", "*:E", check=False)
            pid = s.pid()
            app_errs = [l for l in errs.splitlines() if f"({pid.rjust(5)})" in l or f"({pid})" in l]
            ph.adb("logcat", "-c", check=False)
            # (two platform messages come in every cycle on this phone: the audio HAL's fade type, Codec2's resource query)
            noise = ("get fade type", "Failed to query component interface")
            extra["log_errors"] = sum(1 for l in app_errs if not any(n in l for n in noise))
            extra["fatal"] = sum(1 for l in errs.splitlines() if "FATAL" in l or "AndroidRuntime" in l and pid in l)
            if app_errs:
                with open(os.path.join(out, "errors.log"), "a") as f:
                    f.write(f"--- cycle {cycle}\n" + "\n".join(app_errs) + "\n")
            s.sample(cycle, "warm" if cycle > 3 else "early", extra)
    except BaseException as e:
        failure = f"{type(e).__name__}: {e}"
        event("FAILED " + failure)
    finally:
        try:
            ph.call("cut_trace", {"trace": False})
        except Exception:
            pass
        try:
            new = {k: v for k, v in movies(ph).items() if k not in before}
            for k, v in new.items():
                ph.adb("shell", "content", "delete", "--uri", f"content://media/external/video/media/{k}", check=False)
            event(f"removed {len(new)} test exports: {sorted(new.values())}")
        except Exception as e:
            event(f"couldn't remove the test exports: {e}")
        if user:
            try:
                ph.callj("projects", {"op": "open", "id": user})
                time.sleep(2)
                if info.get("user_playhead") is not None:
                    ph.call("set_playhead", {"seconds": info["user_playhead"]})
                event(f"user's project {user} reopened at {info.get('user_playhead')}")
            except Exception as e:
                event(f"couldn't reopen the user's project: {e}")
    sys.exit(1 if failure else 0)


if __name__ == "__main__":
    main()
