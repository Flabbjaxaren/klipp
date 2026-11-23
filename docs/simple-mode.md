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

## The layout (from the mockups)

Every control is an advanced-mode function, reached in fewer steps:

| Where | Control | The function it uses |
|---|---|---|
| Top | The project's name, length and number of clips; **Save video** | The export dialog (its simple form: preset and size) |
| Bottom, nothing selected | **Add** | The media import |
| | **Sounds** | The sound library (packs, text to speech, files from the phone), with two switches under it: *Lower the sounds when someone speaks* (the audio panel's ducking, on the tracks that hold only added sounds, against the speech) and *Fade out at the end* (the sounds still playing when the picture ends end with it and fade out over 2 s – an audio fade on their out edge, which doesn't block trimming or moving them). Each is one undo step |
| | **Text** | The title sheet, with *Captions from what is said* (the captions sheet) at the top |
| | **Filter** | The simple colour page (look, exposure, contrast, warmth, saturation – the same nodes as advanced colour), in a sheet |
| | **Format** | The project's settings (format and size) |
| Bottom, a clip selected | **Split here, Speed, Sound, Filter, Zoom, Delete** | Razor at the playhead, the speed steps, the clip's volume, the filter sheet, the picture's crop and position, ripple delete; *Advanced* when the clip holds advanced work |
| The strip | A row per track under the clips, the **+** tile, a first-time tip | The other tracks' clips (selected, moved and trimmed as clips), the media import |

The pages (Edit, Colour, Audio) are reached through these buttons instead of the workspace pills.

## The clip strip (first stage)

In simple mode the edit page shows a clip strip (`SimpleStrip.kt`) in place of the track timeline:

- **The film as one row.** The strip shows the storyline, the clips on V1, with their frames. Gaps in the storyline
  show as dashed spaces.
- **Rows for the other tracks.** Every track with something on it is a row of its own, in the advanced timeline's
  order: the video tracks (overlays, B-roll, titles) over the storyline, the highest on top – what lies higher covers
  what is under it in the picture – and A1, A2 … (sound and music that isn't a video clip's own; that is part of its
  clip) and the captions under it. Empty tracks are not shown; they stay in the project. A clip on a row is selected,
  moved and trimmed like a clip in the storyline. With more rows than there is room for, the strip scrolls up and
  down; it opens with the storyline in view.
- **What hangs on what.** Overlays and titles hang on the storyline clip under them (the magnetic timeline's connected
  clips) and go with it: a pin and a thin line show where. While a layer is dragged, the clip it will hang on lights
  up. Music and sound moved, trimmed or added in simple mode stay where they are in the film instead (they are kept
  disconnected, as part of that edit), so a tune doesn't jump when a clip earlier in the film is shortened.
- **The track's own state from advanced mode** shows as a mark at the row's start: locked, hidden, muted, track
  effects. A locked row's clips don't change; touching the lock explains and offers advanced mode.
- **Selecting.** Tapping a clip selects it (and brings the playhead into it), and the bar below acts on it. A tap
  beside the clips deselects; in the ruler and the clips' row it also moves the playhead there. The strip takes
  all the room down to the bar, so a drag anywhere under the clips scrolls it.
- **The playhead.** A white line over the strip, with the time and the film's length on top. Drag along the time
  ruler above the clips to move it through the film.
- **Centred playhead** (⋯ menu, as in advanced mode). The line stays in the middle and the strip moves under it, so
  scrolling the strip moves through the film.
- **Zoom.** Pinch with two fingers, down to the whole film in view however long it is (the overview) and up to
  400 dp a second. The strip starts at a size where clips can be taken hold of; *Show the whole film* returns to
  the overview in one tap. The time under the fingers stays put, or the playhead does when it is centred.
- **Moving a clip.** Hold a clip and drag it; held near the strip's edge, the strip scrolls that way. Dragged up
  onto a row, a storyline clip becomes a layer over the film (the storyline closes up – no gap is left; the magnetic
  `MagTimeline.move` to another lane); a video clip dragged down from a row goes into the storyline at the nearest edit
  point (what follows makes room). While a video clip or a title is dragged, the strip's top (over the ruler) is a place
  to drop it as a new layer over all the others – or just over the storyline when there are no layers yet; nothing
  moves to make room for it. A clip goes only onto a row of its kind (video and titles on video tracks, sound on audio tracks); a
  layer dropped onto a clip on its row stacks onto the next free track. Each move is one undo step.
- **The magnet.** A clip moved onto a row, or along one, snaps its start or end to the edges of the clips on every
  row and to the playhead when it comes within 12 dp; a trimmed edge snaps the same way (not to the playhead, which
  follows the edge). A lime line through the rows shows the edge it caught, with a tick.
- **Delete** (the bar) takes the selected clip out at once: a storyline clip closes up, a layer just goes. (The
  timeline's removal animation, which finishes a delete in advanced mode, is not part of the strip.) A lime line shows where it will go in, and it is dropped at the
  storyline's edit point nearest to where its start is. This is the magnetic timeline's own reorder
  (`MagTimeline.move`): the gap it leaves closes, nothing is overwritten, its linked sound and connected clips go
  with it, and it is one undo step. A clip joined to a neighbour by a transition is not moved; it explains why and
  offers advanced mode. Screen readers get *Move earlier / later* on each clip.
- **Advanced.** A button that is always visible switches to advanced mode, the same switch as in the ⋯ menu and
  Settings.
- **Undo and redo** are the editor's own buttons, always shown.
- **The transport** under the picture is Start, Play and End only. The frame steps, edit jumps and timecode stay in
  advanced mode, and so does the transcript view.

### Trimming

The selected clip has a lime handle on each edge, 48 dp to touch.

- **While dragging,** the line over the strip shows the result ("0:06 left (was 0:11)"). The part the drag cuts off is
  striped. Material brought back shows as a pale strip beside the clip. The monitor shows the frame at the new edge
  when there is one to show – with the centred playhead it moves to the edit when the finger lifts instead (moving
  it during the drag moved the strip under the finger).
- **When the finger lifts,** the drag becomes one trim, the magnetic timeline's `MagTimeline.trim` through
  `EditorState.magTrim`. The engine carries it out as one group, so a whole drag is one undo step however many moves
  it had.
- **Trimmed-off material can be brought back.** Drag the handle outward, or use *Bring back start / end*, which trims
  the edge out to the file's first or last frame (one trim, one undo step). Freeze frames and reversed clips only
  shorten in simple mode; titles and adjustment clips can be made longer at their end (they have no file to run out
  of).
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

- the storyline, and a row per track (a video clip's own sound in its clip, empty tracks left out);
- layers moving along their row, into the storyline and out of it; music staying in place; titles made longer;
- on screen: video rows over the storyline and sound rows under it, a clip dragged up onto a row (and not onto a
  sound row), a layer dragged along its row, a locked row's mark, more rows than room scrolling;
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
