#!/usr/bin/env python3
"""
Klipp's license inventory: what the release APK contains or downloads, with versions, origins, licenses and notices.

    python3 tools/licenses/inventory.py build  [--gradle-deps FILE]   # writes LICENSES/inventory.json + LICENSES/third-party/rust
    python3 tools/licenses/inventory.py check  [--gradle-deps FILE]   # CI: fails on a component or asset without a reviewed entry

Sources (one inventory for the repository and the app's license screen, so the two never drift apart):
  * Rust: `cargo metadata --filter-platform aarch64-linux-android` from rust/ – the crates linked into
    libklipp_bridge.so (normal dependencies) are told apart from build scripts and proc-macros (not distributed).
  * Maven: the app's releaseRuntimeClasspath (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`,
    saved to a file) against tools/licenses/maven-reviewed.json (licenses read from each POM, reviewed by hand).
  * Everything else (native code built from source here, ports, fonts, film tables, models, sound packs, run-time
    downloads): tools/licenses/manual.json, reviewed by hand. Its "files" globs must cover every file in
    app/src/main/assets, so a new asset without a license entry fails the check.

Status per entry: "verified" (license and notices checked against the actual files), "incomplete" (license known,
something missing – see notes) or "unclear" (rights not established – see docs/licensing-open-questions.md).
Never mark something verified to make the check pass.
"""
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "LICENSES")
TOOLS = os.path.dirname(os.path.abspath(__file__))
TARGET = "aarch64-linux-android"
LICENSE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE|UNLICENSE)([-._].*)?$", re.I)


def cargo():
    meta = json.loads(subprocess.check_output(
        ["cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", TARGET],
        cwd=os.path.join(ROOT, "rust")))
    pk = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = meta["resolve"]["root"]

    def proc_macro(pid):
        return any("proc-macro" in t["kind"] for t in pk[pid]["targets"])

    runtime, build, seen, stack = set(), set(), set(), [(root, True)]
    while stack:
        pid, rt = stack.pop()
        if (pid, rt) in seen:
            continue
        seen.add((pid, rt))
        if pid != root:
            (runtime if rt and not proc_macro(pid) else build).add(pid)
        for d in nodes[pid]["deps"]:
            kinds = [k["kind"] for k in d["dep_kinds"]]
            if None in kinds:
                stack.append((d["pkg"], rt and not proc_macro(d["pkg"])))
            if "build" in kinds:
                stack.append((d["pkg"], False))
    build -= runtime
    out = []
    for pid in sorted(runtime | build, key=lambda i: (pk[i]["name"], pk[i]["version"])):
        p = pk[pid]
        d = os.path.dirname(p["manifest_path"])
        src = p.get("source") or "path"
        files = sorted(f for f in os.listdir(d) if LICENSE_FILE.match(f) and os.path.isfile(os.path.join(d, f)))
        out.append({
            "id": f"rust:{p['name']}",
            "component": p["name"],
            "version": p["version"],
            "kind": "rust-crate",
            "origin": (f"https://crates.io/crates/{p['name']}/{p['version']}" if src.startswith("registry+")
                       else src.split("+", 1)[-1] if src != "path" else "rust/ (this repository)"),
            "repository": p.get("repository") or "",
            "license": p.get("license") or "",
            "features": nodes[pid]["features"],
            "included_in": "lib/arm64-v8a/libklipp_bridge.so" if pid in runtime else "not distributed (build script or proc-macro)",
            "distributed": pid in runtime,
            "_dir": d,
            "_license_files": files,
        })
    return out


def maven(deps_file):
    reviewed = json.load(open(os.path.join(TOOLS, "maven-reviewed.json")))
    coords = set()
    for line in open(deps_file, encoding="utf-8"):
        line = line.rstrip()
        if line.endswith("(c)") or "project" in line:  # version constraints are not dependencies
            continue
        m = re.search(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::([0-9][^ ]*?))?(?: -> ([0-9][^ ]*))?(?: \(\*\))?\s*$", line)
        if not m or not (m.group(3) or m.group(4)):
            continue
        g, a, v, v2 = m.groups()
        coords.add((f"{g}:{a}", v2 or v))
    out, missing = [], []
    for name, version in sorted(coords):
        r = reviewed.get(name)
        if not r or r["version"] != version:
            missing.append(f"{name}:{version}")
            continue
        out.append({
            "id": f"maven:{name}",
            "component": name,
            "version": version,
            "kind": "maven",
            "origin": "Google Maven" if name.startswith(("androidx.", "com.google.android")) else "Maven Central",
            "homepage": r.get("homepage"),
            "license": r["license"],
            "evidence": r["evidence"],
            "included_in": "classes*.dex / resources" if r.get("distributed", True) else "not distributed – " + r["evidence"],
            "distributed": r.get("distributed", True),
            "notices": r.get("notices") or (["LICENSES/texts/Apache-2.0.txt"] if r["license"] == "Apache-2.0" else []),
            "status": "verified",
        })
    return out, missing


def manual():
    return json.load(open(os.path.join(TOOLS, "manual.json")))


def asset_files():
    base = os.path.join(ROOT, "app", "src", "main", "assets")
    for dp, _, fs in os.walk(base):
        for f in fs:
            yield os.path.relpath(os.path.join(dp, f), os.path.join(ROOT, "app", "src", "main"))  # assets/...


def uncovered_assets(entries):
    globs = [g for e in entries for g in e.get("files", [])]
    return [a for a in asset_files() if not any(fnmatch.fnmatch(a, g) for g in globs)]


def write_rust_texts(crates):
    """Each distributed crate's license and notice files, as shipped (LICENSES/third-party/rust/<crate>-<version>/)."""
    base = os.path.join(OUT, "third-party", "rust")
    if os.path.isdir(base):
        shutil.rmtree(base)
    for c in crates:
        if not c["distributed"]:
            continue
        files = c["_license_files"]
        src = c["_dir"]
        if not files:  # git crates keep their license at the repository root
            top = src
            while top != os.path.dirname(top) and not os.path.exists(os.path.join(top, "Cargo.lock")):
                top = os.path.dirname(top)
            files = sorted(f for f in os.listdir(top) if LICENSE_FILE.match(f))
            src = top
        dst = os.path.join(base, f"{c['component']}-{c['version']}")
        os.makedirs(dst, exist_ok=True)
        for f in files:
            shutil.copyfile(os.path.join(src, f), os.path.join(dst, f))
        c["license_files"] = [os.path.relpath(os.path.join(dst, f), ROOT) for f in files]


def ship_notices(entries):
    """Every notice an entry names must be in the APK: files outside LICENSES/ and the app's assets are copied to
    LICENSES/repo/<path> (e.g. third_party/media3-effect/KLIPP.md) and the entry points at the copy."""
    base = os.path.join(OUT, "repo")
    if os.path.isdir(base):
        shutil.rmtree(base)
    for e in entries:
        shipped = []
        for n in e.get("notices", []):
            if n.startswith(("LICENSES/", "app/src/main/assets/")):
                shipped.append(n)
                continue
            src = os.path.join(ROOT, n)
            if not os.path.isfile(src):
                raise SystemExit(f"{e['id']}: notice file not found: {n}")
            dst = os.path.join(base, n)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            shipped.append(os.path.relpath(dst, ROOT))
        e["notices"] = shipped


def missing_notices(entries):
    return [f"{e['id']}: notice file not found: {n}" for e in entries for n in e.get("notices", [])
            if not os.path.isfile(os.path.join(ROOT, n))]


def verbatim_problems(crates):
    """License and notice files that must be byte-identical to their originals (copied, never edited)."""
    pairs = [("LICENSES/texts/GPL-3.0.txt", "LICENSE")]
    for m in ("media3-effect", "media3-exoplayer", "media3-transformer"):
        pairs.append(("LICENSES/texts/Apache-2.0.txt", f"third_party/{m}/LICENSE"))
    for f in ("LICENSE", "AUTHORS"):
        pairs.append((f"LICENSES/third-party/whisper.cpp/{f}", f"third_party/whisper.cpp/{f}"))
    out = [f"{a} differs from its original {b}" for a, b in pairs
           if open(os.path.join(ROOT, a), "rb").read() != open(os.path.join(ROOT, b), "rb").read()]
    roots = {}
    for c in crates:
        if not c["distributed"]:
            continue
        files, src = c["_license_files"], c["_dir"]
        if not files:  # git crates: the repository root
            top = src
            while top != os.path.dirname(top) and not os.path.exists(os.path.join(top, "Cargo.lock")):
                top = os.path.dirname(top)
            files, src = sorted(f for f in os.listdir(top) if LICENSE_FILE.match(f)), top
            roots[os.path.basename(os.path.dirname(top)).rsplit("-", 1)[0]] = top  # filmcraft, craft-libs
        for f in files:
            shipped = os.path.join(OUT, "third-party", "rust", f"{c['component']}-{c['version']}", f)
            if not os.path.isfile(shipped) or open(shipped, "rb").read() != open(os.path.join(src, f), "rb").read():
                out.append(f"LICENSES/third-party/rust/{c['component']}-{c['version']}/{f} is not its crate's original (run inventory.py build)")
    for name, top in roots.items():
        for f in ("LICENSE-MIT", "LICENSE-APACHE", "NOTICE"):
            shipped = os.path.join(OUT, "third-party", name, f)
            if os.path.isfile(os.path.join(top, f)) and (not os.path.isfile(shipped) or open(shipped, "rb").read() != open(os.path.join(top, f), "rb").read()):
                out.append(f"LICENSES/third-party/{name}/{f} is not {name}'s original")
    return out


def rust_status(c, overrides):
    o = overrides.get(c["component"], {})
    if not c["distributed"]:
        return "verified", o.get("notes", "Build-time only: not part of the APK.")
    if o:
        return o["status"], o["notes"]
    if c.get("license_files"):
        return "verified", ""
    return "incomplete", "No license file in the crate or its repository root."


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    deps = sys.argv[sys.argv.index("--gradle-deps") + 1] if "--gradle-deps" in sys.argv else None
    crates = cargo()
    man = manual()
    problems = []
    mav = []
    if deps:
        mav, missing = maven(deps)
        problems += [f"Maven dependency without a reviewed license entry (tools/licenses/maven-reviewed.json): {m}" for m in missing]
    problems += [f"Asset without a license entry (tools/licenses/manual.json 'files'): {a}" for a in uncovered_assets(man["entries"])]
    reviewed_crates = man.get("rust_reviewed", {})  # "name@version": the license expression that was reviewed
    for c in crates:
        key = f"{c['component']}@{c['version']}"
        if c["distributed"] and reviewed_crates.get(key) != c["license"]:
            problems.append(f"Rust crate not reviewed (tools/licenses/manual.json rust_reviewed): {key} ({c['license']})")
    if mode == "build":
        write_rust_texts(crates)
        ship_notices(man["entries"])
        for c in crates:
            c["status"], c["notes"] = rust_status(c, man.get("rust_notes", {}))
            for k in ("_dir", "_license_files"):
                c.pop(k, None)
        for c in crates:
            c["notices"] = c.pop("license_files", [])
        inv = {
            "klipp": man["klipp"],
            "generated_by": "tools/licenses/inventory.py",
            "components": man["entries"] + sorted(mav, key=lambda e: e["component"]) + crates,
        }
        with open(os.path.join(OUT, "inventory.json"), "w", encoding="utf-8") as f:
            json.dump(inv, f, indent=1, ensure_ascii=False)
            f.write("\n")
        print(f"LICENSES/inventory.json: {len(inv['components'])} components "
              f"({len(man['entries'])} reviewed by hand, {len(mav)} Maven, {len(crates)} Rust)")
    if mode == "check":
        problems += verbatim_problems(crates)
        # the generated inventory must match what was reviewed (run `inventory.py build` after changing manual.json)
        try:
            shipped = json.load(open(os.path.join(OUT, "inventory.json")))
            ids = {e["id"] + "@" + str(e.get("version")) for e in shipped["components"]}
            for e in man["entries"]:
                if e["id"] + "@" + str(e.get("version")) not in ids:
                    problems.append(f"LICENSES/inventory.json is out of date: {e['id']} (run inventory.py build)")
            problems += missing_notices(shipped["components"])
        except FileNotFoundError:
            problems.append("LICENSES/inventory.json is missing (run inventory.py build)")
    if problems:
        print("\n".join(problems))
        if mode == "check":
            sys.exit(1)
    elif mode == "check":
        print("license inventory: every distributed component and asset has a reviewed entry")


if __name__ == "__main__":
    main()
