# Cinematic RAW Studio Decoder

Decoder and clip-reading sources from [Cinematic RAW Studio Decoder](https://github.com/matthew777777/CinematicRAWStudio-Decoder)
at `cff9a90d7ce76daaa190652f9800495a0ce0905e` (identical to the Cinematic RAW Studio Android app's copy).
The command-line tools and encoder are outside Klipp's import path.

## Modified for Klipp

Each change is marked with "Modified for Klipp" in the file:

- `src/Reader.cpp`: a file of 32–39 bytes, or a forged index table, made `scanIndex` read outside the file. The smallest
  closed file is checked before the unsigned bound is formed, and every indexed chunk must end at or before INDX.
- `src/Decoder.cpp`: `probeFrame` rejects frames over 2^27 samples – a 100-byte payload could ask for gigabytes.
- `tools/common/Clip.cpp`: a lens shading map in the frame JSON is sized from its arrays before it is allocated.
- `tools/common/Clip.cpp`: `frameRaw` copies the frame's bytes before the decoder's threads read them, so a file cut short
  while it is read faults only on the calling thread (caught by Klipp's JNI, `app/src/main/cpp/craw_jni.cpp`).
