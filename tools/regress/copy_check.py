#!/opt/homebrew/bin/python3
"""
The export stopped exactly in its copy into Movies (the test build's latch holds the copy halfway), on the phone, in the
test package and the endurance test project (its sequence "X1 long export"):

  killed   the process killed (am force-stop) in the copy: at the next start no half file may be left in Movies/Klipp
           (neither published nor pending), no notification, and the notice must say that no file was saved.
  closed   the app's screen closed (its task removed, as when it is swiped away in Recents) in the copy, the process
           living on (the foreground service): what is in Movies/Klipp afterwards and what the app says when it is
           opened again must agree – a file saved is never reported as "no file was saved", and the other way round.

Every tap is made only with the test app on top. Test exports are removed at the end; the user's project is reopened.

Usage: copy_check.py --serial S --port P [--project NAME] [--out DIR]
"""
import argparse, json, os, re, subprocess, sys, time

sys.argv, _argv = sys.argv[:1], sys.argv[1:]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regress as r  # noqa: E402
import endurance as e  # noqa: E402
import export_check as x  # noqa: E402
sys.argv += _argv

DIR = "/sdcard/Movies/Klipp"


def files(ph):
    """Movies/Klipp on disk, pending entries included (".pending-<expiry>-name"): name → size."""
    out = ph.adb("shell", "ls", "-la", DIR + "/", check=False)
    got = {}
    for line in out.splitlines():
        m = re.match(r"\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+(.+)$", line.strip())
        if m and m.group(2) not in (".", ".."):
            got[m.group(2)] = int(m.group(1))
    return got


def latch(ph, op, **kw):
    return ph.call("export_latch", {"op": op, **kw}).strip()


def wait_held(ph, timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        s = latch(ph, "status")
        if s.startswith("held"):
            return s
        time.sleep(0.5)
    raise RuntimeError("the copy was never held (latch: %s)" % latch(ph, "status"))


def notice(ph):
    """The dialog shown about the last export at the editor (its tag and text), or None."""
    for tag in ("exportInterrupted", "exportSavedLate"):
        if ph.node_bounds(tag):
            nodes = ph.call("ui_nodes", {})
            texts = [t for t in re.findall(r'"([^"\n]{12,})"', nodes) if "xport" in t or "fil" in t.lower()]
            return {"tag": tag, "text": texts[:3]}
    return None


def task_of(ph):
    out = ph.adb("shell", "am", "stack", "list", check=False)
    for line in out.splitlines():
        m = re.search(r"taskId=(\d+): " + re.escape(ph.pkg) + "/", line)
        if m:
            return m.group(1)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--pkg", default="se.gendut.klipp.designtest")
    ap.add_argument("--project", default="Klipp endurance 156")
    ap.add_argument("--only", default="killed,published,closed")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    ph = r.Phone(args.serial, args.port, args.pkg)
    out = args.out or os.path.join(r.HERE, "out", time.strftime("%Y%m%d-%H%M%S") + "-copy")
    os.makedirs(out, exist_ok=True)
    x.bring(ph)
    print("build", ph.callj("app_state").get("version"), flush=True)
    projects = ph.callj("projects", {"op": "list"})
    user = next((p["id"] for p in projects if p["current"]), None)
    if any(p["current"] and p["name"] == args.project for p in projects):
        sys.exit("the test project is open – open the user's first")
    playhead = ph.callj("app_state").get("playhead")
    reg = next((p["id"] for p in projects if p["name"] == args.project), None)
    if not reg:
        sys.exit(f"no project {args.project} (run endurance.py first)")
    movies0, files0 = e.movies(ph), files(ph)
    results = []

    def result(name, ok, detail):
        results.append({"case": name, "ok": ok, "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'} {name}: {json.dumps(detail, ensure_ascii=False)}", flush=True)

    def prepare():
        ph.callj("projects", {"op": "open", "id": reg})
        time.sleep(3)
        x.long_sequence(ph)
        time.sleep(3)

    try:
        prepare()
        if "killed" in args.only:
            before_m, before_f = e.movies(ph), files(ph)
            latch(ph, "arm", seconds=120)
            x.start_export(ph)
            held = wait_held(ph)
            in_copy = {k: v for k, v in files(ph).items() if k not in before_f}
            ph.adb("shell", "am", "force-stop", ph.pkg)
            time.sleep(2)
            left_note = e.notified(ph)
            x.bring(ph)
            time.sleep(3)
            said = notice(ph)
            time.sleep(2)   # (the start's cleanup)
            after_f = {k: v for k, v in files(ph).items() if k not in before_f}
            after_m = [v for k, v in e.movies(ph).items() if k not in before_m]
            saved = bool(after_m) or any(not k.startswith(".pending") for k in after_f)
            d = {"latch": held, "in_movies_while_held": in_copy, "notification_left": left_note,
                 "after_restart_on_disk": after_f, "after_restart_published": after_m, "notice": said}
            ok = (not left_note and not after_f and not after_m and said is not None and said["tag"] == "exportInterrupted"
                  and bool(in_copy))
            result("process killed in the copy", ok, d)
            if said:
                x.tap_tag(ph, "exportInterruptedOk" if said["tag"] == "exportInterrupted" else "exportSavedLateOk")
                time.sleep(1)
            prepare()

        if "published" in args.only:
            # killed right after the file was published, before the export noted it: the next start must find the
            # file whole in Movies by its recorded URI, keep it and say it was saved
            before_m, before_f = e.movies(ph), files(ph)
            latch(ph, "arm", seconds=120, at="published")
            x.start_export(ph)
            t0 = time.time()
            while not latch(ph, "status").startswith("held published"):
                if time.time() - t0 > 600:
                    raise RuntimeError("never held after publishing")
                time.sleep(0.3)
            ph.adb("shell", "am", "force-stop", ph.pkg)
            time.sleep(2)
            left_note = e.notified(ph)
            x.bring(ph)
            time.sleep(3)
            said = notice(ph)
            time.sleep(2)
            after_m = {k: v for k, v in e.movies(ph).items() if k not in before_m}
            after_f = {k: v for k, v in files(ph).items() if k not in before_f}
            whole = None
            if len(after_m) == 1:
                local = os.path.join(out, "published_then_killed.mp4")
                ph.adb("pull", f"{DIR}/{list(after_m.values())[0]}", local)
                probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", local], capture_output=True, text=True)
                dec = subprocess.run(["ffmpeg", "-v", "error", "-i", local, "-f", "null", "-"], capture_output=True, text=True)
                dur = float(json.loads(probe.stdout or "{}").get("format", {}).get("duration", 0))
                whole = {"duration": round(dur, 3), "decode_errors": dec.stderr.strip()[:200], "ok": abs(dur - 56.0) < 0.2 and not dec.stderr.strip()}
                os.remove(local)
            d = {"notification_left": left_note, "published": list(after_m.values()), "on_disk": after_f, "file": whole, "notice": said}
            ok = (not left_note and len(after_m) == 1 and whole and whole["ok"] and not any(k.startswith(".pending") for k in after_f)
                  and said is not None and said["tag"] == "exportSavedLate")
            result("process killed right after publishing", ok, d)
            if said:
                x.tap_tag(ph, said["tag"] + "Ok")
                time.sleep(1)
            prepare()

        if "closed" in args.only:
            before_m, before_f = e.movies(ph), files(ph)
            latch(ph, "arm", seconds=15)
            x.start_export(ph)
            held = wait_held(ph)
            pid = ph.adb("shell", "pidof", ph.pkg).strip()
            task = task_of(ph)
            if not task:
                raise RuntimeError("the test app's task not found")
            ph.adb("shell", "am", "stack", "remove", task)
            time.sleep(1.5)
            acts = ph.adb("shell", "dumpsys", "activity", "activities", check=False)
            closed = f"{ph.pkg}/se.gendut.klipp.MainActivity" not in acts
            protected = e.notified(ph)   # the foreground service still holds the process while the copy finishes
            # the latch lets go by itself after its 15 s; then the copy ends (or not) with the screen gone
            t0 = time.time()
            while time.time() - t0 < 60 and latch(ph, "status").startswith("held"):
                time.sleep(0.5)
            state = latch(ph, "status")
            time.sleep(4)
            alive = ph.adb("shell", "pidof", ph.pkg).strip() == pid
            left_note = e.notified(ph)
            after_f = {k: v for k, v in files(ph).items() if k not in before_f}
            after_m = [v for k, v in e.movies(ph).items() if k not in before_m]
            saved = bool(after_m)
            pending = [k for k in after_f if k.startswith(".pending")]
            whole = None
            if after_m:   # the saved file must be whole: its length the sequence's (56 s), decodable to the end
                local = os.path.join(out, "saved_while_closed.mp4")
                ph.adb("pull", f"{DIR}/{after_m[0]}", local)
                probe = subprocess.run(["ffprobe", "-v", "error", "-count_packets", "-show_entries", "format=duration:stream=codec_type,nb_read_packets",
                                        "-of", "json", local], capture_output=True, text=True)
                dec = subprocess.run(["ffmpeg", "-v", "error", "-i", local, "-f", "null", "-"], capture_output=True, text=True)
                info = json.loads(probe.stdout or "{}")
                dur = float(info.get("format", {}).get("duration", 0))
                whole = {"duration": round(dur, 3), "packets": {st["codec_type"]: int(st["nb_read_packets"]) for st in info.get("streams", [])},
                         "decode_errors": dec.stderr.strip()[:200], "ok": abs(dur - 56.0) < 0.2 and not dec.stderr.strip()}
                os.remove(local)
            x.bring(ph)
            time.sleep(3)
            said = notice(ph)
            d = {"latch": held, "latch_after": state, "saved_file": whole, "screen_closed": closed, "held_in_foreground": protected, "same_process": alive, "notification_left": left_note,
                 "on_disk": after_f, "published": after_m, "notice": said}
            agrees = (said is not None and ((saved and whole and whole["ok"] and said["tag"] == "exportSavedLate") or (not saved and said["tag"] == "exportInterrupted")))
            result("screen closed in the copy", closed and protected and alive and not left_note and not pending and agrees, d)
            if said:
                x.tap_tag(ph, "exportInterruptedOk" if said["tag"] == "exportInterrupted" else "exportSavedLateOk")
                time.sleep(1)
    except BaseException as ex:
        result("runner", False, f"{type(ex).__name__}: {ex}")
    finally:
        try:
            latch(ph, "release")
        except Exception:
            pass
        try:
            x.bring(ph)
        except Exception as ex:
            print("couldn't bring the test app forward:", ex)
        new = {k: v for k, v in e.movies(ph).items() if k not in movies0}
        for k in new:
            ph.adb("shell", "content", "delete", "--uri", f"content://media/external/video/media/{k}", check=False)
        print(f"removed {len(new)} test exports: {sorted(new.values())}")
        left = {k: v for k, v in files(ph).items() if k not in files0}
        if left:
            print("STILL IN Movies/Klipp (not removed – pending entries are the app's own):", left)
        if user:
            try:
                ph.callj("projects", {"op": "open", "id": user})
                time.sleep(2)
                if playhead is not None:
                    ph.call("set_playhead", {"seconds": playhead})
                print(f"user's project {user} reopened at {playhead}")
            except Exception as ex:
                print("couldn't reopen the user's project:", ex)
        json.dump(results, open(os.path.join(out, "copy_check.json"), "w"), indent=1, ensure_ascii=False)
    sys.exit(0 if results and all(v["ok"] for v in results) else 1)


if __name__ == "__main__":
    main()
