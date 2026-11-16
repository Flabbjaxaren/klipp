/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.common.util.Util.durationUsToSampleCount;
import static androidx.media3.common.util.Util.sampleCountToDurationUs;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.TransferListener;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import java.util.ArrayList;

/**
 * Klipp: a copy of Media3's {@code SilenceMediaSource} (1.11.1) that makes its silence at a given sample rate. Media3's is
 * always 44.1 kHz; with the composition player's mix at the project's rate (see {@link KlippAudio}) every gap's and every
 * video item's silence was resampled to it, and at each item edge of the video sequence the player went to buffering and
 * its position jumped about half a second (the audio sink's buffer).
 */
@UnstableApi
/* package */ final class KlippSilenceMediaSource extends BaseMediaSource {

  /** The media id used by any media item of silence media sources. */
  public static final String MEDIA_ID = "SilenceMediaSource";

  private static final @C.PcmEncoding int PCM_ENCODING = C.ENCODING_PCM_16BIT;
  private static final int CHANNEL_COUNT = 2;
  private static final MediaItem MEDIA_ITEM =
      new MediaItem.Builder()
          .setMediaId(MEDIA_ID)
          .setUri(Uri.EMPTY)
          .setMimeType(MimeTypes.AUDIO_RAW)
          .build();
  private static final byte[] SILENCE_SAMPLE =
      new byte[Util.getPcmFrameSize(PCM_ENCODING, CHANNEL_COUNT) * 1024];

  private final long durationUs;

  @GuardedBy("this")
  private MediaItem mediaItem;

  /**
   * Creates a new media source providing silent audio of the given duration.
   *
   * @param durationUs The duration of silent audio to output, in microseconds.
   */
  private final Format format;
  private final int sampleRateHz;

  /**
   * Creates an instance.
   *
   * @param durationUs The duration of silence, in microseconds.
   * @param sampleRateHz The silence's sample rate.
   */
  public KlippSilenceMediaSource(long durationUs, int sampleRateHz) {
    checkArgument(durationUs >= 0);
    checkArgument(sampleRateHz > 0);
    this.durationUs = durationUs;
    this.sampleRateHz = sampleRateHz;
    this.mediaItem = MEDIA_ITEM;
    this.format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(CHANNEL_COUNT)
            .setSampleRate(sampleRateHz)
            .setPcmEncoding(PCM_ENCODING)
            .build();
  }

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    refreshSourceInfo(
        new SinglePeriodTimeline(
            durationUs,
            /* isSeekable= */ true,
            /* isDynamic= */ false,
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            getMediaItem()));
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() {}

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    return new SilenceMediaPeriod(durationUs, format, sampleRateHz);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {}

  @Override
  public synchronized MediaItem getMediaItem() {
    return mediaItem;
  }

  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return true;
  }

  @Override
  public synchronized void updateMediaItem(MediaItem mediaItem) {
    this.mediaItem = mediaItem;
  }

  @Override
  protected void releaseSourceInternal() {}

  private static final class SilenceMediaPeriod implements MediaPeriod {

    private final TrackGroupArray tracks;
    private final Format format;
    private final int sampleRateHz;
    private final long durationUs;
    private final ArrayList<SampleStream> sampleStreams;

    public SilenceMediaPeriod(long durationUs, Format format, int sampleRateHz) {
      this.durationUs = durationUs;
      this.format = format;
      this.sampleRateHz = sampleRateHz;
      tracks = new TrackGroupArray(new TrackGroup(format));
      sampleStreams = new ArrayList<>();
    }

    @Override
    public void prepare(Callback callback, long positionUs) {
      callback.onPrepared(/* mediaPeriod= */ this);
    }

    @Override
    public void maybeThrowPrepareError() {}

    @Override
    public TrackGroupArray getTrackGroups() {
      return tracks;
    }

    @Override
    public long selectTracks(
        @NullableType ExoTrackSelection[] selections,
        boolean[] mayRetainStreamFlags,
        @NullableType SampleStream[] streams,
        boolean[] streamResetFlags,
        long positionUs) {
      positionUs = constrainSeekPosition(positionUs);
      for (int i = 0; i < selections.length; i++) {
        if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
          sampleStreams.remove(streams[i]);
          streams[i] = null;
        }
        if (streams[i] == null && selections[i] != null) {
          SilenceSampleStream stream = new SilenceSampleStream(durationUs, format, sampleRateHz);
          stream.seekTo(positionUs);
          sampleStreams.add(stream);
          streams[i] = stream;
          streamResetFlags[i] = true;
        }
      }
      return positionUs;
    }

    @Override
    public void discardBuffer(long positionUs, boolean toKeyframe) {}

    @Override
    public long readDiscontinuity() {
      return C.TIME_UNSET;
    }

    @Override
    public long seekToUs(long positionUs) {
      positionUs = constrainSeekPosition(positionUs);
      for (int i = 0; i < sampleStreams.size(); i++) {
        ((SilenceSampleStream) sampleStreams.get(i)).seekTo(positionUs);
      }
      return positionUs;
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
      return constrainSeekPosition(positionUs);
    }

    @Override
    public long getBufferedPositionUs() {
      return C.TIME_END_OF_SOURCE;
    }

    @Override
    public long getNextLoadPositionUs() {
      return C.TIME_END_OF_SOURCE;
    }

    @Override
    public boolean continueLoading(LoadingInfo loadingInfo) {
      return false;
    }

    @Override
    public boolean isLoading() {
      return false;
    }

    @Override
    public void reevaluateBuffer(long positionUs) {}

    private long constrainSeekPosition(long positionUs) {
      return Util.constrainValue(positionUs, 0, durationUs);
    }
  }

  private static final class SilenceSampleStream implements SampleStream {

    private final Format format;
    private final int sampleRateHz;
    private final long durationBytes;

    private boolean sentFormat;
    private long positionBytes;

    public SilenceSampleStream(long durationUs, Format format, int sampleRateHz) {
      this.format = format;
      this.sampleRateHz = sampleRateHz;
      durationBytes = getAudioByteCount(durationUs, sampleRateHz);
    }

    public void seekTo(long positionUs) {
      positionBytes = Util.constrainValue(getAudioByteCount(positionUs, sampleRateHz), 0, durationBytes);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void maybeThrowError() {}

    @Override
    public int readData(
        FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
      if (!sentFormat || (readFlags & FLAG_REQUIRE_FORMAT) != 0) {
        formatHolder.format = format;
        sentFormat = true;
        return C.RESULT_FORMAT_READ;
      }

      long bytesRemaining = durationBytes - positionBytes;
      if (bytesRemaining == 0) {
        buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        return C.RESULT_BUFFER_READ;
      }

      buffer.timeUs = getAudioPositionUs(positionBytes, sampleRateHz);
      buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME);
      int bytesToWrite = (int) min(SILENCE_SAMPLE.length, bytesRemaining);
      if ((readFlags & FLAG_OMIT_SAMPLE_DATA) == 0) {
        buffer.ensureSpaceForWrite(bytesToWrite);
        buffer.data.put(SILENCE_SAMPLE, /* offset= */ 0, bytesToWrite);
      }
      if ((readFlags & FLAG_PEEK) == 0) {
        positionBytes += bytesToWrite;
      }
      return C.RESULT_BUFFER_READ;
    }

    @Override
    public int skipData(long positionUs) {
      long oldPositionBytes = positionBytes;
      seekTo(positionUs);
      return (int) ((positionBytes - oldPositionBytes) / SILENCE_SAMPLE.length);
    }
  }

  private static long getAudioByteCount(long durationUs, int sampleRateHz) {
    long audioSampleCount = durationUsToSampleCount(durationUs, sampleRateHz);
    return Util.getPcmFrameSize(PCM_ENCODING, CHANNEL_COUNT) * audioSampleCount;
  }

  private static long getAudioPositionUs(long bytes, int sampleRateHz) {
    long audioSampleCount = bytes / Util.getPcmFrameSize(PCM_ENCODING, CHANNEL_COUNT);
    return sampleCountToDurationUs(audioSampleCount, sampleRateHz);
  }
}
