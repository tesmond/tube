package com.tube.tv.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.tube.tv.domain.VideoVariant

/** Asks the platform which decoders can play a variant; results are cached per format. */
@OptIn(UnstableApi::class)
class MediaCodecCapabilities(context: Context) : DecoderCapabilities {

    private val displayHdr: Boolean = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }.getOrDefault(false)

    private val cache = HashMap<String, Support>()

    @Synchronized
    override fun support(variant: VideoVariant): Support =
        cache.getOrPut(variant.key) { compute(variant) }

    private fun compute(v: VideoVariant): Support {
        if (v.isHdr && !displayHdr) return Support.NONE
        val mime = MimeTypes.getVideoMediaMimeType(v.codec) ?: return Support.NONE
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setCodecs(v.codec)
            .setWidth(v.width)
            .setHeight(v.height)
            .setFrameRate(v.fps.toFloat())
            .build()
        return try {
            val infos = MediaCodecUtil.getDecoderInfos(mime, /* requiresSecureDecoder = */ false, /* requiresTunnelingDecoder = */ false)
            var best = Support.NONE
            for (info in infos) {
                if (!info.isFormatSupported(format)) continue
                if (info.hardwareAccelerated) return Support.HARDWARE
                best = Support.SOFTWARE
            }
            best
        } catch (e: Exception) {
            Support.NONE
        }
    }
}
