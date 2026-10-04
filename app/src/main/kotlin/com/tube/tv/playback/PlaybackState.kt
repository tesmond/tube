package com.tube.tv.playback

import com.tube.tv.domain.Chapter
import com.tube.tv.domain.ContentException
import com.tube.tv.domain.SubtitleTrack

enum class Phase { IDLE, RESOLVING, BUFFERING, READY, ENDED, ERROR }

/** Everything the player UI needs. Position/duration are deliberately NOT here: the UI polls them. */
data class PlaybackState(
    val phase: Phase = Phase.IDLE,
    val isPlaying: Boolean = false,
    val videoId: String? = null,
    val title: String = "",
    val channel: String = "",
    val isLive: Boolean = false,
    val qualities: List<Int> = emptyList(),
    /** [QUALITY_AUTO] or a height. */
    val quality: Int = QUALITY_AUTO,
    val activeHeight: Int? = null,
    val speed: Float = 1f,
    val boostMb: Int = 0,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** Index into [subtitles], or -1 for off. */
    val subtitleIndex: Int = -1,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val audioTrackId: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val loop: Boolean = false,
    /** Wall-clock time the sleep timer fires, or null. */
    val sleepAtMs: Long? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val error: ContentException? = null,
)
