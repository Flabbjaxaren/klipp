# Klipp's sound packs as versioned files

The app's sound packs are CC0 (Kenney, BigSoundBank). Instead of fetching them from the publishers' sites at run time –
Kenney's old zip links stopped working in 2026-11, and BigSoundBank excludes its audio folder from automated access –
Klipp downloads them from its own GitHub release **`sounds-1`**, each file pinned by size and SHA-256
(`SoundFx.HOSTED`). A pack without a pin keeps the old source until it has one, so nothing breaks before the release
exists. Packs already on a phone are kept as they are (installed = their `.done` marker; nothing is re-downloaded).

## Making a release

1. **Download the originals by hand, in a browser** (both sites disallow automated downloading – don't script it):
   - Kenney, each pack's page → *Download*: `kenney.nl/assets/impact-sounds`, `interface-sounds`, `ui-audio`,
     `rpg-audio`, `sci-fi-sounds`, `digital-audio`, `music-jingles`. Keep the file names (`kenney_<slug>.zip`).
   - BigSoundBank: every id that `SoundFx.kt`'s BigSoundBank packs cut from – `build_sound_assets.py` lists the
     missing ones with their pages (`bigsoundbank.com/s<id>.html` → OGG). Name them `<id>.ogg`.
   Put them all in one folder.
2. `python3 tools/sounds/build_sound_assets.py <that folder> <out folder>` – checks each Kenney zip has its CC0
   `License.txt` and sounds, each BigSoundBank file is OGG, packs one zip per Klipp pack (deterministic), writes
   `tools/sounds/sounds-1.json` and prints the pins.
3. Paste the pins into `SoundFx.HOSTED`; `SoundPacksTest` checks them against `sounds-1.json`.
4. Publish a GitHub release `sounds-1` (pre-release, not "latest") with the zips from the out folder, and test a
   download of each pack on a phone. A changed file is a new release (`sounds-2`), never a replaced asset.

The zips hold the publishers' files unchanged (Kenney's own zip renamed; BigSoundBank's OGGs plus a CREDITS.txt).
Credit is not required by CC0 but given: Kenney (kenney.nl), BigSoundBank / Joseph Sardin.
