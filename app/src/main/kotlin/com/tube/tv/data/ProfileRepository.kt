package com.tube.tv.data

import android.content.Context
import androidx.core.content.edit
import com.tube.tv.domain.Profile
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local profiles. The active profile is chosen at every launch and is never persisted. */
class ProfileRepository(private val context: Context) {

    private val sp = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _active = MutableStateFlow<Profile?>(null)
    val active: StateFlow<Profile?> = _active.asStateFlow()

    fun add(name: String): Profile {
        val list = _profiles.value
        val profile = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { "Profile ${list.size + 1}" }.take(24),
            colorIndex = list.size,
        )
        save(list + profile)
        return profile
    }

    fun select(id: String) {
        _active.value = _profiles.value.firstOrNull { it.id == id }
    }

    /** Removes the profile together with its tokens and resume positions. */
    fun delete(id: String) {
        context.deleteSharedPreferences(authPrefsName(id))
        context.deleteSharedPreferences(resumePrefsName(id))
        if (_active.value?.id == id) _active.value = null
        save(_profiles.value.filterNot { it.id == id })
    }

    fun isSignedIn(id: String): Boolean =
        context.getSharedPreferences(authPrefsName(id), Context.MODE_PRIVATE).getString("refresh", null) != null

    private fun save(list: List<Profile>) {
        sp.edit {
            putString(
                KEY,
                JSONArray().apply {
                    list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.colorIndex)) }
                }.toString(),
            )
        }
        _profiles.value = list
    }

    private fun load(): List<Profile> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Profile(o.getString("id"), o.getString("name"), o.optInt("color", it))
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY = "list"
        fun authPrefsName(id: String) = "auth_$id"
        fun resumePrefsName(id: String) = "resume_$id"
    }
}
