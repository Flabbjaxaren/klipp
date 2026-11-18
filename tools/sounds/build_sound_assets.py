#!/usr/bin/env python3
"""
Builds the versioned sound packs for Klipp's GitHub release (see README.md in this folder) from the publishers'
original files, checks them, and writes the manifest the app's pins come from.

    python3 tools/sounds/build_sound_assets.py [<sources dir> [<out dir>]] [--release sounds-1]

    (defaults: ~/AndroidStudioProjects/Klipp-builds/sounds-originals → ~/AndroidStudioProjects/Klipp-builds/sounds-1)

<sources dir> holds what was downloaded by hand in a browser (kenney.nl and bigsoundbank.com don't allow automated
downloading):
  kenney_<slug>.zip   – the seven Kenney packs exactly as kenney.nl serves them (impact-sounds, interface-sounds,
                        ui-audio, rpg-audio, sci-fi-sounds, digital-audio, music-jingles)
  <id>.ogg            – each BigSoundBank sound the app's packs cut clips from (the ids in SoundFx.kt), as served
                        by its page bigsoundbank.com/s<id>.html (OGG download)
Redistribution is checked before anything is packed: a Kenney zip must carry Kenney's CC0 License.txt; every
BigSoundBank sound must be marked CC0 on its page (tools/sounds/evidence/bigsoundbank-licenses-*.md, read 2026-11-18).
Nothing is changed in the files: a Kenney pack is published as its own zip (renamed), the BigSoundBank sounds of one
Klipp pack go into one zip with CREDITS.txt (page, author, license marking, SHA-256 of each file) and the CC0 1.0 legal
code. Every zip is written deterministically (sorted, fixed timestamps), so
the same sources give the same SHA-256.

Writes <out dir>/<asset files>, tools/sounds/<release>.json (asset, sha256, bytes, license, sources) and prints the
Kotlin pins for SoundFx.HOSTED.
"""
import hashlib
import json
import os
import re
import sys
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOUNDFX = os.path.join(ROOT, "app", "src", "main", "java", "se", "gendut", "klipp", "engine", "SoundFx.kt")
FIXED_TIME = (2026, 1, 1, 0, 0, 0)
KENNEY = {  # Klipp pack id → kenney.nl asset slug
    "kenney-impact": "impact-sounds", "kenney-interface": "interface-sounds", "kenney-ui": "ui-audio",
    "kenney-rpg": "rpg-audio", "kenney-scifi": "sci-fi-sounds", "kenney-digital": "digital-audio",
    "kenney-jingles": "music-jingles",
}


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()


EVIDENCE = os.path.join(ROOT, "tools", "sounds", "evidence")


def bsb_evidence():
    """id → (page, author, marking) from the recorded license check of BigSoundBank's pages."""
    out = {}
    for f in sorted(os.listdir(EVIDENCE)):
        if f.startswith("bigsoundbank-licenses-") and f.endswith(".md"):
            for line in open(os.path.join(EVIDENCE, f), encoding="utf-8"):
                m = re.match(r"\|\s*(\d{4})\s*\|\s*\[[^\]]*\]\(([^)]+)\)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|", line)
                if m:
                    out[m.group(1)] = (m.group(2), m.group(3), m.group(4))
    return out


def bsb_packs():
    """The BigSoundBank ids each Klipp pack cuts from, read from SoundFx.kt (the one place they are listed)."""
    src = open(SOUNDFX, encoding="utf-8").read()
    packs = {}
    for m in re.finditer(r'Pack\("(bsb-[a-z]+)".*?\)\),', src, re.S):
        packs[m.group(1)] = sorted(set(re.findall(r'Clip\("(\d{4})"', m.group(0))))
    return packs


def main():
    release = sys.argv[sys.argv.index("--release") + 1] if "--release" in sys.argv else "sounds-1"
    args = [a for a in sys.argv[1:] if not a.startswith("--") and a != release]
    builds = os.path.expanduser("~/AndroidStudioProjects/Klipp-builds")
    sources = args[0] if args else os.path.join(builds, "sounds-originals")
    out = args[1] if len(args) > 1 else os.path.join(builds, release)
    evidence = bsb_evidence()
    cc0 = open(os.path.join(EVIDENCE, "CC0-1.0-legalcode.txt"), "rb").read()
    os.makedirs(out, exist_ok=True)
    problems, assets = [], []

    for pack, slug in KENNEY.items():
        src = os.path.join(sources, f"kenney_{slug}.zip")
        if not os.path.isfile(src):
            problems.append(f"missing {os.path.basename(src)} (kenney.nl/assets/{slug} → Download)")
            continue
        with zipfile.ZipFile(src) as z:
            names = z.namelist()
            lic = [n for n in names if os.path.basename(n).lower() == "license.txt"]
            text = z.read(lic[0]).decode("utf-8", "replace") if lic else ""
            sounds = [n for n in names if n.lower().endswith((".ogg", ".wav", ".mp3")) and not n.endswith("Preview.ogg")]
        if "CC0" not in text and "publicdomain/zero" not in text:
            problems.append(f"{os.path.basename(src)}: no CC0 License.txt inside – not packed")
            continue
        if not sounds:
            problems.append(f"{os.path.basename(src)}: no sounds inside (an HTML page saved as .zip?)")
            continue
        name = f"{pack}-{release}.zip"
        dst = os.path.join(out, name)
        with open(src, "rb") as i, open(dst, "wb") as o:
            o.write(i.read())  # unchanged, only renamed
        assets.append({"pack": pack, "file": name, "sha256": sha(dst), "bytes": os.path.getsize(dst), "license": "CC0-1.0",
                       "source": f"https://kenney.nl/assets/{slug}", "original": os.path.basename(src),
                       "original_sha256": sha(src), "license_text": text.strip()[:300], "sounds": len(sounds)})

    for pack, ids in bsb_packs().items():
        missing = [i for i in ids if not os.path.isfile(os.path.join(sources, f"{i}.ogg"))]
        if missing:
            problems.append(f"{pack}: missing " + ", ".join(f"{i}.ogg (bigsoundbank.com/s{i}.html)" for i in missing))
            continue
        bad = [i for i in ids if open(os.path.join(sources, f"{i}.ogg"), "rb").read(4) != b"OggS"]
        if bad:
            problems.append(f"{pack}: not OGG files: {bad}")
            continue
        not_cc0 = [i for i in ids if i not in evidence or "CC0" not in evidence[i][2]]
        if not_cc0:
            problems.append(f"{pack}: no recorded CC0 marking for {not_cc0} – check their pages and the evidence file first")
            continue
        name = f"{pack}-{release}.zip"
        dst = os.path.join(out, name)
        credits = ("Sounds from BigSoundBank (https://bigsoundbank.com) – CC0 1.0 Universal (LICENSE-CC0-1.0.txt;\n"
                   "https://creativecommons.org/publicdomain/zero/1.0/). Credit is optional; given here: Additional sounds:\n"
                   "Joseph SARDIN - BigSoundBank.com. The files are unmodified; Klipp cuts its clips on the phone.\n\n"
                   + "".join(f"{i}.ogg  {evidence[i][0]}  author: {evidence[i][1]}  page says: {evidence[i][2]}  "
                             f"sha256 {sha(os.path.join(sources, f'{i}.ogg'))}\n" for i in ids))
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as z:  # OGG is compressed already
            for n, data in [("CREDITS.txt", credits.encode()), ("LICENSE-CC0-1.0.txt", cc0)] + [(f"{i}.ogg", open(os.path.join(sources, f"{i}.ogg"), "rb").read()) for i in ids]:
                info = zipfile.ZipInfo(n, FIXED_TIME)
                info.external_attr = 0o644 << 16
                z.writestr(info, data)
        assets.append({"pack": pack, "file": name, "sha256": sha(dst), "bytes": os.path.getsize(dst), "license": "CC0-1.0",
                       "source": "https://bigsoundbank.com", "license_evidence": "tools/sounds/evidence/bigsoundbank-licenses-2026-11-18.md",
                       "sounds": [{"file": f"{i}.ogg", "page": evidence[i][0], "author": evidence[i][1], "marking": evidence[i][2],
                                   "sha256": sha(os.path.join(sources, f"{i}.ogg"))} for i in ids]})

    manifest = {"release": release, "url": f"https://github.com/Flabbjaxaren/klipp/releases/tag/{release}", "assets": assets,
                "problems": problems}
    with open(os.path.join(ROOT, "tools", "sounds", f"{release}.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)
        f.write("\n")
    for p in problems:
        print("NOT BUILT:", p)
    print("\nPins for SoundFx.HOSTED (only the packs that were built):")
    for a in assets:
        print(f'        "{a["pack"]}" to Hosted("{a["file"]}", "{a["sha256"]}", {a["bytes"]}),')
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
