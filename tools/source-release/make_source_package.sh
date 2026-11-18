#!/bin/bash
# The Corresponding Source of a Klipp release as one archive: the repository at the release's commit, the source of
# every Rust crate linked into libklipp_bridge.so (vendored, so it builds offline), the source jars of the Maven
# libraries in the APK, the build scripts, and instructions. See docs/source-release.md.
#
#   tools/source-release/make_source_package.sh <git ref, e.g. v0.20.0-beta.1> <output dir>
#
# Needs: git, cargo, python3, network access (crates and Maven source jars are fetched). Writes
# <output dir>/klipp-<version>-source.tar.gz and its .sha256. Never includes keys or local settings: the archive is
# built from `git archive` of a committed ref, plus downloaded third-party source.
set -euo pipefail
REF=${1:?git ref}
OUTDIR=${2:?output dir}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
COMMIT=$(git -C "$ROOT" rev-parse "$REF^{commit}")
VERSION=$(git -C "$ROOT" show "$COMMIT:app/build.gradle.kts" | sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' | head -1)
NAME="klipp-$VERSION-source"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUTDIR"
OUTDIR=$(cd "$OUTDIR" && pwd)
PKG="$WORK/$NAME"

echo "== repository at $COMMIT ($VERSION)"
git -C "$ROOT" archive --format=tar --prefix="$NAME/" "$COMMIT" | tar -x -C "$WORK"
echo "$COMMIT" > "$PKG/SOURCE_COMMIT"

echo "== Rust crates (cargo vendor)"
mkdir -p "$PKG/rust/.cargo"
# where cargo finds each crate before vendoring (git dependencies: their checkouts)
(cd "$PKG/rust" && cargo metadata --locked --format-version 1 --filter-platform aarch64-linux-android) > "$WORK/meta.json"
(cd "$PKG/rust" && cargo vendor --locked --versioned-dirs vendor > .cargo/config.toml)
# `cargo vendor` copies a git dependency's crate folder only. A crate may include files from elsewhere in its repository
# (filmcraft-text embeds FilmCraft's assets/fonts with include_bytes!("../../../assets/fonts/…")): copy every such file
# to the place the vendored crate looks for it, and keep each git dependency's whole repository at its pinned commit.
python3 - "$WORK/meta.json" "$PKG/rust/vendor" "$PKG/third_party-sources/git" <<'EOF'
import json, os, re, shutil, subprocess, sys
meta, vendor, gitdir = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
inc = re.compile(r'include_(?:bytes|str)!\(\s*"([^"]+)"\s*\)')
repos = {}
for p in meta["packages"]:
    src = p.get("source") or ""
    if not src.startswith("git+"):
        continue
    crate = os.path.dirname(p["manifest_path"])
    root = crate
    while root != "/" and not os.path.exists(os.path.join(root, ".git")) and not os.path.exists(os.path.join(root, "Cargo.lock")):
        root = os.path.dirname(root)
    repos[src.split("#")[0]] = (root, src)
    vdir = os.path.join(vendor, f"{p['name']}-{p['version']}")
    for dp, _, fs in os.walk(os.path.join(crate, "src")):
        for f in fs:
            if not f.endswith(".rs"):
                continue
            rel_dir = os.path.relpath(dp, crate)
            for path in inc.findall(open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()):
                orig = os.path.normpath(os.path.join(dp, path))
                dest = os.path.normpath(os.path.join(vdir, rel_dir, path))
                if os.path.commonpath([dest, vdir]) != vdir and os.path.isfile(orig):
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    shutil.copyfile(orig, dest)
                    print("outside its crate:", os.path.relpath(dest, os.path.dirname(vendor)))
os.makedirs(gitdir, exist_ok=True)
for url, (root, src) in sorted(repos.items()):
    rev = src.split("#")[-1]
    name = url.rstrip("/").split("/")[-1].split("?")[0]
    out = os.path.join(gitdir, f"{name}-{rev[:12]}.tar.gz")
    # the checkout cargo used, as a whole repository snapshot (license files, NOTICE, ATTRIBUTION, assets)
    subprocess.run(["tar", "-czf", out, "--exclude=.git", "-C", os.path.dirname(root), os.path.basename(root)], check=True)
    print("repository snapshot:", os.path.basename(out), "from", url, rev)
EOF

echo "== Maven libraries' source jars (releaseRuntimeClasspath)"
# the dependency list is resolved from the archived build files, so it matches the release
(cd "$PKG" && ./gradlew -q --no-daemon :app:dependencies --configuration releaseRuntimeClasspath) > "$WORK/deps.txt"
python3 - "$WORK/deps.txt" "$PKG/third_party-sources/maven" <<'EOF'
import os, re, subprocess, sys
deps, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
coords = set()
for line in open(deps):
    line = line.rstrip()
    if line.endswith("(c)") or "project" in line:  # version constraints are not dependencies
        continue
    m = re.search(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::([0-9][^ ]*?))?(?: -> ([0-9][^ ]*))?(?: \(\*\))?\s*$", line)
    if m and (m.group(3) or m.group(4)):
        g, a, v, v2 = m.groups(); coords.add((g, a, v2 or v))
missing = []
for g, a, v in sorted(coords):
    name = f"{a}-{v}-sources.jar"
    for base in ("https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2"):
        dst = os.path.join(out, f"{g}__{name}")
        if subprocess.run(["curl", "-sfL", "-m", "120", "-o", dst, f"{base}/{g.replace('.', '/')}/{a}/{v}/{name}"]).returncode == 0:
            break
        if os.path.exists(dst):
            os.remove(dst)
    else:
        missing.append(f"{g}:{a}:{v}")
with open(os.path.join(out, "MISSING.txt"), "w") as f:
    f.write("Artifacts without a published source jar (BOMs and empty artifacts have none):\n" + "\n".join(missing) + "\n")
print(f"{len(coords) - len(missing)} source jars, {len(missing)} without (see MISSING.txt)")
EOF

# the dependency listing above leaves Gradle's own folders behind: not source
rm -rf "$PKG/.gradle" "$PKG/build" "$PKG/app/build" "$PKG/third_party/"*/build "$PKG/local.properties"

echo "== instructions"
cp "$ROOT/tools/source-release/BUILDING-FROM-SOURCE.md" "$PKG/BUILDING-FROM-SOURCE.md"

tar -C "$WORK" -czf "$OUTDIR/$NAME.tar.gz" "$NAME"
(cd "$OUTDIR" && shasum -a 256 "$NAME.tar.gz" > "$NAME.tar.gz.sha256")
echo "== $OUTDIR/$NAME.tar.gz"
cat "$OUTDIR/$NAME.tar.gz.sha256"
