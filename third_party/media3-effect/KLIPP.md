# Media3 effect 1.11.1 – Klipp's version

The source code of `androidx.media3:media3-effect:1.11.1` (Apache License 2.0, © The Android Open Source Project,
https://github.com/androidx/media), with a few changes. The module replaces the Maven artifact throughout the app
(`dependencySubstitution` in `app/build.gradle.kts`), so that Transformer and ExoPlayer use it too.

## Changes

- `KlippPrecision.java` (new): the "floating point through the whole chain" setting and a check that the context is
  OpenGL ES 3 and can render to RGBA16F.
- `TexturePool.java`: every texture pool (sampling, effects, the compositor, the final stage) gets RGBA16F when
  `KlippPrecision.highPrecision` is on – otherwise Media3 picks floating point only for HDR output. The color math (HDR,
  tone mapping) is unchanged.
- `MultipleInputVideoGraph.java`: the factory's `GlObjectsProvider` is kept behind the shared context (Klipp's
  10-bit encoder surfaces in multi-layer exports too).
- `DefaultVideoFrameProcessor.java`: `Factory.klippGlObjectsProvider()` for the change above.
- `FinalShaderProgramWrapper.java`: counts rendered and late-dropped frames (`KlippPrecision.framesShown`,
  `framesLate`) for diagnostics.
- `KlippBlend.java` (new): blend modes for the layers (multiply, screen, overlay, soft light, add, darken,
  lighten, difference). A layer's `OverlaySettings` that implements `KlippBlend.Settings` is blended with its mode.
- `DefaultCompositorGlProgram.java`: a layer with a blend mode is drawn by copying the image so far into a
  texture of its own and blending the layer with it in `KlippBlend`'s shader (`klippBlendWithMode`); other layers as before.

- `KlippPriority.java` (new) and `DefaultGlObjectsProvider.java`: the video processing contexts are created with low
  GPU priority (EGL_IMG_context_priority) when the driver offers it, so the app's UI isn't queued behind heavy effects.

The resources (`res/raw`, shaders) and the manifest come from the AAR file of the same version.

To update: unpack `media3-effect-<version>-sources.jar` and the AAR's `res/` here and redo the changes above.
