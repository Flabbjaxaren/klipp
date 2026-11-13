/*
 * Klipp: part of Klipp's changes to Media3 effect (see KLIPP.md). Apache License 2.0.
 */
package androidx.media3.effect;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayDeque;

/**
 * Klipp: a frame for every slot of the timeline's frame rate, for the effects after it (a transition's move and smear
 * are drawn per frame – from a source slower than the timeline, e.g. 120 fps at 25 % in a 60 fps timeline, a whip moved
 * at the source's 30 frames a second). The {@link Planner} says, per source frame, at which times it is shown: the
 * slots before it ({@code [fill..., own]}, repeating the frame kept before) and its own, or none (dropped); at the end
 * of the item the slots up to its end, repeating the last kept frame.
 *
 * <p>Only for the export and pre-rendering (Transformer): there the end of each item reaches the effects
 * ({@code signalEndOfCurrentInputStream}), and it is passed on only once the trailing slots are out.
 *
 * <p>Frames: each input frame is copied at once and its texture handed back; the frames to show go out one at a time
 * (one output texture: the stage before never holds more than one request, so a new frame never arrives while the last
 * one's slots are still going out), and the next input frame is asked for only when they are all out. The bookkeeping
 * is {@link Queue} (no GL, tested on its own).
 */
@UnstableApi
public final class KlippFrameFill implements GlEffect {

  /**
   * A planner's answer for a frame kept but not shown ({@code [UNSEEN]}): it becomes the frame repeated into the slots
   * before the next one (a pre-rendered part's pre-roll – see Klipp's StartPinPlanner).
   */
  public static final long UNSEEN = Long.MIN_VALUE;

  /** Where each source frame is shown (presentation times, µs). */
  public interface Planner {
    /** The times for the frame at {@code ptsUs}: the slots repeating the kept frame before, then its own; empty: dropped. */
    long[] next(long ptsUs);

    /** At the end of the item: the slots after the last kept frame up to the item's end. */
    long[] end();

    /** A seek or a new item. */
    void reset();
  }

  /** What the queue asks of the GL side. */
  public interface Sink {
    /** Copies the input frame aside as the current one. */
    void copyCurrent();

    /** Sends a frame: the current one or the one kept before, at {@code timeUs}. */
    void emit(boolean current, long timeUs);

    /** The current frame becomes the kept one (its own slot is out). */
    void keepCurrent();

    boolean outputFree();

    void inputProcessed();

    void readyForInput();

    void endOfStream();
  }

  /** The bookkeeping: what goes out when, the requests to the stage before and the end of the item. */
  public static final class Queue {
    private final Planner planner;
    private final Sink sink;
    // (from the current frame?, time, its own slot?)
    private final ArrayDeque<long[]> pending = new ArrayDeque<>();
    private boolean hasKept;
    private boolean endPending;

    public Queue(Planner planner, Sink sink) {
      this.planner = planner;
      this.sink = sink;
    }

    public void input(long ptsUs) {
      long[] times = planner.next(ptsUs);
      if (times.length > 0) {
        sink.copyCurrent();
        for (int i = 0; i < times.length; i++) {
          boolean own = i == times.length - 1;
          // (before the first kept frame there is nothing to repeat: the frame itself)
          pending.add(new long[] {own || !hasKept ? 1 : 0, times[i], own ? 1 : 0});
        }
      }
      sink.inputProcessed();
      drain();
    }

    public void released() {
      drain();
    }

    public void end() {
      for (long t : planner.end()) pending.add(new long[] {0, t, 0});
      endPending = true;
      drain();
    }

    public void flush() {
      pending.clear();
      endPending = false;
      hasKept = false;
      planner.reset();
    }

    private void drain() {
      while (!pending.isEmpty() && (pending.peek()[1] == UNSEEN || sink.outputFree())) {
        long[] p = pending.poll();
        if (p[1] == UNSEEN) {
          sink.keepCurrent();
          hasKept = true;
          continue;
        }
        sink.emit(p[0] == 1 || !hasKept, p[1]);
        if (p[2] == 1) {
          sink.keepCurrent();
          hasKept = true;
        }
      }
      if (!pending.isEmpty()) return;
      if (endPending) {
        endPending = false;
        hasKept = false;
        planner.reset();
        sink.endOfStream();
        return;
      }
      if (sink.outputFree()) sink.readyForInput();
    }
  }

  private final Planner planner;

  public KlippFrameFill(Planner planner) {
    this.planner = planner;
  }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new Program(context, useHdr, planner);
  }

  private static final class Program extends FrameCacheGlShaderProgram implements Sink {
    private final boolean useHdr;
    private final Queue queue;
    // the frame kept before (repeated into slots) and the frame just come in
    @Nullable private GlTextureInfo kept;
    @Nullable private GlTextureInfo current;
    @Nullable private GlObjectsProvider objects;
    @Nullable private GlTextureInfo input;

    Program(Context context, boolean useHdr, Planner planner) throws VideoFrameProcessingException {
      super(context, /* capacity= */ 1, useHdr);
      this.useHdr = useHdr;
      this.queue = new Queue(planner, this);
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      objects = glObjectsProvider;
      input = inputTexture;
      queue.input(presentationTimeUs);
      input = null;
    }

    @Override
    public void releaseOutputFrame(GlTextureInfo outputTexture) {
      if (!outputTexturePool.isUsingTexture(outputTexture)) return;
      outputTexturePool.freeTexture(outputTexture);
      queue.released();
    }

    @Override
    public void signalEndOfCurrentInputStream() {
      queue.end();
    }

    @Override
    public void flush() {
      queue.flush();
      super.flush();
    }

    @Override
    public void release() throws VideoFrameProcessingException {
      super.release();
      try {
        if (kept != null) kept.release();
        if (current != null) current.release();
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e);
      }
    }

    // Sink

    @Override
    public void copyCurrent() {
      GlObjectsProvider o = objects;
      GlTextureInfo in = input;
      if (o == null || in == null) return;
      try {
        GlTextureInfo t = current;
        if (t == null || t.width != in.width || t.height != in.height) {
          if (t != null) t.release();
          int id = GlUtil.createTexture(in.width, in.height, useHdr);
          t = o.createBuffersForTexture(id, in.width, in.height);
        }
        GlUtil.focusFramebufferUsingCurrentContext(t.fboId, t.width, t.height);
        GlUtil.clearFocusedBuffers();
        drawFrame(in.texId, C.TIME_UNSET);
        current = t;
      } catch (VideoFrameProcessingException | GlUtil.GlException e) {
        onError(e);
      }
    }

    @Override
    public void emit(boolean fromCurrent, long timeUs) {
      GlObjectsProvider o = objects;
      GlTextureInfo src = fromCurrent || kept == null ? current : kept;
      if (o == null || src == null) return;
      try {
        outputTexturePool.ensureConfigured(o, src.width, src.height);
        GlTextureInfo out = outputTexturePool.useTexture();
        GlUtil.focusFramebufferUsingCurrentContext(out.fboId, out.width, out.height);
        GlUtil.clearFocusedBuffers();
        drawFrame(src.texId, timeUs);
        getOutputListener().onOutputFrameAvailable(out, timeUs);
      } catch (VideoFrameProcessingException | GlUtil.GlException e) {
        onError(e);
      }
    }

    @Override
    public void keepCurrent() {
      GlTextureInfo t = kept;
      kept = current;
      current = t;
    }

    @Override
    public boolean outputFree() {
      return outputTexturePool.freeTextureCount() > 0;
    }

    @Override
    public void inputProcessed() {
      GlTextureInfo in = input;
      if (in != null) getInputListener().onInputFrameProcessed(in);
    }

    @Override
    public void readyForInput() {
      getInputListener().onReadyToAcceptInputFrame();
    }

    @Override
    public void endOfStream() {
      super.signalEndOfCurrentInputStream();
    }
  }
}
