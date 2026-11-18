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
 * Klipp: SDR video sampled as YUV with the file's own matrix, unclamped.
 *
 * Media3 samples SDR video through samplerExternalOES: the driver converts YUV to RGB with a matrix of its choice and
 * clamps to 0..1, so a camera's super-whites (codes above 235/940) and sub-blacks are gone before any effect sees them,
 * and some drivers pick another matrix than the file's tagging. With [KlippPrecision#sdrYuv] on (or a factory's
 * GlObjectsProvider that implements [Choice]), SDR is sampled through EXT_YUV_target like HDR, converted here with the
 * file's matrix and range (BT.601/709/2020, limited/full, 8 or 10 bits) and not clamped.
 *
 */
package androidx.media3.effect;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.util.UnstableApi;

/** Klipp's YUV → RGB conversion for SDR video. */
@UnstableApi
public final class KlippYuv {

  /** A factory's GlObjectsProvider that decides itself (overrides [KlippPrecision#sdrYuv]). */
  public interface Choice {
    /** Whether SDR video is sampled as YUV in this factory's chains. */
    boolean klippSdrYuv();
  }

  private KlippYuv() {}

  /**
   * The conversion for a sampled YUV value s (normalized as the GPU returns it: 8-bit code / 255, 10-bit P010 code·64
   * / 65535): rgb = M · (s − offset). Returns {M as a column-major mat3 (9 values), offset (3 values)}.
   */
  public static float[][] transform(int colorSpace, int colorRange, int bitDepth) {
    double kr;
    double kb;
    if (colorSpace == C.COLOR_SPACE_BT601) {
      kr = 0.299;
      kb = 0.114;
    } else if (colorSpace == C.COLOR_SPACE_BT2020) {
      kr = 0.2627;
      kb = 0.0593;
    } else {
      kr = 0.2126;
      kb = 0.0722;
    }
    double kg = 1 - kr - kb;
    boolean ten = bitDepth > 8;
    // a sample's code value = normalized value · d; k = the code scale relative to 8 bits
    double d = ten ? 65535.0 / 64.0 : 255.0;
    double k = ten ? 4.0 : 1.0;
    double max = ten ? 1023.0 : 255.0;
    double ys;
    double yo;
    double cs;
    double co = 128 * k / d;
    if (colorRange == C.COLOR_RANGE_FULL) {
      ys = d / max;
      yo = 0;
      cs = d / max;
    } else {
      ys = d / (219 * k);
      yo = 16 * k / d;
      cs = d / (224 * k);
    }
    float[] m = {
      (float) ys, (float) ys, (float) ys,
      0f, (float) (-2 * kb * (1 - kb) / kg * cs), (float) (2 * (1 - kb) * cs),
      (float) (2 * (1 - kr) * cs), (float) (-2 * kr * (1 - kr) / kg * cs), 0f
    };
    float[] o = {(float) yo, (float) co, (float) co};
    return new float[][] {m, o};
  }

  /** The conversion for a decoder's input [info] (unknown matrix: BT.709, range: limited, bit depth: 8). */
  public static float[][] transform(ColorInfo info) {
    int depth = info.lumaBitdepth > 0 ? info.lumaBitdepth : 8;
    return transform(info.colorSpace, info.colorRange, depth);
  }

  /** Whether SDR video is sampled as YUV for chains made with [provider]. */
  static boolean use(Object provider) {
    if (provider instanceof Choice) {
      return ((Choice) provider).klippSdrYuv();
    }
    return KlippPrecision.sdrYuv;
  }
}
