/*
 * Klipp: GPU priority for video processing (see KLIPP.md).
 */
package androidx.media3.effect;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.annotation.Nullable;
import androidx.media3.common.util.GlUtil;

/**
 * The video processing contexts (playback, the layers' players, export) ask for low GPU priority with
 * EGL_IMG_context_priority, so the app's own drawing – the timeline, the playhead, the panels – isn't queued
 * behind a heavy effect chain (spektrafilm's blurs take several milliseconds per pass). Video still gets the GPU
 * whenever the UI doesn't need it. Without the extension (or if the driver refuses) the context is created as
 * Media3 does.
 */
public final class KlippPriority {
  private KlippPriority() {}

  private static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100;
  private static final int EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;

  /** Low priority for the video contexts. Default: on. */
  public static volatile boolean lowPriority = true;

  /** The priority the driver granted the last video context ("low", "medium", "high", "default"), for diagnostics. */
  @Nullable public static volatile String granted;

  static EGLContext createEglContext(
      EGLContext sharedContext, EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes)
      throws GlUtil.GlException {
    if (lowPriority && supported(eglDisplay)) {
      EGLConfig[] configs = new EGLConfig[1];
      int[] num = new int[1];
      if (EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, num, 0) && num[0] > 0) {
        int[] attributes = {
          EGL14.EGL_CONTEXT_CLIENT_VERSION, openGlVersion,
          EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG,
          EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(eglDisplay, configs[0], sharedContext, attributes, 0);
        if (context != null && !context.equals(EGL14.EGL_NO_CONTEXT)) {
          int[] level = new int[1];
          if (EGL14.eglQueryContext(eglDisplay, context, EGL_CONTEXT_PRIORITY_LEVEL_IMG, level, 0)) {
            granted = level[0] == 0x3101 ? "high" : level[0] == 0x3102 ? "medium" : level[0] == 0x3103 ? "low" : "?";
          }
          return context;
        }
        // (a refused attribute leaves an EGL error behind – cleared before Media3's own attempt checks for errors)
        EGL14.eglGetError();
      }
    }
    granted = "default";
    return GlUtil.createEglContext(sharedContext, eglDisplay, openGlVersion, configAttributes);
  }

  private static boolean supported(EGLDisplay display) {
    @Nullable String extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
    return extensions != null && extensions.contains("EGL_IMG_context_priority");
  }
}
