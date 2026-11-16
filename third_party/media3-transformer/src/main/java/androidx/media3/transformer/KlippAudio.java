/*
 * Klipp: the composition player's mix at the project's sample rate (see KLIPP.md).
 */
package androidx.media3.transformer;

import androidx.media3.common.Format;

/** Klipp: settings for the composition player's audio (see {@code KLIPP.md}). */
public final class KlippAudio {

  private KlippAudio() {}

  /**
   * The sample rate the composition player mixes at (Hz), {@link Format#NO_VALUE} for Media3's own choice: the first input
   * that registers, which is a gap's silence (always 44.1 kHz) whenever a track begins with one. Every input is converted
   * to it before the mixer. Read when a composition's audio graph is made.
   */
  public static volatile int playbackMixSampleRate = Format.NO_VALUE;
}
