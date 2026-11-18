/*
 * Copyright 2026 The Klipp contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
