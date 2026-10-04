package com.tube.tv.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Tiny bounded (most-recent-100) map of videoId -> resume position, kept per profile in one
 * preferences key. Call [setProfile] before use.
 */
class ResumeStore(private val context: Context) {
    private var sp: SharedPreferences? = null
    private var map = LinkedHashMap<String, Long>()

    @Synchronized
    fun setProfile(prefsName: String) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        sp = prefs
        map = parse(prefs.getString(KEY, "").orEmpty())
    }

    @Synchronized
    fun get(videoId: String): Long? = map[videoId]

    @Synchronized
    fun put(videoId: String, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_MS || (durationMs > 0 && positionMs > durationMs - END_MARGIN_MS)) {
            remove(videoId)
            return
        }
        map.remove(videoId)
        map[videoId] = positionMs
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        persist()
    }

    @Synchronized
    fun remove(videoId: String) {
        if (map.remove(videoId) != null) persist()
    }

    private fun persist() {
        sp?.edit { putString(KEY, map.entries.joinToString(";") { "${it.key}=${it.value}" }) }
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 100
        private const val MIN_MS = 10_000L
        private const val END_MARGIN_MS = 10_000L

        internal fun parse(s: String): LinkedHashMap<String, Long> {
            val out = LinkedHashMap<String, Long>()
            for (part in s.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val v = part.substring(i + 1).toLongOrNull() ?: continue
                out[part.substring(0, i)] = v
            }
            return out
        }
    }
}
