/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.effect;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import java.io.IOException;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Wraps a {@link GlProgram} to draw multiple input {@link GlCompositionFrame}s onto one output
 * {@link GlTextureInfo}.
 *
 * <p>All methods must be called on a GL thread, unless otherwise stated.
 */
/* package */ final class DefaultCompositorGlProgram
    implements DefaultGlTextureFrameCompositingProcessor.CompositorGlProgram {

  private static final String TAG = "CompositorGlProgram";

  private final Context context;
  private final OverlayMatrixProvider overlayMatrixProvider;
  private @MonotonicNonNull GlProgram glProgram;
  // Klipp: blend modes (KlippBlend) – the copy of the image so far and the programs that blend
  private @MonotonicNonNull GlProgram klippCopyProgram;
  private @MonotonicNonNull GlProgram klippBlendProgram;
  private int klippScratchTex = -1;
  private int klippScratchFbo = -1;
  private int klippScratchW;
  private int klippScratchH;

  /**
   * Creates an instance.
   *
   * <p>May be called on any thread.
   */
  public DefaultCompositorGlProgram(Context context) {
    this.context = context;
    this.overlayMatrixProvider = new OverlayMatrixProvider();
  }

  /**
   * Draws the {@linkplain GlCompositionFrame GlCompositionFrames} onto the {@linkplain
   * GlTextureInfo outputTexture}.
   */
  // Enhanced for-loops are discouraged in media3.effect due to short-lived allocations.
  @SuppressWarnings("ListReverse")
  @Override
  public void drawFrame(List<GlCompositionFrame> framesToComposite, GlTextureInfo outputTexture)
      throws GlUtil.GlException, VideoFrameProcessingException {
    ensureConfigured();
    GlUtil.focusFramebufferUsingCurrentContext(
        outputTexture.fboId, outputTexture.width, outputTexture.height);
    overlayMatrixProvider.configure(new Size(outputTexture.width, outputTexture.height));
    GlUtil.clearFocusedBuffers();

    GlProgram glProgram = checkNotNull(this.glProgram);
    glProgram.use();

    // Setup for blending.
    GLES20.glEnable(GLES20.GL_BLEND);
    // Similar to:
    // dst.rgb = src.rgb * src.a + dst.rgb * (1 - src.a)
    // dst.a   = src.a           + dst.a   * (1 - src.a)
    GLES20.glBlendFuncSeparate(
        /* srcRGB= */ GLES20.GL_SRC_ALPHA,
        /* dstRGB= */ GLES20.GL_ONE_MINUS_SRC_ALPHA,
        /* srcAlpha= */ GLES20.GL_ONE,
        /* dstAlpha= */ GLES20.GL_ONE_MINUS_SRC_ALPHA);
    GlUtil.checkGlError();

    // Draw textures from back to front.
    for (int i = framesToComposite.size() - 1; i >= 0; i--) {
      GlCompositionFrame frame = framesToComposite.get(i);
      // Klipp: a layer with a blend mode (not the bottom one – it has nothing to blend with)
      int mode =
          frame.overlaySettings instanceof KlippBlend.Settings
              ? ((KlippBlend.Settings) frame.overlaySettings).getBlendMode()
              : KlippBlend.NORMAL;
      if (mode != KlippBlend.NORMAL && i < framesToComposite.size() - 1) {
        klippBlendWithMode(frame, mode, outputTexture);
        glProgram.use();
      } else {
        blendOntoFocusedTexture(frame);
      }
    }

    GLES20.glDisable(GLES20.GL_BLEND);
    GlUtil.checkGlError();
  }

  @Override
  public void release() {
    try {
      if (glProgram != null) {
        glProgram.delete();
      }
      if (klippCopyProgram != null) {
        klippCopyProgram.delete();
      }
      if (klippBlendProgram != null) {
        klippBlendProgram.delete();
      }
      if (klippScratchFbo != -1) {
        GlUtil.deleteFbo(klippScratchFbo);
      }
      if (klippScratchTex != -1) {
        GlUtil.deleteTexture(klippScratchTex);
      }
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Error releasing GL Program", e);
    }
  }

  private void ensureConfigured() throws VideoFrameProcessingException, GlUtil.GlException {
    if (glProgram != null) {
      return;
    }
    try {
      glProgram =
          new GlProgram(
              context,
              /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es2,
              /* fragmentShaderResId= */ R.raw.fragment_shader_alpha_scale_es2);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    } catch (IOException e) {
      throw new VideoFrameProcessingException(e);
    }
  }

  /**
   * Klipp: the layer blended with the image so far using blend mode [mode]: the image is copied into a texture of its own, then
   * the layer is drawn (without GL blending) with a shader that reads both.
   */
  private void klippBlendWithMode(GlCompositionFrame frame, int mode, GlTextureInfo outputTexture)
      throws GlUtil.GlException {
    int w = outputTexture.width;
    int h = outputTexture.height;
    if (klippCopyProgram == null) {
      try {
        klippCopyProgram =
            new GlProgram(
                context,
                /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es2,
                /* fragmentShaderResId= */ R.raw.fragment_shader_copy_es2);
      } catch (IOException e) {
        throw new GlUtil.GlException(e.getMessage());
      }
      klippCopyProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      klippBlendProgram = new GlProgram(KlippBlend.VERTEX, KlippBlend.FRAGMENT);
      klippBlendProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    }
    if (klippScratchTex == -1 || klippScratchW != w || klippScratchH != h) {
      if (klippScratchFbo != -1) {
        GlUtil.deleteFbo(klippScratchFbo);
      }
      if (klippScratchTex != -1) {
        GlUtil.deleteTexture(klippScratchTex);
      }
      klippScratchTex = GlUtil.createTexture(w, h, KlippPrecision.use(false));
      klippScratchFbo = GlUtil.createFboForTexture(klippScratchTex);
      klippScratchW = w;
      klippScratchH = h;
    }
    GLES20.glDisable(GLES20.GL_BLEND);
    // 1. the image so far
    GlUtil.focusFramebufferUsingCurrentContext(klippScratchFbo, w, h);
    GlProgram copy = checkNotNull(klippCopyProgram);
    copy.use();
    copy.setSamplerTexIdUniform("uTexSampler", outputTexture.texId, /* texUnitIndex= */ 0);
    copy.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    copy.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    copy.bindAttributesAndUniforms();
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    // 2. the layer blended with the copy
    GlUtil.focusFramebufferUsingCurrentContext(outputTexture.fboId, w, h);
    GlProgram blend = checkNotNull(klippBlendProgram);
    blend.use();
    GlTextureInfo inputTexture = frame.glTextureInfo;
    blend.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
    blend.setSamplerTexIdUniform("uDst", klippScratchTex, /* texUnitIndex= */ 1);
    float[] transformationMatrix = frame.transformationMatrix;
    if (transformationMatrix == null) {
      transformationMatrix =
          overlayMatrixProvider.getTransformationMatrix(
              /* overlaySize= */ new Size(inputTexture.width, inputTexture.height),
              frame.overlaySettings);
    }
    blend.setFloatsUniform("uTransformationMatrix", transformationMatrix);
    blend.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    blend.setFloatsUniform("uDstSize", new float[] {w, h});
    blend.setFloatUniform("uAlphaScale", frame.overlaySettings.getAlphaScale());
    blend.setFloatUniform("uMode", mode);
    blend.bindAttributesAndUniforms();
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    GlUtil.checkGlError();
    GLES20.glEnable(GLES20.GL_BLEND);
  }

  private void blendOntoFocusedTexture(GlCompositionFrame inputFrameInfo)
      throws GlUtil.GlException {
    GlProgram glProgram = checkNotNull(this.glProgram);
    GlTextureInfo inputTexture = inputFrameInfo.glTextureInfo;
    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
    float[] transformationMatrix = inputFrameInfo.transformationMatrix;
    if (transformationMatrix == null) {
      transformationMatrix =
          overlayMatrixProvider.getTransformationMatrix(
              /* overlaySize= */ new Size(inputTexture.width, inputTexture.height),
              inputFrameInfo.overlaySettings);
    }
    glProgram.setFloatsUniform("uTransformationMatrix", transformationMatrix);
    glProgram.setFloatUniform("uAlphaScale", inputFrameInfo.overlaySettings.getAlphaScale());
    glProgram.bindAttributesAndUniforms();

    // The four-vertex triangle strip forms a quad.
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    GlUtil.checkGlError();
  }
}
