# Klipp's sound packs as versioned files

The app's sound packs are CC0 (Kenney, BigSoundBank). Instead of fetching them from the publishers' sites at run time –
Kenney's old zip links stopped working in 2026-11, and BigSoundBank excludes its audio folder from automated access –
Klipp downloads them only from its own GitHub release **`sounds-1`**, each file pinned by size and SHA-256
(`SoundFx.HOSTED`). A pack without a pin can't be downloaded (the sound library says why); nothing is fetched from the
publishers' old links any more. Packs already on a phone are kept as they are (installed = their `.done` marker;
nothing is re-downloaded), and so are the projects that use them.

## Making a release

1. **Download the originals by hand, in a browser** (both sites disallow automated downloading – don't script it):
   - Kenney, each pack's page → *Download*: `kenney.nl/assets/impact-sounds`, `interface-sounds`, `ui-audio`,
     `rpg-audio`, `sci-fi-sounds`, `digital-audio`, `music-jingles`. Keep the file names (`kenney_<slug>.zip`).
   - BigSoundBank: every id that `SoundFx.kt`'s BigSoundBank packs cut from – `build_sound_assets.py` lists the
     missing ones with their pages (`bigsoundbank.com/s<id>.html` → OGG). Name them `<id>.ogg`.
   Put them all in **`~/AndroidStudioProjects/Klipp-builds/sounds-originals/`** (kept there as the originals).
2. `python3 tools/sounds/build_sound_assets.py` – checks redistribution first (each Kenney zip carries its CC0
   `License.txt` and sounds; each BigSoundBank sound is marked CC0 in `evidence/bigsoundbank-licenses-2026-11-18.md`
   and is an OGG file), then packs one zip per Klipp pack (deterministic; BigSoundBank zips get `CREDITS.txt` with page,
   author, marking and SHA-256 per file, and `LICENSE-CC0-1.0.txt`) into `Klipp-builds/sounds-1/`, writes
   `tools/sounds/sounds-1.json` and prints the pins. Anything that fails a check is not packed.
3. Paste the pins into `SoundFx.HOSTED`; `SoundPacksTest` checks them against `sounds-1.json`.
4. Publish a GitHub release `sounds-1` (pre-release, not "latest") with the zips from the out folder, and test a
   download of each pack on a phone. A changed file is a new release (`sounds-2`), never a replaced asset.

The zips hold the publishers' files unchanged (Kenney's own zip renamed; BigSoundBank's OGGs plus a CREDITS.txt).
Credit is not required by CC0 but given: Kenney (kenney.nl), BigSoundBank / Joseph Sardin.
