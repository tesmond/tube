package com.tube.tv.playback

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import okhttp3.OkHttpClient

/** Builds the single ExoPlayer instance with TV-tuned, bounded buffering (ADR sections 9, 10). */
@OptIn(UnstableApi::class)
class PlayerFactory(private val context: Context, httpClient: OkHttpClient) {

    val bandwidthMeter: DefaultBandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)

    val dataSourceFactory: DataSource.Factory =
        OkHttpDataSource.Factory(httpClient).setUserAgent(USER_AGENT)

    fun create(): ExoPlayer {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowMemory = am.isLowRamDevice || am.memoryClass <= 128

        // Bounded: a hard byte ceiling wins over the time thresholds, so a 4K stream cannot
        // balloon the heap, and a low-end stick gets a smaller window.
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                if (lowMemory) 8_000 else 15_000,   // min buffer
                if (lowMemory) 20_000 else 30_000,  // max buffer
                1_500,                              // start playback after
                3_000,                              // resume after rebuffer
            )
            .setTargetBufferBytes(if (lowMemory) 24 * 1024 * 1024 else 48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(5_000, false)
            .build()

        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(context))
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
