# Regression run

A short, deterministic check of what Klipp shows, on a phone, through the app's agent bridge (Settings → Developer →
"Let the computer control Klipp"). It works in a project of its own ("Klipp regress") and reopens the project that was
open, with its playhead, mode and selection, afterwards. Nothing is deleted.

    ./make_clips.sh clips                       # frame-coded test clips (ffmpeg), once
    adb -s <serial> forward tcp:8765 localabstract:klipp-mcp
    ./regress.py --serial <serial> [--only R1,R3] [--repeat 5]

The test timelines (built once, kept in the test project):

| test | what | catches |
|---|---|---|
| R1 straight | 24, 29.97, 30, 50, 60, 25 fps and 120 fps at 25 %, in points between source frames | wrong source frame, problems after a rebuild |
| R2 layers | V2 and V3 over V1, every clip with its own code | the paused picture of upper layers, layer sync |
| R3 push | four push transitions in a row | a missing incoming picture (failures of attempts) |
| R4 stab cache | plain → stabilized → film with node cache → plain → both → plain | switches into and out of those clips |
| R5 freeze | two freeze frames inside clips | the cuts into and out of a freeze frame |
| R6 push oracle | a push from a dark to a light plain picture | a push that is wrong the same way in every render |
| A1 camera (`--apv`) | camera clips in a row with film, node cache, a push and an upper layer | the main case, paused picture by appearance |

Verdicts, kept apart:

- **completeness** – exactly one frame for every slot of the range asked for (transitions included), on its slot and in
  rising order; the decoding is strict (an error, a frame without a time or a count that differs fails – never truncated).
- **identity** – every slot shows the exact source frame the FrameGrid rule picks (a port of `SourceSampler` here,
  `segment_slots`), read from the code band in the picture: the export, a pre-rendered chunk and the paused player
  (at every cut: the slot before, on and after it), as opened and right after the composition was rebuilt.
- **timing** – CutTrace while playing from a cold start and right after a rebuild: every new frame of the main layer
  (and every slot of a transition) must reach the GL chain before its presentation time.
- **repeat** – the same range rendered several times must give the same frames; R3 also plays several times.
- **push reference** (R6) – every frame of the push against its reference (the edge at (1 − e)·width, e from the push's
  own curve), in every render: an error repeated in each render fails each.

A runner error fails the run (`runner / execution`). `selftest.py <run output dir>` shows that the checks catch what they
are for: from a run's R1 and R6 files it makes broken ones (the last two frames gone, the last frame the one before, a
straight cut, a black incoming picture, a push the other way, the same wrong push five times) – each must fail, the
unbroken files must pass.

Each run writes `out/<time>-<serial>/`: `report.md` (the verdicts, the details of each failure), `report.json`,
`steps.log` (the timelines' clips, the chunks, the changes made), the timelines as JSON, the CutTrace of each play
and pictures of wrong slots.

The code band: 16 blocks across the middle of the picture, white = 1, least significant bit left; code = clip id × 2048
+ frame index. The rs clips (for stabilization) carry it between two white markers in the middle of the width on a
texture that is never white, so the markers give the zoom.
