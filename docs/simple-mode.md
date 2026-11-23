# Simple mode

Simple mode is a second face on the same editor. Both modes use the same project, the same edits and the same
undo history.

- Switching modes changes only which controls are shown. Nothing in the project changes: no clips, tracks, effects,
  grades or settings, and the switch is not an undo step.
- There is no separate copy of the project for simple mode.
- Work done in advanced mode that simple mode has no control for is kept and still plays and exports. It shows as a
  mark on the clip, and the dock's *Advanced* row offers to turn it off, remove it or open it in advanced mode
  (see `Simple.kt`).
- Simple mode always edits on the magnetic timeline (`Magnetic.kt`). The advanced mode's own magnetic setting is not
  changed.

The mode is `EditorState.simple`. It is switched with `EditorState.switchMode()` and stored in
`AppPrefs.simpleMode`.

## The clip strip (first stage)

In simple mode the edit page shows a clip strip (`SimpleStrip.kt`) in place of the track timeline:

- **The film as one row.** The strip shows the storyline, the clips on V1, with their frames. Gaps in the storyline
  show as dashed spaces.
- **Clips on other tracks** (titles, B-roll, sound, music) are not shown. A line under the strip counts them, because
  they follow their clips.
- **Selecting.** Tapping a clip selects it, and the dock below acts on it as in advanced mode. Tapping beside the
  clips moves the playhead there.
- **The playhead.** A white line over the strip, with the time and the film's length on top.
- **Advanced.** A button that is always visible switches to advanced mode, the same switch as in the ⋯ menu and
  Settings.
- **Undo and redo** are the editor's own buttons, always shown.

### Trimming

The selected clip has a lime handle on each edge, 48 dp to touch.

- **While dragging,** the line over the strip shows the result ("0:06 left (was 0:11)"). The part the drag cuts off is
  striped. Material brought back shows as a pale strip beside the clip. The monitor shows the frame at the new edge
  when there is one to show.
- **When the finger lifts,** the drag becomes one trim, the magnetic timeline's `MagTimeline.trim` through
  `EditorState.magTrim`. The engine carries it out as one group, so a whole drag is one undo step however many moves
  it had.
- **Trimmed-off material can be brought back.** Drag the handle outward, or use *Bring back start / end*, which trims
  the edge out to the file's first or last frame (one trim, one undo step). Freeze frames, reversed clips, titles and
  adjustment clips only shorten in simple mode.
- **Screen readers.** Each handle offers *Shorten / Lengthen by half a second* as accessibility actions. Each one is
  a trim and an undo step.

### What a trim changes

A trim of a storyline clip follows the magnetic timeline's rules (`Magnetic.kt`):

| | What happens |
|---|---|
| Clips after it on the storyline | They ripple: they move up or out by the trim, so no gap opens and nothing is overwritten. |
| Its linked sound (its own audio, a file's further audio streams) | Trimmed with it. |
| Clips on other tracks connected to it (titles, B-roll, music starting on it) | They stay on their frame of the trimmed clip. Those connected to later clips move with those clips. |
| Captions | They hang on the storyline like connected clips and stay with their words. |
| Transitions | Not changed. An edge with a transition can't be trimmed in simple mode (see below). |
| The clip's own settings (grade, effects, keyframes, speed, volume) | Kept. The clip is the same clip; only its edges move. |

### What simple mode leaves alone

When an edit can't be made safely in simple mode, the timeline stays exactly as it is. The strip says why and offers
advanced mode, and nothing is simplified automatically.

- **An edge with a transition.** Trimming there would change the transition, so the handle shows a lock. Touching it
  explains and offers advanced mode.
- **A clip on a locked track.** Nothing on a locked track changes, as in advanced mode.
- **A film that is only on other tracks** (nothing on V1). The strip can't show it, so it says so and offers advanced
  mode.

## Tests

`SimpleStripTest` covers:

- the storyline, and the count of clips on other tracks;
- the limits of what a trim can bring back;
- trimming the end (ripple, connected clips follow, one undo step, undo and redo);
- cutting the start;
- bringing back trimmed-off material;
- edges with a transition and locked tracks;
- switching modes repeatedly: clips, tracks, transitions, magnetic side data, clip settings, grades and the undo
  history unchanged, and a trim in simple mode keeping the clip's own settings;
- on screen: the strip in place of the timeline, the visible undo, redo and advanced buttons, a drag being one trim
  and one undo step, the screen reader action, the blocked handle's dialog, and a film on other tracks.

`SimpleModeTest` covers the mode setting, the dock, the badges, colour and export.

## Later stages

Music, text and filter panels follow once the strip is verified with real projects. The mockups are on the design
canvas.
