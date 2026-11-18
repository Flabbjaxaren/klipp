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
 * Klipp: an item's pre-roll released from the decoder (see KLIPP.md).
 */
package androidx.media3.transformer;

import androidx.media3.common.util.UnstableApi;

/**
 * Klipp: an item effect saying that the item's frames before a composition time are only its pre-roll (the frames from
 * the keyframe before its start, read because the item starts at a keyframe – see the app's LiveCuts). The sequence's
 * video renderer releases those frames from the decoder without drawing them: through the effects each cost a surface
 * update and a pass of the chain, and a second of 50 fps pre-roll held the cut up by 235 ms on the OnePlus.
 */
@UnstableApi
public interface KlippPreroll {
  /** The composition time (µs) from which the item's frames are shown. */
  long klippPrerollStartUs();
}
