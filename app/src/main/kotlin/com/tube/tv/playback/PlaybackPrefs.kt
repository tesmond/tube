package com.tube.tv.playback

import android.content.Context
import androidx.core.content.edit

class PlaybackPrefs(context: Context) {
    private val sp = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var qualityHeight: Int
        get() = sp.getInt("quality", QUALITY_AUTO)
        set(v) = sp.edit { putInt("quality", v) }

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit { putFloat("speed", v) }

    /** Null = subtitles off. */
    var subtitleLanguage: String?
        get() = sp.getString("subtitle_lang", null)
        set(v) = sp.edit { if (v == null) remove("subtitle_lang") else putString("subtitle_lang", v) }

    var audioLanguage: String?
        get() = sp.getString("audio_lang", null)
        set(v) = sp.edit { if (v == null) remove("audio_lang") else putString("audio_lang", v) }
}
