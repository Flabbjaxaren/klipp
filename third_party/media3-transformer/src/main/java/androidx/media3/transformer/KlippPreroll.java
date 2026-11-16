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
