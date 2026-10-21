/*
 * Klipp: floating point (RGBA16F) through the whole video chain.
 *
 * Media3 picks 16-bit floating-point textures only when the output is HDR; all SDR material (even 10-bit) otherwise goes
 * through 8-bit textures between every stage. With [highPrecision] on, every texture pool in the effect chain (sampling,
 * effects, the compositor, the final stage) gets RGBA16F – if the context is OpenGL ES 3 and can render to half float; otherwise
 * as before. The color math doesn't change (HDR handling is controlled by the color info as before).
 *
 * License: like the rest of the module, Apache 2.0 (the change) – used in Klipp (GPL-3.0).
 */
package androidx.media3.effect;

import android.opengl.GLES20;
import android.opengl.GLES30;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;

/** Klipp's setting for texture precision in Media3's effect chain. */
@UnstableApi
public final class KlippPrecision {
  private KlippPrecision() {}

  /** Floating point (RGBA16F) in all texture pools when the context supports it. Default: on. */
  public static volatile boolean highPrecision = true;

  /** Number of pools that got floating point and 8 bits, respectively (diagnostics). */
  public static volatile int floatPools;
  public static volatile int bytePools;

  /**
   * Frames rendered to an output surface (the screen or the encoder's input) and frames dropped because they were ready too
   * late, respectively – after the whole effect chain (diagnostics: how many frames per second the chain keeps up with).
   */
  public static volatile long framesShown;
  public static volatile long framesLate;

  private static final ThreadLocal<Boolean> CAN_RENDER_FLOAT = new ThreadLocal<>();

  /** Should a pool that asked for [requested] precision get floating point? Called on the GL thread with the context current. */
  static boolean use(boolean requested) {
    if (requested) return true;
    if (!highPrecision) return false;
    Boolean ok = CAN_RENDER_FLOAT.get();
    if (ok == null) {
      ok = probe();
      CAN_RENDER_FLOAT.set(ok);
    }
    return ok;
  }

  /** OpenGL ES 3 and an RGBA16F texture that can be rendered to. */
  private static boolean probe() {
    try {
      if (GlUtil.getContextMajorVersion() < 3) return false;
      int tex = GlUtil.createTexture(4, 4, /* useHighPrecisionColorComponents= */ true);
      int fbo = GlUtil.createFboForTexture(tex);
      int[] prev = new int[1];
      GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prev, 0);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
      int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prev[0]);
      GlUtil.deleteFbo(fbo);
      GlUtil.deleteTexture(tex);
      return status == GLES30.GL_FRAMEBUFFER_COMPLETE;
    } catch (GlUtil.GlException | RuntimeException e) {
      return false;
    }
  }
}
