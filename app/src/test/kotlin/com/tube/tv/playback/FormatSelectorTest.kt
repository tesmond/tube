package com.tube.tv.playback

import com.tube.tv.domain.AudioVariant
import com.tube.tv.domain.ResolvedMedia
import com.tube.tv.domain.VideoVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSelectorTest {

    private fun v(h: Int, codec: String = "avc1.640028", fps: Int = 30, kbps: Int, videoOnly: Boolean = true) =
        VideoVariant(
            url = "https://x/$h/$codec/$fps",
            mimeType = if (codec.startsWith("avc")) "video/mp4" else "video/webm",
            codec = codec, width = h * 16 / 9, height = h, fps = fps,
            bitrate = kbps * 1000, videoOnly = videoOnly,
        )

    private fun a(kbps: Int, mime: String = "audio/mp4", track: String? = null, lang: String? = null, original: Boolean = false) =
        AudioVariant("https://a/$kbps/$mime/$track", mime, "mp4a.40.2", kbps * 1000, track, lang, lang, original, false)

    private fun media(videos: List<VideoVariant>, audios: List<AudioVariant> = emptyList()) = ResolvedMedia(
        "id", "t", "c", 1000, null, false, null, videos, audios, emptyList(), emptyList(), Long.MAX_VALUE,
    )

    private val hardwareAll = DecoderCapabilities { Support.HARDWARE }

    private val ladder = listOf(
        v(2160, "vp09.00.50.08", 30, 15_000),
        v(1080, "avc1.640028", 30, 4_000),
        v(1080, "vp09.00.40.08", 30, 2_500),
        v(720, "avc1.64001f", 30, 2_000),
        v(360, "avc1.4d401e", 30, 700),
    )

    @Test fun `fixed quality picks exact height and cheapest codec`() {
        val pick = FormatSelector(hardwareAll).pickVideo(media(ladder), quality = 1080)
        assertEquals(1080, pick!!.height)
        assertTrue(pick.codec.startsWith("avc"))
    }

    @Test fun `fixed quality falls back to nearest lower`() {
        assertEquals(720, FormatSelector(hardwareAll).pickVideo(media(ladder), quality = 900)!!.height)
    }

    @Test fun `auto respects bandwidth budget`() {
        val sel = FormatSelector(hardwareAll)
        // 4 Mbps * 0.75 = 3 Mbps budget -> 1080p AVC (4M) does not fit, VP9 1080p (2.5M) does.
        val pick = sel.pickVideo(media(ladder), QUALITY_AUTO, bandwidthBps = 4_000_000)!!
        assertEquals(1080, pick.height)
        assertTrue(pick.codec.startsWith("vp09"))
        assertEquals(360, sel.pickVideo(media(ladder), QUALITY_AUTO, bandwidthBps = 1_000_000)!!.height)
    }

    @Test fun `auto with unknown bandwidth is capped at 1080p`() {
        assertEquals(1080, FormatSelector(hardwareAll).pickVideo(media(ladder), QUALITY_AUTO, bandwidthBps = 0)!!.height)
    }

    @Test fun `height cap lowers auto selection`() {
        val pick = FormatSelector(hardwareAll).pickVideo(media(ladder), QUALITY_AUTO, heightCap = 720, bandwidthBps = 100_000_000)
        assertEquals(720, pick!!.height)
    }

    @Test fun `undecodable formats are not offered`() {
        val noVp9 = DecoderCapabilities { if (it.codec.startsWith("vp09")) Support.NONE else Support.HARDWARE }
        val sel = FormatSelector(noVp9)
        assertEquals(listOf(1080, 720, 360), sel.availableHeights(media(ladder)))
    }

    @Test fun `software decoding only tolerated at low resolution`() {
        val soft = DecoderCapabilities { Support.SOFTWARE }
        assertEquals(listOf(720, 360), FormatSelector(soft).availableHeights(media(ladder)))
    }

    @Test fun `hardware beats software at equal height`() {
        val caps = DecoderCapabilities { if (it.codec.startsWith("avc")) Support.SOFTWARE else Support.HARDWARE }
        val pick = FormatSelector(caps).pickVideo(media(listOf(v(720, "avc1.64001f", kbps = 1_000), v(720, "vp09.00.31.08", kbps = 1_200))), 720)!!
        assertTrue(pick.codec.startsWith("vp09"))
    }

    @Test fun `excluded variant is skipped`() {
        val sel = FormatSelector(hardwareAll)
        val first = sel.pickVideo(media(ladder), 1080)!!
        val second = sel.pickVideo(media(ladder), 1080, exclude = setOf(first.key))!!
        assertTrue(first.key != second.key)
        assertEquals(1080, second.height)
    }

    @Test fun `nothing playable returns null`() {
        assertNull(FormatSelector { Support.NONE }.pickVideo(media(ladder), QUALITY_AUTO))
    }

    @Test fun `audio prefers aac capped at 160k`() {
        val audios = listOf(a(48), a(128), a(256, "audio/webm"), a(160, "audio/webm"))
        val pick = FormatSelector(hardwareAll).pickAudio(media(ladder, audios), null, null)
        assertEquals(128_000, pick!!.bitrate)
        assertEquals("audio/mp4", pick.mimeType)
    }

    @Test fun `audio track choice follows language then original`() {
        val audios = listOf(
            a(128, track = "en.4", lang = "en", original = true),
            a(128, track = "fr.4", lang = "fr"),
        )
        val sel = FormatSelector(hardwareAll)
        assertEquals("fr.4", sel.pickAudio(media(ladder, audios), null, "fr")!!.trackId)
        assertEquals("en.4", sel.pickAudio(media(ladder, audios), null, "de")!!.trackId)
        assertEquals("fr.4", sel.pickAudio(media(ladder, audios), "fr.4", "en")!!.trackId)
        assertEquals(2, sel.audioTracks(media(ladder, audios)).size)
        assertNotNull(sel.pickAudio(media(ladder, audios), "missing", null))
    }
}
