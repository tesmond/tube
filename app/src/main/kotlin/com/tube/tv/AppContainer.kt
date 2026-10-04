package com.tube.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.tube.tv.data.NewPipeBootstrap
import com.tube.tv.data.NewPipeContentRepository
import com.tube.tv.data.NewPipeStreamResolver
import com.tube.tv.domain.ContentRepository
import com.tube.tv.domain.StreamResolver
import com.tube.tv.playback.FormatSelector
import com.tube.tv.playback.MediaCodecCapabilities
import com.tube.tv.playback.PlaybackManager
import com.tube.tv.playback.PlaybackPrefs
import com.tube.tv.playback.PlayerFactory
import com.tube.tv.playback.ResumeStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual DI: everything is lazy so cold start only pays for what the first screen needs. */
@OptIn(UnstableApi::class)
class AppContainer(private val app: Application) {

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val contentRepository: ContentRepository by lazy { NewPipeContentRepository(httpClient) }
    val streamResolver: StreamResolver by lazy { NewPipeStreamResolver(httpClient) }

    val playbackPrefs by lazy { PlaybackPrefs(app) }
    private val resumeStore by lazy { ResumeStore(app) }
    private val playerFactory by lazy { PlayerFactory(app, httpClient) }

    private val playbackLazy = lazy {
        PlaybackManager(
            context = app,
            resolver = streamResolver,
            selector = FormatSelector(MediaCodecCapabilities(app)),
            playerFactory = playerFactory,
            prefs = playbackPrefs,
            resume = resumeStore,
        )
    }

    val playbackManager: PlaybackManager get() = playbackLazy.value

    /** Null until something has played, so lifecycle hooks never force playback setup at cold start. */
    val playbackManagerOrNull: PlaybackManager? get() = if (playbackLazy.isInitialized()) playbackLazy.value else null

    /** Bounded caches (ADR section 12): small RAM cache, RGB_565, modest disk cache. */
    val imageLoader: ImageLoader by lazy {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowRam = am.isLowRamDevice || am.memoryClass <= 128
        ImageLoader.Builder(app)
            .okHttpClient(httpClient)
            .memoryCache {
                MemoryCache.Builder(app)
                    .maxSizeBytes(if (lowRam) 12 * 1024 * 1024 else 32 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("thumbs"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }

    /** Loads the (large) extractor classes off the main thread so the first search is fast. */
    fun warmUp() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { NewPipeBootstrap.ensure(httpClient) }
    }
}
