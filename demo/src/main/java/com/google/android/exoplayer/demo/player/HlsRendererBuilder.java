/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer.demo.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.os.Handler;
import com.omny.android.exoplayer.DefaultLoadControl;
import com.omny.android.exoplayer.LoadControl;
import com.omny.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.omny.android.exoplayer.MediaCodecSelector;
import com.omny.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.omny.android.exoplayer.SampleSource;
import com.omny.android.exoplayer.TrackRenderer;
import com.omny.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.demo.player.DemoPlayer.RendererBuilder;
import com.omny.android.exoplayer.hls.DefaultHlsTrackSelector;
import com.omny.android.exoplayer.hls.HlsChunkSource;
import com.omny.android.exoplayer.hls.HlsMasterPlaylist;
import com.omny.android.exoplayer.hls.HlsPlaylist;
import com.omny.android.exoplayer.hls.HlsPlaylistParser;
import com.omny.android.exoplayer.hls.HlsSampleSource;
import com.omny.android.exoplayer.hls.PtsTimestampAdjusterProvider;
import com.omny.android.exoplayer.metadata.MetadataTrackRenderer;
import com.omny.android.exoplayer.metadata.id3.Id3Frame;
import com.omny.android.exoplayer.metadata.id3.Id3Parser;
import com.omny.android.exoplayer.text.TextTrackRenderer;
import com.omny.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.omny.android.exoplayer.upstream.DataSource;
import com.omny.android.exoplayer.upstream.DefaultAllocator;
import com.omny.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.omny.android.exoplayer.upstream.DefaultUriDataSource;
import com.omny.android.exoplayer.util.ManifestFetcher;
import com.omny.android.exoplayer.util.ManifestFetcher.ManifestCallback;
import java.io.IOException;
import java.util.List;

/**
 * A {@link RendererBuilder} for HLS.
 */
public class HlsRendererBuilder implements RendererBuilder {

  private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
  private static final int MAIN_BUFFER_SEGMENTS = 254;
  private static final int AUDIO_BUFFER_SEGMENTS = 54;
  private static final int TEXT_BUFFER_SEGMENTS = 2;

  private final Context context;
  private final String userAgent;
  private final String url;

  private AsyncRendererBuilder currentAsyncBuilder;

  public HlsRendererBuilder(Context context, String userAgent, String url) {
    this.context = context;
    this.userAgent = userAgent;
    this.url = url;
  }

  @Override
  public void buildRenderers(DemoPlayer player) {
    currentAsyncBuilder = new AsyncRendererBuilder(context, userAgent, url, player);
    currentAsyncBuilder.init();
  }

  @Override
  public void cancel() {
    if (currentAsyncBuilder != null) {
      currentAsyncBuilder.cancel();
      currentAsyncBuilder = null;
    }
  }

  private static final class AsyncRendererBuilder implements ManifestCallback<HlsPlaylist> {

    private final Context context;
    private final String userAgent;
    private final DemoPlayer player;
    private final ManifestFetcher<HlsPlaylist> playlistFetcher;

    private boolean canceled;

    public AsyncRendererBuilder(Context context, String userAgent, String url, DemoPlayer player) {
      this.context = context;
      this.userAgent = userAgent;
      this.player = player;
      HlsPlaylistParser parser = new HlsPlaylistParser();
      playlistFetcher = new ManifestFetcher<>(url, new DefaultUriDataSource(context, userAgent),
          parser);
    }

    public void init() {
      playlistFetcher.singleLoad(player.getMainHandler().getLooper(), this);
    }

    public void cancel() {
      canceled = true;
    }

    @Override
    public void onSingleManifestError(IOException e) {
      if (canceled) {
        return;
      }

      player.onRenderersError(e);
    }

    @Override
    public void onSingleManifest(HlsPlaylist manifest) {
      if (canceled) {
        return;
      }

      Handler mainHandler = player.getMainHandler();
      LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
      DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
      PtsTimestampAdjusterProvider timestampAdjusterProvider = new PtsTimestampAdjusterProvider();

      boolean haveSubtitles = false;
      boolean haveAudios = false;
      if (manifest instanceof HlsMasterPlaylist) {
        HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) manifest;
        haveSubtitles = !masterPlaylist.subtitles.isEmpty();
        haveAudios = !masterPlaylist.audios.isEmpty();
      }

      // Build the video/id3 renderers.
      DataSource dataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
      HlsChunkSource chunkSource = new HlsChunkSource(true /* isMaster */, dataSource, manifest,
          DefaultHlsTrackSelector.newDefaultInstance(context), bandwidthMeter,
          timestampAdjusterProvider);
      HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
          MAIN_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, DemoPlayer.TYPE_VIDEO);
      MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context,
          sampleSource, MediaCodecSelector.DEFAULT, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
          5000, mainHandler, player, 50);
      MetadataTrackRenderer<List<Id3Frame>> id3Renderer = new MetadataTrackRenderer<>(
          sampleSource, new Id3Parser(), player, mainHandler.getLooper());

      // Build the audio renderer.
      MediaCodecAudioTrackRenderer audioRenderer;
      if (haveAudios) {
        DataSource audioDataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
        HlsChunkSource audioChunkSource = new HlsChunkSource(false /* isMaster */, audioDataSource,
            manifest, DefaultHlsTrackSelector.newAudioInstance(), bandwidthMeter,
            timestampAdjusterProvider);
        HlsSampleSource audioSampleSource = new HlsSampleSource(audioChunkSource, loadControl,
            AUDIO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player,
            DemoPlayer.TYPE_AUDIO);
        audioRenderer = new MediaCodecAudioTrackRenderer(
            new SampleSource[] {sampleSource, audioSampleSource}, MediaCodecSelector.DEFAULT, null,
            true, player.getMainHandler(), player, AudioCapabilities.getCapabilities(context),
            AudioManager.STREAM_MUSIC);
      } else {
        audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource,
            MediaCodecSelector.DEFAULT, null, true, player.getMainHandler(), player,
            AudioCapabilities.getCapabilities(context), AudioManager.STREAM_MUSIC);
      }

      // Build the text renderer.
      TrackRenderer textRenderer;
      if (haveSubtitles) {
        DataSource textDataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
        HlsChunkSource textChunkSource = new HlsChunkSource(false /* isMaster */, textDataSource,
            manifest, DefaultHlsTrackSelector.newSubtitleInstance(), bandwidthMeter,
            timestampAdjusterProvider);
        HlsSampleSource textSampleSource = new HlsSampleSource(textChunkSource, loadControl,
            TEXT_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, DemoPlayer.TYPE_TEXT);
        textRenderer = new TextTrackRenderer(textSampleSource, player, mainHandler.getLooper());
      } else {
        textRenderer = new Eia608TrackRenderer(sampleSource, player, mainHandler.getLooper());
      }

      TrackRenderer[] renderers = new TrackRenderer[DemoPlayer.RENDERER_COUNT];
      renderers[DemoPlayer.TYPE_VIDEO] = videoRenderer;
      renderers[DemoPlayer.TYPE_AUDIO] = audioRenderer;
      renderers[DemoPlayer.TYPE_METADATA] = id3Renderer;
      renderers[DemoPlayer.TYPE_TEXT] = textRenderer;
      player.onRenderers(renderers, bandwidthMeter);
    }

  }

}
