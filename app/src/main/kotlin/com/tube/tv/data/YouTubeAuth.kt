package com.tube.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.tube.tv.domain.AuthRepository
import com.tube.tv.domain.ContentException
import com.tube.tv.domain.DeviceCode
import com.tube.tv.domain.ErrorKind
import com.tube.tv.domain.LoginResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject

/**
 * OAuth 2.0 device-code flow ("limited input device"), the same mechanism YouTube's own TV app uses.
 * The client id/secret below are the publicly known ones of the YouTube TV client, so requests are
 * attributed to that client. Tokens live in app-private preferences (backup is disabled).
 */
class YouTubeAuth(private val context: Context, private val client: OkHttpClient) : AuthRepository {

    // Tokens are stored per profile; [setProfile] must be called when a profile is chosen.
    @Volatile private var sp: SharedPreferences = context.getSharedPreferences("auth_none", Context.MODE_PRIVATE)
    private val _signedIn = MutableStateFlow(false)

    fun setProfile(profileId: String) {
        sp = context.getSharedPreferences(ProfileRepository.authPrefsName(profileId), Context.MODE_PRIVATE)
        _signedIn.value = sp.getString(KEY_REFRESH, null) != null
    }

    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    private val refreshLock = Mutex()
    private val deviceId = UUID.randomUUID().toString().replace("-", "")

    override suspend fun startDeviceLogin(): DeviceCode {
        val res = client.postJson(
            DEVICE_CODE_URL,
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("scope", SCOPE)
                .put("device_id", deviceId)
                .put("device_model", "ytlr::"),
        )
        val json = parse(res)
        if (!json.has("device_code")) {
            throw ContentException(ErrorKind.UNKNOWN, detail = "Sign-in refused: HTTP ${res.code} ${res.body.take(120)}")
        }
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url", "https://www.youtube.com/activate"),
            expiresInSec = json.optInt("expires_in", 1800),
            intervalSec = json.optInt("interval", 5).coerceAtLeast(3),
        )
    }

    override suspend fun pollLogin(code: DeviceCode): LoginResult {
        val res = client.postJson(
            TOKEN_URL,
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
                .put("code", code.deviceCode)
                .put("grant_type", "http://oauth.net/grant_type/device/1.0"),
        )
        val json = parse(res)
        if (json.has("access_token")) {
            store(json)
            return LoginResult.Success
        }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> LoginResult.Pending
            "slow_down" -> LoginResult.SlowDown
            "access_denied" -> LoginResult.Failed("Sign-in was declined.")
            "expired_token" -> LoginResult.Failed("The code expired. Try again.")
            else -> LoginResult.Failed("Sign-in failed (${error.ifEmpty { "HTTP ${res.code}" }}).")
        }
    }

    /** A valid access token, refreshing if needed; null when signed out. */
    suspend fun accessToken(): String? = refreshLock.withLock {
        val refresh = sp.getString(KEY_REFRESH, null) ?: return@withLock null
        val access = sp.getString(KEY_ACCESS, null)
        if (access != null && sp.getLong(KEY_EXPIRES, 0) - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return@withLock access
        }
        val res = client.postJson(
            TOKEN_URL,
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
                .put("refresh_token", refresh)
                .put("grant_type", "refresh_token"),
        )
        val json = parse(res)
        if (json.has("access_token")) {
            store(json)
            return@withLock json.getString("access_token")
        }
        if (json.optString("error") == "invalid_grant") {
            signOut()
            throw ContentException(ErrorKind.SIGNED_OUT)
        }
        throw ContentException(ErrorKind.UNKNOWN, detail = "Token refresh failed: HTTP ${res.code} ${res.body.take(120)}")
    }

    override fun signOut() {
        sp.edit { clear() }
        _signedIn.value = false
    }

    private fun store(json: JSONObject) {
        sp.edit {
            putString(KEY_ACCESS, json.getString("access_token"))
            putLong(KEY_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            // A refresh response omits refresh_token; keep the one we already have.
            if (json.has("refresh_token")) putString(KEY_REFRESH, json.getString("refresh_token"))
        }
        _signedIn.value = sp.getString(KEY_REFRESH, null) != null
    }

    private fun parse(res: HttpResult): JSONObject = try {
        JSONObject(res.body)
    } catch (e: JSONException) {
        Log.w("Tube", "auth: non-JSON response HTTP ${res.code}: ${res.body.take(200)}")
        throw ContentException(ErrorKind.UNKNOWN, e, "Unexpected sign-in response (HTTP ${res.code})")
    } catch (e: CancellationException) {
        throw e
    }

    private companion object {
        const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
        const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EXPIRES = "expires"
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
