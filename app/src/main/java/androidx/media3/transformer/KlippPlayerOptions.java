package androidx.media3.transformer;

/**
 * Klipp: options of {@link CompositionPlayer.Builder} that Media3 keeps to its own package.
 *
 * <p>Video prewarming (on by default): the next item of a sequence is decoded by a second renderer whose frames wait
 * outside the effects until the playback position reaches the item – only then is the effect chain told about the new
 * stream. The chain needs its lead (its rebuild, the item's first frames through the grade), so with effects the first
 * frames of every item come late: the picture stands 85–160 ms at each unrendered cut on the vivo. Without it the one
 * renderer runs on across the item boundary and the next item's first frame came on time (measured) – but the effect
 * chain was never configured for the new stream and the picture stood from the second frame on. Kept on until that is
 * solved; this switch is for the experiments.
 */
public final class KlippPlayerOptions {
  private KlippPlayerOptions() {}

  public static CompositionPlayer.Builder videoPrewarming(CompositionPlayer.Builder builder, boolean enabled) {
    return builder.setVideoPrewarmingEnabled(enabled);
  }

  /**
   * Diagnostics: the players of the sequences (one each). Media3 keeps them to itself, so they are found by their type
   * (the names don't survive the release build). Empty until a composition is set; call on the player's thread.
   */
  public static java.util.List<androidx.media3.exoplayer.ExoPlayer> sequencePlayers(CompositionPlayer player) {
    java.util.ArrayList<androidx.media3.exoplayer.ExoPlayer> out = new java.util.ArrayList<>();
    try {
      for (java.lang.reflect.Field f : CompositionPlayer.class.getDeclaredFields()) {
        if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
        f.setAccessible(true);
        Object v = f.get(player);
        if (!(v instanceof java.util.List)) continue;
        for (Object h : (java.util.List<?>) v) {
          if (h == null) continue;
          for (java.lang.reflect.Field g : h.getClass().getDeclaredFields()) {
            if (!androidx.media3.exoplayer.ExoPlayer.class.isAssignableFrom(g.getType())) continue;
            g.setAccessible(true);
            Object q = g.get(h);
            if (q instanceof androidx.media3.exoplayer.ExoPlayer) out.add((androidx.media3.exoplayer.ExoPlayer) q);
          }
        }
      }
    } catch (Throwable ignored) {
      // diagnostics only
    }
    return out;
  }
}
