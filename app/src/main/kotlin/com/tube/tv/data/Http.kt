package com.tube.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

internal class HttpResult(val code: Int, val body: String)

/** POSTs JSON and returns status + body without throwing on non-2xx. Cancellable. */
internal suspend fun OkHttpClient.postJson(
    url: String,
    body: JSONObject,
    headers: Map<String, String> = emptyMap(),
): HttpResult = try {
    postJsonBlocking(url, body, headers)
} catch (e: java.io.IOException) {
    // Cancelled requests show up as interrupted I/O; report them as cancellation, not as a failure.
    currentCoroutineContext().ensureActive()
    if (e is java.io.InterruptedIOException) throw CancellationException("interrupted")
    throw e
}

private suspend fun OkHttpClient.postJsonBlocking(
    url: String,
    body: JSONObject,
    headers: Map<String, String>,
): HttpResult = runInterruptible(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .header("User-Agent", TV_USER_AGENT)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    newCall(request).execute().use { HttpResult(it.code, it.body?.string().orEmpty()) }
}
