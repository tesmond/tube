package com.tube.tv.data

import android.util.Log
import com.tube.tv.domain.BrowseItem
import com.tube.tv.domain.ContentException
import com.tube.tv.domain.ContentRepository
import com.tube.tv.domain.ErrorKind
import com.tube.tv.domain.FeedRepository
import com.tube.tv.domain.PageToken
import com.tube.tv.domain.ResultPage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject

/**
 * Home and history via YouTube's InnerTube API using the TV client (the one the device-code token
 * is valid for). Home works signed-out too; if the personalised feed is unavailable it falls back to
 * the extractor's trending kiosk, then to a plain search, so the screen is never empty by default.
 */
class InnerTubeFeedRepository(
    private val client: OkHttpClient,
    private val auth: YouTubeAuth,
    private val fallback: ContentRepository,
) : FeedRepository {

    private class Continuation(val token: String)
    private class SearchCursor(val page: PageToken)

    override suspend fun home(page: PageToken?): ResultPage<BrowseItem> {
        when (val raw = page?.raw) {
            is Continuation -> return continuation(raw.token)
            is SearchCursor -> return searchFallback(raw.page)
        }

        var last: ContentException? = null
        try {
            val r = browse("FEwhat_to_watch", authenticated = auth.signedIn.value)
            if (r.items.isNotEmpty()) return r
            Log.w(TAG, "home: InnerTube returned no videos")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "home: InnerTube failed", e)
            last = e.asContentException()
        }
        try {
            val r = fallback.trending()
            if (r.items.isNotEmpty()) return r.copy(next = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContentException) {
            Log.w(TAG, "home: trending kiosk failed", e)
            last = e
        }
        try {
            val r = searchFallback(null)
            if (r.items.isNotEmpty()) return r
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContentException) {
            last = e
        }
        throw last ?: ContentException(ErrorKind.EXTRACTION, detail = "No home content available from any source")
    }

    override suspend fun history(page: PageToken?): ResultPage<BrowseItem> {
        if (!auth.signedIn.value) throw ContentException(ErrorKind.SIGNED_OUT)
        return try {
            val raw = page?.raw
            if (raw is Continuation) continuation(raw.token, authenticated = true)
            else browse("FEhistory", authenticated = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asContentException()
        }
    }

    private suspend fun searchFallback(page: PageToken?): ResultPage<BrowseItem> {
        val r = fallback.search(FALLBACK_QUERY, page)
        return ResultPage(r.items, r.next?.let { PageToken(SearchCursor(it)) })
    }

    private suspend fun browse(browseId: String, authenticated: Boolean): ResultPage<BrowseItem> =
        call(JSONObject().put("context", context()).put("browseId", browseId), authenticated, browseId)

    private suspend fun continuation(token: String, authenticated: Boolean = auth.signedIn.value): ResultPage<BrowseItem> =
        call(JSONObject().put("context", context()).put("continuation", token), authenticated, "continuation")

    private suspend fun call(body: JSONObject, authenticated: Boolean, what: String): ResultPage<BrowseItem> {
        val headers = HashMap<String, String>()
        headers["X-YouTube-Client-Name"] = CLIENT_NAME_ID
        headers["X-YouTube-Client-Version"] = CLIENT_VERSION
        if (authenticated) auth.accessToken()?.let { headers["Authorization"] = "Bearer $it" }

        val res = client.postJson(BROWSE_URL, body, headers)
        if (res.code == 401) {
            if (authenticated) auth.signOut()
            throw ContentException(ErrorKind.SIGNED_OUT, detail = "HTTP 401 from $what")
        }
        if (res.code !in 200..299) {
            throw ContentException(ErrorKind.EXTRACTION, detail = "$what: HTTP ${res.code} ${res.body.take(120)}")
        }
        val json = try {
            JSONObject(res.body)
        } catch (e: JSONException) {
            throw ContentException(ErrorKind.EXTRACTION, e, "$what: response was not JSON")
        }
        val parsed = InnerTubeParser.parse(json)
        Log.i(TAG, "$what: ${parsed.items.size} videos, continuation=${parsed.continuation != null}")
        if (parsed.items.isEmpty()) Log.w(TAG, "$what: nothing parsed; ${InnerTubeParser.describe(json)}")
        return ResultPage(parsed.items, parsed.continuation?.let { PageToken(Continuation(it)) })
    }

    private fun context(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "TVHTML5")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "GB"),
    )

    private fun Exception.asContentException(): ContentException = when (this) {
        is ContentException -> this
        is IOException -> ContentException(ErrorKind.NETWORK, this)
        else -> ContentException(ErrorKind.UNKNOWN, this)
    }

    private companion object {
        const val TAG = "Tube"
        const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"
        const val CLIENT_NAME_ID = "7"
        const val CLIENT_VERSION = "7.20250219.14.00"
        const val FALLBACK_QUERY = "trending"
    }
}
