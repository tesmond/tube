package com.tube.tv.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import com.tube.tv.MainActivity
import com.tube.tv.domain.AudioVariant
import com.tube.tv.domain.ContentException
import com.tube.tv.domain.ErrorKind
import com.tube.tv.domain.ResolvedMedia
import com.tube.tv.domain.StreamResolver
import com.tube.tv.domain.SubtitleTrack
import com.tube.tv.domain.VideoVariant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the one ExoPlayer + MediaSession (ADR section 10). The UI never creates players; it asks
 * this class to play a video id and observes [state]. The player exists only between [play] and
 * [stop], so nothing is retained while browsing.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(
    private val context: Context,
    private val resolver: StreamResolver,
    private val selector: FormatSelector,
    private val playerFactory: PlayerFactory,
    private val prefs: PlaybackPrefs,
    private val resume: ResumeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    var player: ExoPlayer? = null
        private set
    var session: MediaSession? = null
        private set

    private var media: ResolvedMedia? = null
    private var activeVideo: VideoVariant? = null
    private var loadJob: Job? = null
    private var sleepJob: Job? = null
    private var idleJob: Job? = null

    private var recoveryAttempts = 0
    private var totalRecoveries = 0
    private var heightCap = Int.MAX_VALUE
    private val excluded = HashSet<String>()
    private val stalls = ArrayDeque<Long>()
    private var lastSeekAt = 0L
    private var subtitleInitialised = false

    private var queue: List<String> = emptyList()
    private var queueIndex = -1

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRegisteredAt = 0L

    // ------------------------------------------------------------------ public API

    fun setQueue(ids: List<String>, startId: String) {
        val i = ids.indexOf(startId)
        if (i < 0) {
            queue = listOf(startId)
            queueIndex = 0
        } else {
            queue = ids
            queueIndex = i
        }
    }

    /** Idempotent for the video that is already loaded (survives screen/activity recreation). */
    fun play(videoId: String) {
        val s = _state.value
        if (s.videoId == videoId && s.phase != Phase.IDLE && s.phase != Phase.ERROR) return
        if (queue.getOrNull(queueIndex) != videoId) {
            queue = listOf(videoId)
            queueIndex = 0
        }
        startFresh(videoId, null)
    }

    fun next() {
        queue.getOrNull(queueIndex + 1)?.let {
            queueIndex++
            startFresh(it, null)
        }
    }

    fun previous() {
        val p = player
        // Like most players: restart the current video if we are past the first few seconds.
        if (p != null && p.currentPosition > 3_000) {
            seekTo(0)
            return
        }
        queue.getOrNull(queueIndex - 1)?.let {
            queueIndex--
            startFresh(it, 0L)
        }
    }

    fun retry() {
        val id = _state.value.videoId ?: return
        unregisterNetwork()
        recoveryAttempts = 0
        totalRecoveries = 0
        val pos = (player?.currentPosition ?: 0L).takeIf { it > 0 } ?: resume.get(id) ?: 0L
        _state.update { it.copy(phase = Phase.RESOLVING, error = null) }
        loadJob?.cancel()
        loadJob = scope.launch { load(id, pos, force = true, play = true) }
    }

    fun togglePlay() {
        val p = player ?: return
        when {
            _state.value.phase == Phase.ENDED -> {
                p.seekTo(0)
                p.play()
            }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
    }

    fun setPlaying(play: Boolean) {
        val p = player ?: return
        if (play) p.play() else p.pause()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        lastSeekAt = SystemClock.elapsedRealtime()
        p.seekTo(ms.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        seekTo(p.currentPosition + deltaMs)
    }

    fun seekToChapter(index: Int) {
        _state.value.chapters.getOrNull(index)?.let { seekTo(it.startMs) }
    }

    fun positionMs(): Long = player?.currentPosition ?: 0L
    fun bufferedMs(): Long = player?.bufferedPosition ?: 0L
    fun durationMs(): Long = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun setLoop(loop: Boolean) {
        player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.update { it.copy(loop = loop) }
    }

    fun setSpeed(speed: Float) {
        prefs.speed = speed
        player?.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setQuality(height: Int) {
        prefs.qualityHeight = height
        heightCap = Int.MAX_VALUE
        stalls.clear()
        _state.update { it.copy(quality = height) }
        if (media?.isLive == false) rebuild()
    }

    fun setAudioTrack(trackId: String) {
        media?.audioStreams?.firstOrNull { it.trackId == trackId }?.language?.let { prefs.audioLanguage = it }
        _state.update { it.copy(audioTrackId = trackId) }
        rebuild()
    }

    fun setSubtitle(index: Int) {
        val subs = _state.value.subtitles
        prefs.subtitleLanguage = subs.getOrNull(index)?.languageTag
        _state.update { it.copy(subtitleIndex = index) }
        applySubtitleSelection()
    }

    /** minutes <= 0 cancels. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepAtMs = null) }
            return
        }
        val delayMs = minutes * 60_000L
        _state.update { it.copy(sleepAtMs = System.currentTimeMillis() + delayMs) }
        sleepJob = scope.launch {
            delay(delayMs)
            player?.pause()
            _state.update { it.copy(sleepAtMs = null) }
        }
    }

    /** App left the foreground: pause, free the video decoder, and release everything after a while. */
    fun onAppBackground() {
        val p = player ?: return
        p.pause()
        setVideoDisabled(true)
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            stop()
        }
    }

    fun onAppForeground() {
        idleJob?.cancel()
        setVideoDisabled(false)
    }

    /** Full teardown: saves position, releases session, player, decoders and the foreground service. */
    fun stop() {
        loadJob?.cancel()
        sleepJob?.cancel()
        idleJob?.cancel()
        unregisterNetwork()
        saveResume()
        session?.release()
        session = null
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        media = null
        activeVideo = null
        resolver.invalidate()
        queue = emptyList()
        queueIndex = -1
        _state.value = PlaybackState()
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    // ------------------------------------------------------------------ loading

    private fun startFresh(videoId: String, startMs: Long?) {
        loadJob?.cancel()
        idleJob?.cancel()
        unregisterNetwork()
        saveResume()
        recoveryAttempts = 0
        totalRecoveries = 0
        heightCap = Int.MAX_VALUE
        excluded.clear()
        stalls.clear()
        subtitleInitialised = false
        media = null
        activeVideo = null

        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        p.repeatMode = Player.REPEAT_MODE_OFF

        _state.value = PlaybackState(
            phase = Phase.RESOLVING,
            videoId = videoId,
            speed = prefs.speed,
            quality = prefs.qualityHeight,
            sleepAtMs = _state.value.sleepAtMs, // a sleep timer outlives a queue advance
            hasPrevious = queueIndex > 0,
            hasNext = queueIndex in 0 until queue.size - 1,
        )
        startService()
        loadJob = scope.launch { load(videoId, startMs ?: resume.get(videoId) ?: 0L, force = false, play = true) }
    }

    private suspend fun load(videoId: String, startMs: Long, force: Boolean, play: Boolean) {
        val m = try {
            resolver.resolve(videoId, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContentException) {
            fail(e)
            return
        }
        media = m
        prepareFrom(m, startMs, play)
    }

    private fun rebuild() {
        val m = media ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val play = p.playWhenReady
        loadJob?.cancel()
        loadJob = scope.launch { prepareFrom(m, pos, play) }
    }

    private suspend fun prepareFrom(m: ResolvedMedia, startMs: Long, play: Boolean) {
        val sel = withContext(Dispatchers.Default) { select(m) }
        if (!m.isLive && sel.video == null) {
            fail(ContentException(ErrorKind.UNSUPPORTED_CODEC))
            return
        }
        if (m.isLive && m.hlsUrl == null) {
            fail(ContentException(ErrorKind.UNAVAILABLE))
            return
        }
        val p = ensurePlayer()
        activeVideo = sel.video

        val subs = m.subtitles
        val subIndex = if (subtitleInitialised) _state.value.subtitleIndex else initialSubtitleIndex(subs)
        subtitleInitialised = true

        p.setMediaSource(buildSource(m, sel, subs), startMs)
        p.playbackParameters = PlaybackParameters(prefs.speed)
        p.repeatMode = if (_state.value.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = play

        _state.update {
            it.copy(
                phase = Phase.BUFFERING,
                title = m.title,
                channel = m.channel,
                isLive = m.isLive,
                qualities = sel.heights,
                quality = prefs.qualityHeight,
                activeHeight = sel.video?.height,
                speed = prefs.speed,
                subtitles = subs,
                subtitleIndex = subIndex,
                audioTracks = selector.audioTracks(m),
                audioTrackId = sel.audio?.trackId,
                chapters = m.chapters,
                error = null,
            )
        }
        applySubtitleSelection()
    }

    private class Selection(val video: VideoVariant?, val audio: AudioVariant?, val heights: List<Int>)

    private fun select(m: ResolvedMedia): Selection {
        if (m.isLive) return Selection(null, null, emptyList())
        val video = selector.pickVideo(
            m, prefs.qualityHeight, heightCap, playerFactory.bandwidthMeter.bitrateEstimate, excluded,
        )
        val audio = if (video?.videoOnly == true) {
            selector.pickAudio(m, _state.value.audioTrackId, prefs.audioLanguage ?: Locale.getDefault().language)
        } else null
        return Selection(video, audio, selector.availableHeights(m, excluded))
    }

    private fun buildSource(m: ResolvedMedia, sel: Selection, subs: List<SubtitleTrack>): MediaSource {
        val dsf = playerFactory.dataSourceFactory
        val metadata = MediaMetadata.Builder()
            .setTitle(m.title)
            .setArtist(m.channel)
            .setArtworkUri(m.thumbnailUrl?.toUri())
            .build()

        if (m.isLive) {
            val item = MediaItem.Builder().setMediaId(m.videoId).setUri(m.hlsUrl).setMediaMetadata(metadata).build()
            return HlsMediaSource.Factory(dsf).createMediaSource(item)
        }

        val video = sel.video!!
        val videoItem = MediaItem.Builder()
            .setMediaId(m.videoId)
            .setUri(video.url)
            .setMediaMetadata(metadata)
            .build()
        val sources = mutableListOf<MediaSource>(ProgressiveMediaSource.Factory(dsf).createMediaSource(videoItem))
        sel.audio?.let {
            sources += ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(it.url))
        }
        subs.forEachIndexed { i, s ->
            val cfg = MediaItem.SubtitleConfiguration.Builder(s.url.toUri())
                .setId("sub$i")
                .setMimeType(s.mimeType)
                .setLanguage(s.languageTag)
                .setLabel(s.label)
                .setSelectionFlags(0)
                .build()
            sources += SingleSampleMediaSource.Factory(dsf).createMediaSource(cfg, C.TIME_UNSET)
        }
        return if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
    }

    // ------------------------------------------------------------------ subtitles

    private fun initialSubtitleIndex(subs: List<SubtitleTrack>): Int {
        val lang = prefs.subtitleLanguage ?: return -1
        val manual = subs.indexOfFirst { it.languageTag == lang && !it.autoGenerated }
        return if (manual >= 0) manual else subs.indexOfFirst { it.languageTag == lang }
    }

    private fun applySubtitleSelection() {
        val p = player ?: return
        val index = _state.value.subtitleIndex
        val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (index < 0) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            // Merged sources prefix format ids with "<childIndex>:", so match on the suffix.
            val group = p.currentTracks.groups.firstOrNull { g ->
                g.type == C.TRACK_TYPE_TEXT &&
                    g.length > 0 &&
                    g.mediaTrackGroup.getFormat(0).id.let { it == "sub$index" || it?.endsWith(":sub$index") == true }
            }
            if (group != null) b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        val params = b.build()
        if (params != p.trackSelectionParameters) p.trackSelectionParameters = params
    }

    private fun setVideoDisabled(disabled: Boolean) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
        if (params != p.trackSelectionParameters) p.trackSelectionParameters = params
    }

    // ------------------------------------------------------------------ listener / recovery

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val phase = _state.value.phase
            if (phase == Phase.RESOLVING || phase == Phase.ERROR || phase == Phase.IDLE) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.update { it.copy(phase = Phase.BUFFERING) }
                    if (phase == Phase.READY) noteStall()
                }
                Player.STATE_READY -> {
                    recoveryAttempts = 0
                    _state.update { it.copy(phase = Phase.READY) }
                }
                Player.STATE_ENDED -> onEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveResume()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                lastSeekAt = SystemClock.elapsedRealtime()
            }
        }

        override fun onTracksChanged(tracks: Tracks) = applySubtitleSelection()

        override fun onPlayerError(error: PlaybackException) = recover(error)
    }

    private fun onEnded() {
        _state.value.videoId?.let { resume.remove(it) }
        if (_state.value.hasNext) {
            next()
            return
        }
        _state.update { it.copy(phase = Phase.ENDED, isPlaying = false) }
    }

    /** Three stalls inside a minute in Auto mode => step down one resolution, keeping position. */
    private fun noteStall() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekAt < 3_000) return
        stalls.addLast(now)
        while (stalls.isNotEmpty() && now - stalls.first() > 60_000) stalls.removeFirst()
        val s = _state.value
        if (stalls.size < 3 || s.quality != QUALITY_AUTO || s.isLive) return
        val active = s.activeHeight ?: return
        val lower = s.qualities.firstOrNull { it < active } ?: return
        stalls.clear()
        heightCap = lower
        rebuild()
    }

    private fun recover(e: PlaybackException) {
        val p = player
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        val id = _state.value.videoId
        if (media == null || id == null || p == null) {
            fail(ContentException(ErrorKind.UNKNOWN, e))
            return
        }
        val kind = classify(e)
        if (kind == ErrorKind.UNSUPPORTED_CODEC) activeVideo?.let { excluded += it.key }
        if (recoveryAttempts >= MAX_RECOVERIES || totalRecoveries >= MAX_TOTAL_RECOVERIES) {
            fail(ContentException(kind, e))
            return
        }
        recoveryAttempts++
        totalRecoveries++

        val pos = p.currentPosition
        val play = p.playWhenReady
        val attempt = recoveryAttempts
        _state.update { it.copy(phase = Phase.RESOLVING) }
        loadJob?.cancel()
        loadJob = scope.launch {
            if (kind != ErrorKind.UNSUPPORTED_CODEC) delay(500L shl (attempt - 1)) // 0.5s, 1s, 2s
            // An expired/failed URL needs a fresh resolution; a codec problem only needs another variant.
            load(id, pos, force = kind != ErrorKind.UNSUPPORTED_CODEC, play = play)
        }
    }

    private fun classify(e: PlaybackException): ErrorKind {
        val http = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        if (http != null) {
            return if (http.responseCode == 403 || http.responseCode == 404 || http.responseCode == 410) {
                ErrorKind.STREAM_EXPIRED
            } else {
                ErrorKind.NETWORK
            }
        }
        // PlaybackException code families: 2xxx = I/O, 3xxx = parsing, 4xxx = decoder.
        return when (e.errorCode) {
            in 4001..4005 -> ErrorKind.UNSUPPORTED_CODEC
            in 2000..2999 -> ErrorKind.NETWORK
            in 3000..3999 -> ErrorKind.EXTRACTION
            else -> ErrorKind.UNKNOWN
        }
    }

    private fun fail(e: ContentException) {
        _state.update { it.copy(phase = Phase.ERROR, isPlaying = false, error = e) }
        player?.stop() // free decoders while the error is on screen
        if (e.kind == ErrorKind.NETWORK) registerNetworkCallback()
    }

    // ------------------------------------------------------------------ network auto-retry

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The callback fires once immediately on registration; ignore that one.
                if (SystemClock.elapsedRealtime() - networkRegisteredAt < 2_000) return
                scope.launch {
                    val s = _state.value
                    if (s.phase == Phase.ERROR && s.error?.kind == ErrorKind.NETWORK) retry()
                }
            }
        }
        networkCallback = cb
        networkRegisteredAt = SystemClock.elapsedRealtime()
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun saveResume() {
        val p = player ?: return
        val s = _state.value
        val id = s.videoId ?: return
        if (s.isLive || (s.phase != Phase.READY && s.phase != Phase.BUFFERING)) return
        resume.put(id, p.currentPosition, durationMs())
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = playerFactory.create()
        p.addListener(listener)
        player = p
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(context, p).setSessionActivity(open).build()
        return p
    }

    private fun startService() {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    private companion object {
        const val MAX_RECOVERIES = 3
        const val MAX_TOTAL_RECOVERIES = 8
        const val IDLE_RELEASE_MS = 5 * 60_000L
    }
}
