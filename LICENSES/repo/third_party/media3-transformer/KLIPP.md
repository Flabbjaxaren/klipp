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
- `SequenceRenderersFactory.java`, `SequenceVideoRenderer.klippIsLaterPreroll`: a later item's pre-roll frame that comes
  out of the decoder before the item before's last frames is released undrawn. Media3 assigns a frame to the stream
  whose time range it falls in, and a next item's pre-roll times lie below the item before's last frame: with an open
  GOP (HEVC's CRA) the next item's keyframe came out first, counted as the item before's, and was shown on its last
  slot in playback and paused. The frames are known by the times their samples were queued with, per stream – only
  when the time was queued as a later stream's pre-roll and never for the stream coming out now.
- `SequenceRenderersFactory.java`, `SequenceVideoRenderer.processOutputBuffer`, new `KlippPreroll.java`: an item whose
  effects include a `KlippPreroll` (the app's `PrerollDrop`, live cuts with one renderer) has its frames before that
  composition time released from the decoder undrawn (`skipOutputBuffer`). Through the effects each cost a surface update
  and a pass of the chain before the effect dropped it: a second of 50 fps pre-roll held a cut up by 235 ms (OnePlus).
- `SequenceRenderersFactory.java`, `SequenceVideoRenderer.canReuseCodec`: a change of colour information alone between
  two SDR streams of the same bit depth keeps the decoder (Media3 discards it: a camera file without transfer and
  primaries next to a proxy or a node cache with BT.709 gave a new decoder, 95–126 ms on the OnePlus, at every such
  cut). Not with HDR on either side, the decoder's tone mapping, or another bit depth.
- `SequenceRenderersFactory.java`, `SequenceVideoRenderer.getCodecMaxValues`: with prewarming off the decoder's input
  buffers are at least the codec's own maximum for the resolution (`getCodecMaxInputSize`). Media3 sizes them by the
  current file's largest sample (×1.5, capped there), so the next file of the same codec and size with a larger sample
  could not reuse the decoder (`DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED`): with one renderer it was drained and made again
  right before the cut, on the playback thread, and the item's first frame came too late and was dropped (a reversed
  clip on the OnePlus; two camera files on the vivo).
- `AudioGraph.java`, `PlaybackAudioGraphWrapper.java`, new `KlippAudio.java`: the composition player's mixer runs at a
  sample rate the app sets (`KlippAudio.playbackMixSampleRate`, the project's), every input converted to it before the
  mixer. Media3 took the first input's rate, and a gap's silence (`SilenceMediaSource`) is always 44.1 kHz: whenever a
  track began with one, every clip was resampled to 44.1 kHz and the output back to the phone's 48 kHz, and the fast
  audio path was refused (measured in the audio flinger: the track at 44100 Hz before, at 48000 Hz and a fast track
  after). The export doesn't use it (its own conversion after the mixer).
- `CompositionPlayer.java`, new `KlippSilenceMediaSource.java`: the silence of gaps and of video items (the merged
  silence track) is made at the mix's rate when `KlippAudio.playbackMixSampleRate` is set; Media3's
  `SilenceMediaSource` is always 44.1 kHz and was resampled to the mix at every item. A copy of `SilenceMediaSource`
  (Apache 2.0) with the sample rate as a parameter.
- `TransformerUtil.java` (`getValidColor`, new `klippUntaggedSdr`), `VideoSampleExporter.java`: a file that gives its
  matrix and range but no transfer (the Xiaomi 17 Ultra's log video, stock MiLog or a camera mod's S-Log3: BT.2020,
  full range, transfer unspecified) is SDR with its own matrix and range instead of BT.709 limited – sampled as BT.709
  limited, full-range BT.2020 YUV came out with contrast and saturation ×1.17 (measured against the source). Its
  output is BT.709 limited like other SDR.
