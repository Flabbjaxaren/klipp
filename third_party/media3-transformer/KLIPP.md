# Media3 transformer 1.11.1 – Klipp's version

The source code of `androidx.media3:media3-transformer:1.11.1` (Apache License 2.0, © The Android Open Source Project,
https://github.com/androidx/media), with a few changes. The module replaces the Maven artifact throughout the app
(`dependencySubstitution` in `app/build.gradle.kts`). On a Media3 upgrade: take the new version's sources (the
`-sources.jar` of the same version) and redo the changes below.

## Changes

- `SequenceRenderersFactory.java`, `SequenceVideoRenderer`: each stream's effects and composition time offset are kept
  by the stream's renderer offset (`klippStreams`) and looked up for the stream whose frames come out of the codec. In
  Media3 they are single fields set when a stream is read and used when its frames come out – a renderer that reads more
  than one stream ahead gave a stream the next one's effects and times. With Media3's video prewarming (its default) a
  renderer never holds two streams; Klipp's live playback with one video renderer across the items (prewarming off,
  items that start "at a keyframe", see `LiveCuts` in the app) reads on into the next items, and an item shorter than
  the codec's queue (a pre-rendered piece of 0.6 s) was graded and timed as the item after it.
- `SequenceRenderersFactory.java`, `SequenceVideoRenderer.onRelease`: the buffering sink is detached before it is
  released. With prewarming off Media3 never detaches it (`deactivateBufferingVideoSink` returns early), so releasing a
  sequence player's renderer – which `CompositionPlayer` does at every new composition – released the composition
  player's one video graph through it (`PlaybackVideoGraphWrapper.InputVideoSink.release`). The next composition's
  renderers then flushed a released frame processor: `RejectedExecutionException`, "Unexpected runtime error" at every
  rebuild on a player (Klipp then made a new player each time). `CompositionPlayerInternal` releases the graph itself
  when the player is released.
- `SequenceRenderersFactory.java`, `SequenceVideoRenderer.getCodecMaxValues`: with prewarming off the decoder's input
  buffers are at least the codec's own maximum for the resolution (`getCodecMaxInputSize`). Media3 sizes them by the
  current file's largest sample (×1.5, capped there), so the next file of the same codec and size with a larger sample
  could not reuse the decoder (`DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED`): with one renderer it was drained and made again
  right before the cut, on the playback thread, and the item's first frame came too late and was dropped (a reversed
  clip on the OnePlus; two camera files on the vivo).
