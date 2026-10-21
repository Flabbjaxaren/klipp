/*
 * Klipp: blend modes for the layers in the compositor (multiply, screen, overlay …).
 *
 * Media3's compositor stacks the layers with ordinary transparency. A layer's OverlaySettings that also
 * implements [Settings] is drawn with its blend mode instead: the image so far is copied into a texture of its own and
 * the layer is blended with it in a shader (the same formulas as Klipp's BlendMode).
 *
 * License: like the rest of the module, Apache 2.0 (the change) – used in Klipp (GPL-3.0).
 */
package androidx.media3.effect;

import androidx.media3.common.util.UnstableApi;

/** Klipp's blend modes in the compositor. */
@UnstableApi
public final class KlippBlend {
  private KlippBlend() {}

  /** The layer's blend mode; implemented by OverlaySettings. */
  public interface Settings {
    /** 0 = normal, otherwise one of the modes below. */
    int getBlendMode();
  }

  public static final int NORMAL = 0;
  public static final int MULTIPLY = 1;
  public static final int SCREEN = 2;
  public static final int OVERLAY = 3;
  public static final int SOFT_LIGHT = 4;
  public static final int ADD = 5;
  public static final int DARKEN = 6;
  public static final int LIGHTEN = 7;
  public static final int DIFFERENCE = 8;

  static final String VERTEX =
      "#version 100\n"
          + "attribute vec4 aFramePosition;\n"
          + "uniform mat4 uTransformationMatrix;\n"
          + "uniform mat4 uTexTransformationMatrix;\n"
          + "varying vec2 vTexSamplingCoord;\n"
          + "void main() {\n"
          + "  gl_Position = uTransformationMatrix * aFramePosition;\n"
          + "  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n"
          + "  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;\n"
          + "}\n";

  /** The layer ([uTexSampler]) blended with the image below ([uDst], same position on screen). */
  static final String FRAGMENT =
      "#version 100\n"
          + "precision highp float;\n"
          + "uniform sampler2D uTexSampler;\n"
          + "uniform sampler2D uDst;\n"
          + "uniform vec2 uDstSize;\n"
          + "uniform float uAlphaScale;\n"
          + "uniform float uMode;\n"
          + "varying vec2 vTexSamplingCoord;\n"
          + "float soft(float s, float d) {\n"
          + "  if (s < 0.5) return d - (1.0 - 2.0 * s) * d * (1.0 - d);\n"
          + "  float g = d < 0.25 ? ((16.0 * d - 12.0) * d + 4.0) * d : sqrt(d);\n"
          + "  return d + (2.0 * s - 1.0) * (g - d);\n"
          + "}\n"
          + "float ov(float s, float d) { return d < 0.5 ? 2.0 * s * d : 1.0 - 2.0 * (1.0 - s) * (1.0 - d); }\n"
          + "void main() {\n"
          + "  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);\n"
          + "  vec4 dst = texture2D(uDst, gl_FragCoord.xy / uDstSize);\n"
          + "  vec3 s = clamp(src.rgb, 0.0, 1.0);\n"
          + "  vec3 d = clamp(dst.rgb, 0.0, 1.0);\n"
          + "  vec3 b;\n"
          + "  if (uMode < 1.5) b = s * d;\n"
          + "  else if (uMode < 2.5) b = s + d - s * d;\n"
          + "  else if (uMode < 3.5) b = vec3(ov(s.r, d.r), ov(s.g, d.g), ov(s.b, d.b));\n"
          + "  else if (uMode < 4.5) b = vec3(soft(s.r, d.r), soft(s.g, d.g), soft(s.b, d.b));\n"
          + "  else if (uMode < 5.5) b = min(s + d, 1.0);\n"
          + "  else if (uMode < 6.5) b = min(s, d);\n"
          + "  else if (uMode < 7.5) b = max(s, d);\n"
          + "  else b = abs(s - d);\n"
          + "  float a = src.a * uAlphaScale;\n"
          + "  gl_FragColor = vec4(mix(dst.rgb, b, a), a + dst.a * (1.0 - a));\n"
          + "}\n";
}
