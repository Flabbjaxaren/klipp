#!/usr/bin/env -S uv run --no-project --python 3.13 --with numpy==2.3.5 python
"""
The export's failure paths on the phone, in the test package and a test project (the endurance one, already built):

  background  Export, then HOME at once: the export must finish in the background – a new file in Movies/Klipp, the
              notification there while it runs and gone after.
  back        Export, then BACK: the app must go to the background (not be closed) and the export finish the same way.
  kill        Export, then the process killed (am force-stop) a few seconds in: no file in Movies/Klipp, no notification
              left, no export_*.mp4 left in the cache, and at the next start the "Export interrupted" notice.

Every tap is made only with the test app on top (force-stop can bring another app forward). Test exports are removed
at the end; the user's project is reopened.

Usage: export_check.py --serial S --port P [--project NAME] [--out DIR]
"""
import argparse, json, os, re, sys, time

sys.argv, _argv = sys.argv[:1], sys.argv[1:]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regress as r  # noqa: E402
import endurance as e  # noqa: E402
sys.argv += _argv


def top(ph):
    a = ph.adb("shell", "dumpsys", "activity", "activities", check=False)
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ ([^/\s]+)/", a)
    return m.group(1) if m else None


def bring(ph):
    """The test app to the front (and its bridge back)."""
    if top(ph) != ph.pkg:
        ph.adb("shell", "am", "start", "-n", f"{ph.pkg}/se.gendut.klipp.MainActivity")
    for _ in range(30):
        time.sleep(1)
        if top(ph) == ph.pkg:
            try:
                ph.forward()
                ph.callj("app_state")
                return
            except Exception:
                pass
    raise RuntimeError(f"the test app didn't come to the front (top: {top(ph)})")


NOTICES = ("exportSavedLate", "exportInterrupted", "exportFailedLate")


def take_notice(ph):
    """The editor's dialog about an export that ended unseen (since build 159 an export ended in the background says
    so there): its tag, closed with its OK – or None."""
    for tag in NOTICES:
        if ph.node_bounds(tag):
            tap_tag(ph, tag + "Ok")
            time.sleep(1)
            return tag
    return None


def tap_tag(ph, tag):
    if top(ph) != ph.pkg:
        raise RuntimeError(f"not tapping {tag}: {top(ph)} is on top")
    b = ph.node_bounds(tag)
    if not b:
        raise RuntimeError(f"{tag} not found")
    ph.call("tap", {"x": (b[0] + b[2]) / 2, "y": (b[1] + b[3]) / 2})


def start_export(ph):
    tap_tag(ph, "export")
    for _ in range(20):
        time.sleep(0.5)
        if ph.node_bounds("exp:go"):
            break
    else:
        raise RuntimeError("the export dialog didn't open")
    tap_tag(ph, "exp:go")
    for _ in range(20):
        time.sleep(0.5)
        if e.overlay(ph) or e.notified(ph):
            return
    raise RuntimeError("the export didn't start")


def cache_exports(ph):
    try:
        out = ph.adb("shell", "run-as", ph.pkg, "ls", "cache")
    except RuntimeError:
        return None   # (a release build: the cache can't be listed – the next start's cleanup is unit-tested)
    return [l for l in out.split() if l.startswith("export_")]


def wait_done(ph, before, timeout=600):
    """Until a new file is in Movies/Klipp and the notification is gone (the app may be in the background)."""
    t0 = time.time()
    seen_note = False
    while time.time() - t0 < timeout:
        time.sleep(1.5)
        on = e.notified(ph)
        seen_note |= on
        new = [v for k, v in e.movies(ph).items() if k not in before]
        if new and not on:
            return {"seconds": round(time.time() - t0, 1), "saved": new, "notification_seen": seen_note}
    raise RuntimeError("the export didn't finish in the background")


LONG = "X1 long export"


def long_sequence(ph):
    """A sequence long enough for an export to be caught while it runs: eight clips of 7 s, film on all."""
    rows = {x["name"]: x["item"] for x in ph.exec("project.items")["rows"]}
    if LONG in rows:
        ph.exec("sequence.open", {"item": rows[LONG]})
        return
    ph.exec("file.newSequence", {"name": LONG, "width": 1920, "height": 1080, "fps": r.FPS})
    film = {"nodes": [{"id": 1, "label": "Film", "film": {}}]}
    t = 0.0
    for i in range(8):
        f = e.FILES[i % len(e.FILES)]
        cid = r.place(ph, rows[f], "V1", t, 0.5, 7.0)
        ph.callj("grade", {"op": "set", "clip": cid, "grade": film})
        t += 7.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--pkg", default="se.gendut.klipp.designtest")
    ap.add_argument("--project", default="Klipp endurance 156")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    ph = r.Phone(args.serial, args.port, args.pkg)
    out = args.out or os.path.join(r.HERE, "out", time.strftime("%Y%m%d-%H%M%S") + "-export")
    os.makedirs(out, exist_ok=True)
    bring(ph)
    left = take_notice(ph)
    if left:
        print("closed a notice left from before:", left)
    projects = ph.callj("projects", {"op": "list"})
    user = next((p["id"] for p in projects if p["current"]), None)
    if any(p["current"] and p["name"] == args.project for p in projects):
        sys.exit("the test project is open – open the user's first")
    playhead = ph.callj("app_state").get("playhead")
    reg = next((p["id"] for p in projects if p["name"] == args.project), None)
    if not reg:
        sys.exit(f"no project {args.project} (run endurance.py first)")
    movies0 = e.movies(ph)
    results = []

    def result(name, ok, detail):
        results.append({"case": name, "ok": ok, "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'} {name}: {json.dumps(detail)}", flush=True)

    try:
        ph.callj("projects", {"op": "open", "id": reg})
        time.sleep(3)
        long_sequence(ph)
        time.sleep(3)

        # 1. in the background (HOME)
        before = e.movies(ph)
        start_export(ph)
        ph.adb("shell", "input", "keyevent", "HOME")
        d = wait_done(ph, before)
        bring(ph)
        time.sleep(2)
        d["said"] = take_notice(ph)
        result("background (HOME)", bool(d["saved"]) and d["notification_seen"] and not e.notified(ph) and d["said"] in (None, "exportSavedLate"), d)

        # 2. BACK while it runs: to the background, not closed
        before = e.movies(ph)
        pid = ph.adb("shell", "pidof", ph.pkg).strip()
        start_export(ph)
        ph.adb("shell", "input", "keyevent", "BACK")
        time.sleep(1.5)
        after_back = top(ph)
        d = wait_done(ph, before)
        same = ph.adb("shell", "pidof", ph.pkg).strip() == pid
        acts = ph.adb("shell", "dumpsys", "activity", "activities", check=False)
        alive = f"{ph.pkg}/se.gendut.klipp.MainActivity" in acts
        d.update({"top_after_back": after_back, "same_process": same, "activity_kept": alive})
        bring(ph)
        time.sleep(2)
        d["said"] = take_notice(ph)
        result("back while exporting", bool(d["saved"]) and same and alive and not e.notified(ph) and d["said"] in (None, "exportSavedLate"), d)

        # 3. the process killed in the middle
        before = e.movies(ph)
        start_export(ph)
        time.sleep(4.0)
        running = e.overlay(ph) and e.notified(ph)
        ph.adb("shell", "am", "force-stop", ph.pkg)
        time.sleep(2)
        top_after_kill = top(ph)
        left_note = e.notified(ph)
        new = [v for k, v in e.movies(ph).items() if k not in before]
        # the next start: the notice, and the cleanup
        bring(ph)
        time.sleep(2)
        notice = ph.node_bounds("exportInterrupted") is not None
        cache = cache_exports(ph)
        d = {"was_running": running, "saved": new, "notification_left": left_note, "notice_shown": notice,
             "cache_exports": cache, "top_after_kill": top_after_kill}
        # (cache None: not listable on a release-signed build – not counted as checked)
        result("process killed while exporting", running and not new and not left_note and notice and not cache, d)
        if notice:
            tap_tag(ph, "exportInterruptedOk")
            time.sleep(1)
    except BaseException as ex:
        result("runner", False, f"{type(ex).__name__}: {ex}")
    finally:
        try:
            bring(ph)
        except Exception as ex:
            print("couldn't bring the test app forward:", ex)
        new = {k: v for k, v in e.movies(ph).items() if k not in movies0}
        for k in new:
            ph.adb("shell", "content", "delete", "--uri", f"content://media/external/video/media/{k}", check=False)
        print(f"removed {len(new)} test exports: {sorted(new.values())}")
        if user:
            try:
                ph.callj("projects", {"op": "open", "id": user})
                time.sleep(2)
                if playhead is not None:
                    ph.call("set_playhead", {"seconds": playhead})
                print(f"user's project {user} reopened at {playhead}")
            except Exception as ex:
                print("couldn't reopen the user's project:", ex)
        json.dump(results, open(os.path.join(out, "export_check.json"), "w"), indent=1)
    sys.exit(0 if results and all(x["ok"] for x in results) else 1)


if __name__ == "__main__":
    main()
