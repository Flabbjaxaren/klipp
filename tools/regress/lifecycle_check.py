#!/usr/bin/env -S uv run --no-project --python 3.13 --with numpy==2.3.5 python
"""
The export against the editor screen's life cycle, on the phone, in the test package and the endurance test project
(its sequence "X2 lifecycle export": 36 clips of 7 s with film – 252 s, long enough that every action below lands in
the ENCODING, not after a quick export has already finished).

Lifecycle cases (each: export started, encoding seen under way, then the action):
  home     HOME
  back     BACK
  lock     the screen locked (KEYCODE_SLEEP; woken and unlocked by a swipe after the export)
  swipe    the test app swiped away in Recents (its card found by its label "Klipp test", nothing else is touched)
  remove   its task removed with `am stack remove` (what a swipe does, without the launcher)

For each: whether the activity, the process and the foreground service live on; whether the export goes on (progress
keeps rising, the same job, no restart); what is saved (exactly one new file, its length and frame count the
sequence's, decoded to the end by ffmpeg without errors); and what the app says when it is opened again.

Failure cases (the test build's test points, build 159 on):
  cancel      the user's Cancel during encoding: no file, "cancelled"
  cancelcopy  Cancel while the copy into Movies is held halfway: no file (published or pending), "cancelled"
  timeout     Android's time limit (onTimeout called by the test point) during encoding: the job stops, no file
  deny        the foreground start refused (test point): no job, an error with Try again, nothing left
  double      Export tapped twice: one job, one file

After-checks (the short sequence "X1 long export"), each after an export that was saved, cancelled (Cancel during
encoding) or failed (the test point's encoder failure at 5 %): no encoder held by the process and no more codecs than
before it, no notification, no export mark, background work free again (and resumed in the trail), a file only for the
saved one, and the preview playing again (4 s, cut trace).

Only files whose MediaStore ids are new in the run are removed. Every tap first checks the test app is on top.

Usage: lifecycle_check.py --serial S --port P [--only home,back,...] [--out DIR]
"""
import argparse, json, os, re, subprocess, sys, time

sys.argv, _argv = sys.argv[:1], sys.argv[1:]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regress as r  # noqa: E402
import endurance as e  # noqa: E402
import export_check as x  # noqa: E402
import copy_check as cc  # noqa: E402
sys.argv += _argv

LONG = "X2 lifecycle export"
N_CLIPS, CLIP_S = 36, 7.0
SECONDS = N_CLIPS * CLIP_S
FRAMES = int(round(SECONDS * r.FPS))
LABEL = "Klipp test"


def lifecycle_sequence(ph):
    rows = {v["name"]: v["item"] for v in ph.exec("project.items")["rows"]}
    if LONG in rows:
        ph.exec("sequence.open", {"item": rows[LONG]})
        return
    ph.exec("file.newSequence", {"name": LONG, "width": 1920, "height": 1080, "fps": r.FPS})
    film = {"nodes": [{"id": 1, "label": "Film", "film": {}}]}
    t = 0.0
    for i in range(N_CLIPS):
        f = e.FILES[i % len(e.FILES)]
        cid = r.place(ph, rows[f], "V1", t, 0.5, CLIP_S)
        ph.callj("grade", {"op": "set", "clip": cid, "grade": film})
        t += CLIP_S


def status(ph):
    """The export as the app reports it: the job's status (export_status, build 159 on) or, before that, the overlay's
    percentage and the foreground service."""
    try:
        return ph.callj("export_status")
    except Exception:
        pct = None
        if x.top(ph) == ph.pkg:
            m = re.search(r'"(\d+) %"', ph.call("ui_nodes", {"filter": "%"}))
            pct = int(m.group(1)) if m else None
        return {"legacy": True, "percent": pct, "service": e.notified(ph)}


def activity_alive(ph):
    """The test app's task (and so its activity) is there. (Not a text search of dumpsys activities: some phones keep old
    activity records in histories there, e.g. the vivo's rotation history.)"""
    return re.search(r"taskId=\d+: " + re.escape(ph.pkg) + "/", ph.adb("shell", "am", "stack", "list", check=False)) is not None


def pid(ph):
    return ph.adb("shell", "pidof", ph.pkg, check=False).strip()


def job_of(s):
    j = (s or {}).get("job") or {}
    return j.get("id"), j.get("phase"), j.get("percent")


def wait_encoding(ph, at_least=4, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        s = status(ph)
        p = s.get("percent") if s.get("legacy") else job_of(s)[2]
        if p is not None and p >= at_least:
            return s
        time.sleep(0.4)
    raise RuntimeError(f"the encoding wasn't seen under way ({status(ph)})")


def check_file(ph, out, mid, name):
    local = os.path.join(out, f"saved_{mid}.mp4")
    ph.adb("pull", f"{cc.DIR}/{name}", local)
    probe = subprocess.run(["ffprobe", "-v", "error", "-count_packets", "-select_streams", "v:0", "-show_entries",
                            "format=duration:stream=nb_read_packets", "-of", "json", local], capture_output=True, text=True)
    dec = subprocess.run(["ffmpeg", "-v", "error", "-i", local, "-f", "null", "-"], capture_output=True, text=True)
    info = json.loads(probe.stdout or "{}")
    dur = float(info.get("format", {}).get("duration", 0))
    frames = int((info.get("streams") or [{}])[0].get("nb_read_packets", 0))
    os.remove(local)
    return {"duration": round(dur, 3), "frames": frames, "decode_errors": dec.stderr.strip()[:200],
            "ok": abs(dur - SECONDS) < 0.2 and frames == FRAMES and not dec.stderr.strip()}


NO_POWER_KEYS = False   # --no-power-keys: never a wake/sleep key (a phone that must not get them)


def unlock(ph):
    if NO_POWER_KEYS:
        return
    ph.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1)
    if "isKeyguardShowing=true" in ph.adb("shell", "dumpsys", "window", check=False):
        ph.adb("shell", "input", "swipe", "540", "2000", "540", "600", "250")
        time.sleep(1.5)


def swipe_away(ph):
    """Recents, the test app's card swiped up – only where no other card lies (the user's real Klipp sits beside it).
    Returns what was swiped, or raises having touched nothing. Afterwards every other task must still be there."""
    tasks_before = re.findall(r"taskId=\d+: (\S+)/", ph.adb("shell", "am", "stack", "list", check=False))
    ph.adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH")
    time.sleep(1.8)
    ph.adb("shell", "uiautomator", "dump", "/sdcard/u.xml", check=False)
    xml = ph.adb("shell", "cat", "/sdcard/u.xml", check=False)
    cards = [(m.group(1), tuple(int(v) for v in m.groups()[1:]))
             for m in re.finditer(r'content-desc="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]
    mine = [b for label, b in cards if label == LABEL]
    if len(mine) != 1:
        ph.adb("shell", "input", "keyevent", "HOME")
        raise RuntimeError(f"Recents: {len(mine)} cards '{LABEL}' – not swiping")
    x1, y1, x2, y2 = mine[0]
    others = [b for label, b in cards if label != LABEL and b[3] - b[1] > 800 and b[2] - b[0] < 1000]   # other app cards (tall, not the screen)
    lo, hi = x1, x2
    for ox1, _, ox2, _ in others:
        if ox1 <= lo < ox2:
            lo = ox2
        if ox1 < hi <= ox2:
            hi = ox1
    if hi - lo < 80:
        ph.adb("shell", "input", "keyevent", "HOME")
        raise RuntimeError(f"Recents: no free strip of the test card ({mine[0]}, others {others}) – not swiping")
    cx, cy = (lo + hi) // 2, (y1 + y2) // 2
    ph.adb("shell", "input", "swipe", str(cx), str(cy), str(cx), "60", "250")
    time.sleep(1.5)
    ph.adb("shell", "input", "keyevent", "HOME")
    time.sleep(1)
    tasks_after = re.findall(r"taskId=\d+: (\S+)/", ph.adb("shell", "am", "stack", "list", check=False))
    lost = [t for t in tasks_before if t != ph.pkg and t not in tasks_after]
    if lost:
        print("!!! A TASK OTHER THAN THE TEST APP'S IS GONE:", lost, flush=True)
    return {"card": mine[0], "swiped_at": (cx, cy), "test_task_gone": ph.pkg not in tasks_after, "other_tasks_lost": lost}


def codecs(ph):
    """The codecs the app's process holds (its block in media.resource_manager)."""
    p = pid(ph).split(" ")[0]
    rm = ph.adb("shell", "dumpsys", "media.resource_manager", check=False)
    block = re.search(rf"^\s*Pid: {p}\n(.*?)(?=^\s*Pid: |\Z)", rm, re.M | re.S) if p else None
    return re.findall(r"Name: (\S+)", block.group(1)) if block else []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--pkg", default="se.gendut.klipp.designtest")
    ap.add_argument("--project", default="Klipp endurance 156")
    ap.add_argument("--only", default="home,back,lock,swipe")
    ap.add_argument("--out", default="")
    ap.add_argument("--no-power-keys", action="store_true", help="never send wake/sleep keys (the lock case is refused)")
    args = ap.parse_args()
    global NO_POWER_KEYS
    NO_POWER_KEYS = args.no_power_keys
    if NO_POWER_KEYS and "lock" in args.only.split(","):
        sys.exit("the lock case needs a sleep key – not with --no-power-keys")
    ph = r.Phone(args.serial, args.port, args.pkg)
    out = args.out or os.path.join(r.HERE, "out", time.strftime("%Y%m%d-%H%M%S") + "-lifecycle")
    os.makedirs(out, exist_ok=True)
    x.bring(ph)
    build = ph.callj("app_state").get("version")
    if not build or "designtest" not in build:
        sys.exit(f"not the test build: {build}")
    print("build", build, flush=True)
    projects = ph.callj("projects", {"op": "list"})
    user = next((p["id"] for p in projects if p["current"]), None)
    if any(p["current"] and p["name"] == args.project for p in projects):
        sys.exit("the test project is open – open the user's first")
    playhead = ph.callj("app_state").get("playhead")
    reg = next((p["id"] for p in projects if p["name"] == args.project), None)
    if not reg:
        sys.exit(f"no project {args.project} (run endurance.py first)")
    movies0 = e.movies(ph)
    files0 = cc.files(ph)
    results = []

    def result(name, ok, detail):
        results.append({"case": name, "ok": ok, "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'} {name}: {json.dumps(detail, ensure_ascii=False)}", flush=True)

    def prepare():
        x.bring(ph)
        if not any(p["current"] and p["id"] == reg for p in ph.callj("projects", {"op": "list"})):
            ph.callj("projects", {"op": "open", "id": reg})
            time.sleep(3)
        lifecycle_sequence(ph)
        time.sleep(2)

    def finished(before, timeout=600):
        """Until the export is over: the foreground service gone and the job (if reported) not running."""
        t0 = time.time()
        while time.time() - t0 < timeout:
            s = status(ph)
            running = e.notified(ph) or (not s.get("legacy") and job_of(s)[1] in ("starting", "running"))
            if not running:
                return round(time.time() - t0, 1), s
            time.sleep(1.5)
        raise RuntimeError("the export didn't end")

    def said():
        x.bring(ph)
        time.sleep(2.5)
        n = cc.notice(ph)
        if n:
            x.tap_tag(ph, n["tag"] + "Ok")
            time.sleep(1)
        return n

    def new_files(before_m):
        return {k: v for k, v in e.movies(ph).items() if k not in before_m}

    def lifecycle(case, action):
        prepare()
        before_m, before_f = e.movies(ph), cc.files(ph)
        p0 = pid(ph)
        x.start_export(ph)
        s1 = wait_encoding(ph)
        detail = {"before": s1}
        detail["action"] = action()
        time.sleep(4)
        detail["activity_alive"] = activity_alive(ph)
        detail["same_process"] = pid(ph) == p0 and bool(p0)
        detail["service"] = e.notified(ph)
        s2 = status(ph)
        detail["after_4s"] = s2
        if s1.get("legacy"):
            detail["continues"] = bool(detail["service"])
        else:
            i1, _, q1 = job_of(s1)
            i2, ph2, q2 = job_of(s2)
            detail["continues"] = i1 == i2 and ph2 == "running" and q2 is not None and q2 > q1
        if case == "reopen":
            # a new screen while the job runs: it must show the same job, under way, with its Cancel
            x.bring(ph)
            time.sleep(2)
            s4 = status(ph)
            detail["reopened"] = {"overlay": e.overlay(ph), "cancel_button": ph.node_bounds("exportCancel") is not None,
                                  "job": job_of(s4), "same_job": job_of(s4)[0] == job_of(s1)[0] and job_of(s4)[1] == "running"}
        took, s3 = finished(before_m)
        detail["ended_after_s"] = took
        detail["end_status"] = s3
        if case == "lock":
            unlock(ph)
        detail["service_after"] = e.notified(ph)
        new = new_files(before_m)
        detail["new_files"] = new
        pend = [k for k in cc.files(ph) if k not in before_f and k.startswith(".pending")]
        detail["pending_left"] = pend
        detail["files"] = [check_file(ph, out, k, v) for k, v in new.items()]
        detail["notice"] = said()
        detail["same_process_at_end"] = pid(ph) == p0
        last = (s3.get("last") or {}) if not s3.get("legacy") else {}
        detail["one_job"] = (not last) or last.get("id") == job_of(s1)[0]
        said_ok = (detail["notice"] or {}).get("tag") == "exportSavedLate" or (case == "reopen" and last.get("phase") == "saved")
        ok = detail["one_job"] and said_ok and (case != "reopen" or detail["reopened"]["same_job"] and detail["reopened"]["overlay"]) and (detail["continues"] and len(new) == 1 and all(f["ok"] for f in detail["files"]) and not pend
              and not detail["service_after"] and detail["same_process"])
        result(f"{case}: export continues", ok, detail)

    def home():
        ph.adb("shell", "input", "keyevent", "HOME")
        return "HOME"

    def back():
        ph.adb("shell", "input", "keyevent", "BACK")
        time.sleep(1)
        return f"BACK (top after: {x.top(ph)})"

    def lock():
        ph.adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(1)
        return "KEYCODE_SLEEP, keyguard " + ("on" if "isKeyguardShowing=true" in ph.adb("shell", "dumpsys", "window", check=False) else "off")

    def swipe():
        return swipe_away(ph)

    def remove():
        t = cc.task_of(ph)
        ph.adb("shell", "am", "stack", "remove", t)
        return f"am stack remove {t}"

    actions = {"home": home, "back": back, "lock": lock, "swipe": swipe, "remove": remove, "reopen": remove}

    def test_point(op, **kw):
        return ph.call("export_latch", {"op": op, **kw}).strip()

    try:
        for case in args.only.split(","):
            try:
                if case in actions:
                    lifecycle(case, actions[case])
                elif case in ("after_saved", "after_cancelled", "after_failed"):
                    # after an export that was saved, cancelled or failed: no encoder held, no notification, no mark,
                    # background work free again, the preview plays
                    x.bring(ph)
                    x.long_sequence(ph)   # (X1: short)
                    time.sleep(4)
                    base = codecs(ph)
                    before_m = e.movies(ph)
                    if case == "after_failed":
                        test_point("fail_encode")
                    x.start_export(ph)
                    if case == "after_cancelled":
                        wait_encoding(ph, 3)
                        x.tap_tag(ph, "exportCancel")
                    took, s2 = finished(before_m, 300)
                    time.sleep(4)
                    st = status(ph)
                    held = codecs(ph)
                    new = new_files(before_m)
                    trail = ph.call("logs")[-3000:]
                    pl = e.play(ph, 2.0, 4)
                    want = {"after_saved": "saved", "after_cancelled": "cancelled", "after_failed": "failed"}[case]
                    d = {"end": (st.get("last") or {}), "codecs_before": base, "codecs_after": held, "encoder_held": [n for n in held if "encoder" in n],
                         "notification": e.notified(ph), "marked": st.get("marked"), "background_held": st.get("background_held"),
                         "background_resumed_in_trail": "background work resumed" in trail, "new_files": new, "preview_play": pl}
                    ok = ((st.get("last") or {}).get("phase") == want and not d["encoder_held"] and len(held) <= len(base) and not d["notification"]
                          and d["marked"] is None and d["background_held"] is False and d["background_resumed_in_trail"]
                          and (len(new) == 1) == (want == "saved") and pl.get("slots", 0) > 50)
                    result(f"{case}: resources and state after the export", ok, d)
                elif case == "cancel":
                    prepare()
                    before_m = e.movies(ph)
                    x.start_export(ph)
                    s1 = wait_encoding(ph)
                    x.tap_tag(ph, "exportCancel")
                    took, s2 = finished(before_m, 60)
                    new = new_files(before_m)
                    toast_free = cc.notice(ph) is None
                    d = {"before": s1, "ended_after_s": took, "end": s2, "new_files": new, "no_dialog": toast_free,
                         "service_after": e.notified(ph), "background_resumed": "background work resumed" in ph.call("logs")[-4000:]}
                    ok = not new and job_of(s2)[1] in ("cancelled", None) and not d["service_after"] and (s2.get("last") or {}).get("phase") == "cancelled"
                    result("cancel during encoding", ok, d)
                elif case == "cancelcopy":
                    prepare()
                    before_m, before_f = e.movies(ph), cc.files(ph)
                    test_point("arm", seconds=120)
                    x.start_export(ph)
                    held = cc.wait_held(ph)
                    in_copy = {k: v for k, v in cc.files(ph).items() if k not in before_f}
                    x.tap_tag(ph, "exportCancel")
                    took, s2 = finished(before_m, 60)
                    new = new_files(before_m)
                    left = {k: v for k, v in cc.files(ph).items() if k not in before_f}
                    d = {"latch": held, "in_movies_while_held": in_copy, "latch_after": test_point("status"), "end": s2,
                         "new_files": new, "on_disk_after": left, "service_after": e.notified(ph)}
                    ok = bool(in_copy) and not new and not left and (s2.get("last") or {}).get("phase") == "cancelled" and not d["service_after"]
                    result("cancel during the copy", ok, d)
                elif case == "timeout":
                    prepare()
                    before_m = e.movies(ph)
                    x.start_export(ph)
                    s1 = wait_encoding(ph)
                    t = test_point("timeout")
                    took, s2 = finished(before_m, 60)
                    new = new_files(before_m)
                    n = said()
                    d = {"before": s1, "test_point": t, "ended_after_s": took, "end": s2, "new_files": new,
                         "service_after": e.notified(ph), "notice": n}
                    last = s2.get("last") or {}
                    ok = not new and last.get("phase") == "cancelled" and last.get("reason") == "timeout" and not d["service_after"]
                    result("Android's time limit during encoding", ok, d)
                elif case == "deny":
                    prepare()
                    before_m, before_f = e.movies(ph), cc.files(ph)
                    test_point("deny_start")
                    x.tap_tag(ph, "export")
                    for _ in range(20):
                        time.sleep(0.5)
                        if ph.node_bounds("exp:go"):
                            break
                    x.tap_tag(ph, "exp:go")
                    time.sleep(2.5)
                    dialog = ph.node_bounds("exportDenied") is not None
                    s = status(ph)
                    d = {"dialog": dialog, "retry": ph.node_bounds("exportDeniedRetry") is not None, "status": s,
                         "service": e.notified(ph), "new_files": new_files(before_m),
                         "on_disk": {k: v for k, v in cc.files(ph).items() if k not in before_f}}
                    # Try again: now allowed – it must run and save one whole file
                    if d["retry"]:
                        x.tap_tag(ph, "exportDeniedRetry")
                        time.sleep(2)
                        d["retry_running"] = job_of(status(ph))[1] == "running"
                        took, s2 = finished(before_m)
                        new = new_files(before_m)
                        d["retry_files"] = [check_file(ph, out, k, v) for k, v in new.items()]
                    ok = (dialog and d["retry"] and not d["service"] and not d["new_files"] and not d["on_disk"]
                          and d.get("retry_running") and len(d.get("retry_files", [])) == 1 and d["retry_files"][0]["ok"])
                    result("foreground start refused", ok, d)
                elif case == "double":
                    prepare()
                    before_m = e.movies(ph)
                    x.tap_tag(ph, "export")
                    for _ in range(20):
                        time.sleep(0.5)
                        if ph.node_bounds("exp:go"):
                            break
                    b = ph.node_bounds("exp:go")
                    cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
                    ph.call("tap", {"x": cx, "y": cy})
                    ph.call("tap", {"x": cx, "y": cy})
                    took, s2 = finished(before_m)
                    new = new_files(before_m)
                    log = ph.call("logs")
                    d = {"new_files": new, "refused_second": "not started – one is running" in log[-6000:], "end": s2,
                         "files": [check_file(ph, out, k, v) for k, v in new.items()]}
                    ok = len(new) == 1 and d["files"][0]["ok"]
                    result("export tapped twice", ok, d)
                else:
                    raise RuntimeError(f"unknown case {case}")
            except Exception as ex:
                result(case, False, f"{type(ex).__name__}: {ex}")
                try:
                    unlock(ph)
                    finished({}, 300)
                except Exception:
                    pass
    except BaseException as ex:
        result("runner", False, f"{type(ex).__name__}: {ex}")
    finally:
        try:
            unlock(ph)
            x.bring(ph)
        except Exception as ex:
            print("couldn't bring the test app forward:", ex)
        try:
            ph.call("export_latch", {"op": "release"})
        except Exception:
            pass
        new = {k: v for k, v in e.movies(ph).items() if k not in movies0}
        for k in new:
            ph.adb("shell", "content", "delete", "--uri", f"content://media/external/video/media/{k}", check=False)
        print(f"removed {len(new)} test exports (ids registered as new in this run): {sorted(new.values())}")
        left = {k: v for k, v in cc.files(ph).items() if k not in files0}
        if left:
            print("STILL IN Movies/Klipp:", left)
        if user:
            try:
                ph.callj("projects", {"op": "open", "id": user})
                time.sleep(2)
                if playhead is not None:
                    ph.call("set_playhead", {"seconds": playhead})
                print(f"user's project {user} reopened at {playhead}")
            except Exception as ex:
                print("couldn't reopen the user's project:", ex)
        json.dump(results, open(os.path.join(out, "lifecycle_check.json"), "w"), indent=1, ensure_ascii=False)
    sys.exit(0 if results and all(v["ok"] for v in results) else 1)


if __name__ == "__main__":
    main()
