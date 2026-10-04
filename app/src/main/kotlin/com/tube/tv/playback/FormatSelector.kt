package com.tube.tv.playback

import com.tube.tv.domain.AudioVariant
import com.tube.tv.domain.ResolvedMedia
import com.tube.tv.domain.VideoVariant
import kotlin.math.min

enum class Support { NONE, SOFTWARE, HARDWARE }

fun interface DecoderCapabilities {
    fun support(variant: VideoVariant): Support
}

const val QUALITY_AUTO = 0

/**
 * Pure selection logic (no Android types) so it can be unit-tested.
 *
 * Rules, per ADR sections 4 and 5: only formats the device can decode are offered, hardware
 * decoders are preferred, software decoding is tolerated only for low resolutions, and Auto
 * picks the highest resolution the measured bandwidth can sustain.
 */
class FormatSelector(private val caps: DecoderCapabilities) {

    private class Candidate(val v: VideoVariant, val support: Support)

    private fun playable(media: ResolvedMedia, exclude: Set<String>): List<Candidate> =
        media.videoStreams
            .filter { it.key !in exclude }
            .map { Candidate(it, caps.support(it)) }
            .filter {
                it.support == Support.HARDWARE ||
                    (it.support == Support.SOFTWARE && it.v.height <= SOFTWARE_MAX_HEIGHT)
            }

    /** Heights the user can actually choose for this video, highest first. */
    fun availableHeights(media: ResolvedMedia, exclude: Set<String> = emptySet()): List<Int> =
        playable(media, exclude).map { it.v.height }.distinct().sortedDescending()

    /**
     * @param quality [QUALITY_AUTO] or a fixed height.
     * @param heightCap upper bound applied in Auto mode (lowered after repeated stalls).
     * @param bandwidthBps measured bandwidth, or <= 0 if unknown.
     */
    fun pickVideo(
        media: ResolvedMedia,
        quality: Int,
        heightCap: Int = Int.MAX_VALUE,
        bandwidthBps: Long = 0,
        exclude: Set<String> = emptySet(),
    ): VideoVariant? {
        val all = playable(media, exclude)
        if (all.isEmpty()) return null
        val heights = all.map { it.v.height }.distinct().sortedDescending()

        if (quality != QUALITY_AUTO) {
            val h = heights.firstOrNull { it <= quality } ?: heights.last()
            return best(all.filter { it.v.height == h })
        }

        val cap = if (bandwidthBps > 0) heightCap else min(heightCap, UNKNOWN_BANDWIDTH_CAP)
        val budget = if (bandwidthBps > 0) bandwidthBps * BANDWIDTH_HEADROOM else Double.MAX_VALUE
        for (h in heights) {
            if (h > cap) continue
            val fitting = all.filter { it.v.height == h && (it.v.bitrate <= 0 || it.v.bitrate <= budget) }
            if (fitting.isNotEmpty()) return best(fitting)
        }
        // Nothing fits: take the cheapest variant of the lowest resolution.
        return all.filter { it.v.height == heights.last() }.minByOrNull { it.v.bitrate }?.v
    }

    private fun best(c: List<Candidate>): VideoVariant? =
        c.minWithOrNull(
            compareBy<Candidate>(
                { -it.support.ordinal },
                { if (it.v.videoOnly) 0 else 1 },
                { codecRank(it.v) },
                { -min(it.v.fps, 60) },
                { it.v.bitrate },
            ),
        )?.v

    private fun codecRank(v: VideoVariant): Int {
        val c = v.codec
        val avc = c.startsWith("avc")
        val vp9 = c.startsWith("vp9") || c.startsWith("vp09")
        val av1 = c.startsWith("av01")
        // Cheapest-to-decode first; above 1080p only VP9/AV1 exist in practice.
        return if (v.height <= 1080) when { avc -> 0; vp9 -> 1; av1 -> 2; else -> 3 }
        else when { vp9 -> 0; av1 -> 1; avc -> 2; else -> 3 }
    }

    fun pickAudio(media: ResolvedMedia, preferredTrackId: String?, preferredLanguage: String?): AudioVariant? {
        val all = media.audioStreams
        if (all.isEmpty()) return null
        val trackId = preferredTrackId?.takeIf { id -> all.any { it.trackId == id } }
            ?: defaultTrackId(all, preferredLanguage)
        val pool = all.filter { it.trackId == trackId }.ifEmpty { all }
        return pool.minWithOrNull(
            compareBy<AudioVariant>(
                { if (it.mimeType.contains("mp4")) 0 else 1 },
                { -min(it.bitrate, AUDIO_BITRATE_CAP) },
                { it.bitrate },
            ),
        )
    }

    private fun defaultTrackId(all: List<AudioVariant>, lang: String?): String? {
        val ids = all.mapNotNull { it.trackId }.distinct()
        if (ids.size <= 1) return ids.firstOrNull()
        val normal = all.filter { !it.isDescriptive }
        return normal.firstOrNull { lang != null && it.language == lang }?.trackId
            ?: normal.firstOrNull { it.isOriginal }?.trackId
            ?: ids.first()
    }

    fun audioTracks(media: ResolvedMedia): List<AudioTrackOption> =
        media.audioStreams
            .filter { it.trackId != null }
            .distinctBy { it.trackId }
            .map { AudioTrackOption(it.trackId!!, it.label ?: it.language ?: "Default") }

    private companion object {
        const val SOFTWARE_MAX_HEIGHT = 720
        const val UNKNOWN_BANDWIDTH_CAP = 1080
        const val BANDWIDTH_HEADROOM = 0.75
        const val AUDIO_BITRATE_CAP = 160_000
    }
}

data class AudioTrackOption(val id: String, val label: String)
