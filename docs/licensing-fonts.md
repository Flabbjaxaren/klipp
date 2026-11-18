# Klipp bundled fonts – licensing audit

Date: 2026-11-18. Scope: the 33 families / 55 font files in `app/src/main/assets/fonts/` (added in commit 3e07736, 0.18.31, unchanged since).
Per family: `LICENSES/inventory.json` (ids `font:<folder>`).

## How the files were made

No script is in the repo, in `tools/`, `docs/` or in `Klipp-builds`. The commit message says "subset to Latin"; THIRD_PARTY.md says Inter, DM Sans, Merriweather and Archivo were fixed at one optical size/width with the weight axis kept; The audit confirms this and reconstructs the recipe:

- **Source:** google/fonts. Every file's name ID 5 equals the upstream file's at commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0` (2026-10-23, the last commit before the import). None of the 33 families' font or license files changed between that commit and `main` (`5bd87dfc`, 2026-11-18), so the comparison holds for both.
- **Subset (all 55 files):** fontTools `pyftsubset` with a Latin set. Every code point kept by any of our files lies in this set, and nothing in it was dropped:
  `U+0000-024F, U+0259, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0300-036F, U+1E00-1EFF, U+2000-20CF, U+2100-214F, U+2190-22FF, U+25A0-26FF, U+FB00-FB06`.
  Dropped: Cyrillic, Greek, Devanagari (Poppins), IPA, most spacing modifiers, Latin Extended-C/D, PUA, box drawing, and so on. Options `--layout-features=* --name-IDs=* --glyph-names --notdef-outline` reproduce the same glyph order and cmap for every file. 18 files come out byte-identical with fontTools 4.68 (Poppins ×4, Space Mono ×4, DM Serif Display ×2, Anton, Bebas Neue, Archivo Black, Abril Fatface, Righteous, Bungee, Pacifico, Permanent Marker). 22 more differ only in `head` (timestamp/checksum), and 7 (Montserrat, Work Sans, Outfit, Figtree) differ only by about 4 bytes in GSUB, which looks like a fontTools version difference. The instanced files differ in more tables (Archivo only in GSUB and OS/2; Inter, DM Sans and Merriweather in many), so the instancing options or fontTools version used then were not reproduced exactly. Their glyph order and cmap still match.
- **Instancing (4 families):** `fontTools.varLib.instancer` before subsetting:
  - Inter: `opsz=32` (outlines identical to upstream at 32; the upstream default is 14).
  - DM Sans: `opsz≈32` (advances match at 32, outlines within 1 unit; the upstream default is 9).
  - Archivo: `wdth=100` (identical).
  - Merriweather: `opsz=72, wdth=100` (advances match, outlines within 1 unit; the STAT table that remains still names the opsz value "72pt"; the upstream default opsz is 18).

  In all four only `wght` is left in fvar. Inter, DM Sans and Merriweather also lost all fvar named instances, and DM Sans (both files) and Merriweather Italic lost MVAR.
- **Other table changes:** `DSIG` dropped in 24 of 55 files (pyftsubset default), `FFTM` in Abril Fatface, `GDEF` in Poppins (empty after the Devanagari was removed), and `meta` in Merriweather.
- **Names:** every name record that remains is byte-identical to upstream. Only the records that subsetting or instancing left unreferenced were removed (opsz/wdth axis-value names, the Merriweather named-instance names, one Roboto Slab `ss02` name). **All files still carry the original family names** in IDs 1/4/6 (and 16/17/25 where upstream has them), in the fvar instance names and in STAT.
- **License files:** all 33 `OFL.txt` / `LICENSE.txt` are byte-identical to upstream at that commit.

## How the app uses the names

`Fonts.init` (engine/Layers.kt) reads `fonts.json`. The `"name"` is the label in the title/caption font picker (`Fonts.all`, each name drawn in its own face). The same string is the persisted font ID in projects (`TitleLook` `font=…` in the `klipp:` look string, and `CaptionStyle.font`). Inter is the exception: it is shown and saved as **"Inter Display"**, because "Inter" is the old default mapped to the phone's sans. The family names are therefore user-visible in the UI and stored in users' projects.

## Licence basis used

- OFL 1.1, condition 3: "No Modified Version of the Font Software may use the Reserved Font Name(s) unless explicit written permission is granted by the corresponding Copyright Holder."
- OFL-FAQ 2.6: removing parts of a font (unused glyphs, smart code) is modification. OFL-FAQ 5.9: rebuilds that change the font data are Modified Versions. The FAQ does not mention instancing by name, but instancing changes the outlines and drops axes, so it is clearly a change of the font data.
- The webfont exception (OFL-FAQ 2.7/2.8, "Functional Equivalence") needs "the same full character inventory", so it does not cover a Latin subset. The document-embedding exception (FAQ 1.12) covers fonts embedded in documents, not fonts shipped as files inside an app. FAQ 1.20 says fonts bundled with a mobile app must comply with the license.
- OFL-FAQ 3.8: internal names must be changed "Only if there are declared RFNs." With no RFN, the modified font may keep its name. FAQ 1.4/1.5/3.3: selling the app with the fonts bundled is fine (they are not sold by themselves).
- The OFL grants no trademark rights. Name ID 7 trademark notices (Inter, Archivo, Bebas Neue, Bungee, JetBrains Mono, Roboto Slab, Permanent Marker, and so on) are a separate matter from RFNs and are noted per file.

## Summary table

| Family | Files | License | RFN (from OFL.txt) | Modification | Verdict |
|---|---|---|---|---|---|
| Inter | Regular, Italic | OFL-1.1 | – | subset + opsz pinned 32 | modified-no-rfn |
| Montserrat | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| Poppins | Regular, Italic, Bold, BoldItalic | OFL-1.1 | – | subset | modified-no-rfn |
| **Raleway** | Regular, Italic | OFL-1.1 | **"Raleway"** | subset | **modified-with-rfn-conflict** |
| Work Sans | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| DM Sans | Regular, Italic | OFL-1.1 | – | subset + opsz pinned ≈32 | modified-no-rfn |
| Nunito | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| Outfit | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Space Grotesk | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Manrope | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Figtree | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| Archivo | Regular, Italic | OFL-1.1 | – | subset + wdth pinned 100 | modified-no-rfn |
| Bebas Neue | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Oswald | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Anton | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Archivo Black | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| **Abril Fatface** | Regular | OFL-1.1 | **"Abril", "Abril Fatface"** | subset | **modified-with-rfn-conflict** |
| **Righteous** | Regular | OFL-1.1 | **"Righteous"** | subset | **modified-with-rfn-conflict** |
| Bungee | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| **Playfair Display** | Regular, Italic | OFL-1.1 | **"Playfair Display"** | subset | **modified-with-rfn-conflict** |
| **Lora** | Regular, Italic | OFL-1.1 | **"Lora"** | subset | **modified-with-rfn-conflict** |
| **Merriweather** | Regular, Italic | OFL-1.1 | **"Merriweather"** | subset + opsz ≈72 / wdth 100 pinned | **modified-with-rfn-conflict** |
| Cormorant Garamond | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| **Libre Baskerville** | Regular, Italic | OFL-1.1 | **"Libre Baskerville"** | subset | **modified-with-rfn-conflict** |
| DM Serif Display | Regular, Italic | OFL-1.1 | "Source" (not part of the name) | subset | modified-no-rfn (RFN not used) |
| EB Garamond | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| Roboto Slab | Regular | Apache-2.0 | n/a | subset | modified (Apache: notice of change needed) |
| Pacifico | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| Caveat | Regular | OFL-1.1 | – | subset | modified-no-rfn |
| **Dancing Script** | Regular | OFL-1.1 | **"Dancing Script"** | subset | **modified-with-rfn-conflict** |
| Permanent Marker | Regular | Apache-2.0 | n/a | subset | modified (Apache: notice of change needed) |
| JetBrains Mono | Regular, Italic | OFL-1.1 | – | subset | modified-no-rfn |
| Space Mono | Regular, Italic, Bold, BoldItalic | OFL-1.1 | – | subset | modified-no-rfn |

No file is an unmodified original. Every file is subset.

## Conflicts (8 families, 13 files)

All eight are **subset only** (no instancing), except Merriweather. In each, the RFN is still in name IDs 1/4/6 (and 16 for Raleway and Merriweather), in the fvar/STAT names, in the app's picker label, and in saved projects.

| Family | RFN | Copyright holder (OFL.txt / name ID 0 and 7) | Ours → upstream size (sum of files) |
|---|---|---|---|
| Abril Fatface | "Abril", "Abril Fatface" | TypeTogether | 56 → 66 KiB |
| Dancing Script | "Dancing Script" | The Dancing Script Project Authors (Impallari Type / Pablo Impallari) | 127 → 131 KiB |
| Libre Baskerville | "Libre Baskerville" | The Libre Baskerville Project Authors (Impallari Type / Pablo Impallari) | 306 → 331 KiB |
| Lora | "Lora" | The Lora Project Authors (Cyreal) | 255 → 423 KiB |
| Merriweather | "Merriweather" | The Merriweather Project Authors (Sorkin Type / Eben Sorkin) | 1 986 → 9 003 KiB |
| Playfair Display | "Playfair Display" | The Playfair Display Project Authors (Claus Eggers Sørensen) | 391 → 566 KiB |
| Raleway | "Raleway" | The Raleway Project Authors (impallari@gmail.com); name ID 7: trademark of Matt McInerney | 435 → 616 KiB |
| Righteous | "Righteous" | Brian J. Bonislawsky DBA Astigmatic (AOETI) | 40 → 42 KiB |

Libre Baskerville: name ID 0 inside the font has no RFN clause, but the OFL.txt that grants the license declares it, and OFL.txt governs.

### Option 1 – ship the unmodified upstream files (same name, no permission needed)

- **Size:** +7.6 MiB uncompressed in total (about +5.3 MiB deflated in the APK). Merriweather alone is +7.0 MiB (+4.9 MiB deflated). The other seven together add about +0.6 MiB (+0.4 MiB deflated).
- **Look, the seven subset-only families:** the upstream file has the same outlines and layout tables for every glyph we ship. Reproducing our files from upstream with pyftsubset gives every table identical except `head`: Abril Fatface and Righteous byte-identical; Raleway, Lora, Playfair Display, Libre Baskerville and Dancing Script differ only in `head`. Existing projects should look the same. The only visible difference: characters outside the subset (for example Cyrillic) that now fall back to the phone's font would render in the bundled face.
  - Caveat: the app sets only `'wght'` via `setFontVariationSettings`, so axis defaults matter. For these seven the upstream file has no other axes, so nothing changes.
- **Look, Merriweather:** the upstream default is `opsz=18`, but our pinned file uses about `opsz=72` (advances differ by about 6 %). To keep existing projects identical, the app would have to pass `'opsz' 72, 'wdth' 100` together with `'wght'` for Merriweather. That is a code change, not a font change, and it is allowed for an unmodified font.
- Code impact: fonts.json and the saved names stay as they are (`Fonts.typeface` reads the same files by directory; only the Merriweather variation settings change).

### Option 2 – keep the modified files but rename them (internally and in the UI)

- Change the name records (IDs 1, 3, 4, 6, 16, 17, 21, 22, 25, the fvar instance and STAT names) so that the RFN no longer appears, give each family a new picker label in fonts.json (OFL-FAQ 5.3: the RFN must not be used "in any way to identify the font to the user"), and map the old saved IDs ("Raleway", "Lora", …) to the new names when projects load (`TitleLook` decode / `CaptionStyle.font` / `Fonts.typeface`).
- **Size:** unchanged. **Look:** unchanged, since the outlines stay the same, and old projects keep their look through the mapping.
- Cost: a name-table rewrite step in the font build, migration code for saved IDs, new names users won't recognise. Imported projects shared from older builds still contain the old strings, so the mapping has to stay permanently.
- Unclear: whether the UI may say "based on Raleway" or similar. The FAQ wording on referring to the original in descriptions was not verified in this audit, so treat it as **unclear** and do not rely on it without checking.

### Option 3 – ask the copyright holder for written permission (OFL condition 3, FAQ 5.8)

- Ask each holder listed above for permission to distribute a Latin subset (and for Merriweather an opsz/wdth instance) under the RFN, inside an app that may be sold.
- **Size / look:** unchanged. Cost: time and an uncertain outcome. The permission must be explicit and in writing, and should be kept with the license file (for example noted in THIRD_PARTY.md). Several of the eight share a holder (Impallari Type: Dancing Script, Libre Baskerville, and per its OFL.txt address also Raleway), so one request may cover several families.

The options can be mixed per family. For example: upstream files for the seven small ones (+0.6 MiB), and for Merriweather either the upstream file plus the `opsz`/`wdth` setting, a rename, or permission.

## Apache-2.0 families (Roboto Slab, Permanent Marker)

- `LICENSE.txt` is identical to upstream. There is no RFN concept and no trademark grant (Apache §6).
- §4(b): "You must cause any modified files to carry prominent notices stating that You changed the files." Both fonts are subset. The only notice today is THIRD_PARTY.md ("Subset to Latin"), not in the font folder or the font. Adding a short notice next to the files (or in the name table, ID 5 or a comment) would satisfy this more clearly.
- §4(d): google/fonts has no NOTICE file for either. Roboto Slab has a `COPYRIGHT.txt` upstream, but it only holds another copy of the Apache-2.0 text (no attribution lines), so leaving it out loses nothing. The copyright lines are in name ID 0 of both fonts.

## Other observations (not conflicts)

- **DM Serif Display:** RFN "Source" (Adobe). The modified file is named "DM Serif Display", which does not contain the RFN, so no conflict.
- **Inter:** shown and saved as "Inter Display", while the internal family stays "Inter" (no RFN, so allowed). The instance is the opsz 32 (display) design, so the label fits. The Inter project's own releases also include a family called "Inter Display" (from memory, not checked in this audit).
- **Trademark notices** (name ID 7) exist for many families, including non-RFN ones. The OFL does not license trademarks. Naming a font by its own name to identify it is normal practice, but that is a trademark question, not an OFL one. Not assessed further.
- Optional OFL best practice (FAQ 3.8): even without an RFN, changing the name or adding a suffix to modified fonts is recommended but not required.

## Not verified / unclear

- The exact script and fontTools version used in October: not in the repo or Klipp-builds. The recipe above is inferred (cmap union and reproduction), not recovered.
- Merriweather and DM Sans pin values (≈72 and ≈32) are inferred from advance widths. Outlines differ by ≤1 font unit from a fontTools 4.68 instance, probably rounding or version differences. Inter (32) and Archivo (wdth 100) matched exactly.
- Whether any holder has already granted Google, or anyone else, a general permission that would extend to Klipp: not checked. Assume not.
- Whether a description such as "based on X" may name the RFN: unclear (see Option 2).
- Trademark exposure of the displayed names: out of scope.
