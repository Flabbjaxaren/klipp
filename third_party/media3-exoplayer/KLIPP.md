# Media3 ExoPlayer 1.11.1 – Klipp's version

The source code of `androidx.media3:media3-exoplayer:1.11.1` (Apache License 2.0, © The Android Open Source Project,
https://github.com/androidx/media), with a few changes. The module replaces the Maven artifact throughout the app
(`dependencySubstitution` in `app/build.gradle.kts` and `third_party/media3-transformer/build.gradle.kts`). On a Media3
upgrade: take the new version's sources (the `-sources.jar` of the same version), its AAR's `res/`, manifest and
`proguard.txt` (here `consumer-rules.pro`), and redo the changes below.

## Changes

- `source/SampleQueue.java`, `updateReadEndTimeState`: a keyframe after a clip's end ends the read only when the track
  has no reordering. Media3 ended it at the first keyframe after the end, assuming a closed GOP; in an open GOP (HEVC's
  CRA pictures, x265's default) the pictures shown just before the keyframe are decoded after it, so a clip ending
  there lost its last frames – in the export, the pre-rendering and playback alike (the regression run's R1 with HEVC
  clips: frames 58 and 59 of a 30 fps clip ending at 1.97 s, the keyframe at 2.0 s).

- `PlaybackVideoGraphWrapper.java` (`getAdjustedInputColorInfo`, `registerInput`): the same rule as the transformer's
  `TransformerUtil.klippUntaggedSdr` for playback – a file with matrix and range but no transfer keeps them (SDR), and
  SDR in BT.2020 is shown as BT.709 limited.

The resources (`res/`) and the manifest come from the AAR file of the same version.
