package com.tube.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * HTTP data source for googlevideo stream URLs.
 *
 * Two things a stock data source gets wrong, both of which make YouTube answer 403 or throttle:
 *  1. The User-Agent must match the client the URL was minted for (`c=ANDROID`, `c=IOS`, ...).
 *  2. Media is fetched in bounded chunks using the `range=` query parameter (total size comes from
 *     the URL's `clen`) instead of one open-ended request. URLs without `clen` fall back to a
 *     normal HTTP Range request.
 */
@UnstableApi
class YoutubeDataSource(private val client: Call.Factory) : BaseDataSource(/* isNetwork = */ true) {

    private var spec: DataSpec? = null
    private var uri: Uri? = null
    private var response: Response? = null
    private var stream: InputStream? = null

    private var position = 0L   // absolute offset of the next byte we will hand to the caller
    private var end = -1L       // last absolute byte we were asked for (inclusive), -1 = unknown
    private var chunkEnd = -1L  // last absolute byte of the chunk currently open, -1 = not chunked
    private var chunked = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: -1L
        position = dataSpec.position
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length - 1
            clen > 0 -> clen - 1
            else -> -1L
        }
        chunked = end >= 0 && dataSpec.uri.getQueryParameter("range") == null
        if (end >= 0 && position > end) throw IOException("Position $position is past end $end")

        openRequest()
        opened = true
        transferStarted(dataSpec)
        return if (end >= 0) end - position + 1 else C.LENGTH_UNSET.toLong()
    }

    private fun openRequest() {
        val base = uri!!
        val builder = Request.Builder().header("User-Agent", userAgentFor(base))
        if (chunked) {
            chunkEnd = minOf(end, position + CHUNK_BYTES - 1)
            val url = base.buildUpon().appendQueryParameter("range", "$position-$chunkEnd").build()
            builder.url(url.toString())
        } else {
            chunkEnd = -1L
            builder.url(base.toString())
            if (position > 0) builder.header("Range", "bytes=$position-")
        }
        val r = client.newCall(builder.build()).execute()
        if (!r.isSuccessful) {
            val code = r.code
            val message = r.message
            r.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code, message, null, emptyMap(), spec!!, ByteArray(0),
            )
        }
        response = r
        stream = r.body!!.byteStream()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end >= 0 && position > end) return C.RESULT_END_OF_INPUT

        if (chunked && position > chunkEnd) {
            closeStream()
            openRequest()
        }
        val limit = if (chunked) minOf(length.toLong(), chunkEnd - position + 1).toInt() else length
        val n = stream!!.read(buffer, offset, limit)
        if (n == -1) {
            // Short chunk or end of a non-chunked body.
            if (chunked && position <= end) {
                closeStream()
                openRequest()
                return read(buffer, offset, length)
            }
            return C.RESULT_END_OF_INPUT
        }
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            closeStream()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
            uri = null
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
    }

    private fun userAgentFor(u: Uri): String = when (u.getQueryParameter("c")) {
        "ANDROID" -> YoutubeParsingHelper.getAndroidUserAgent(null)
        "IOS" -> YoutubeParsingHelper.getIosUserAgent(null)
        else -> DEFAULT_USER_AGENT
    }

    class Factory(private val client: Call.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeDataSource(client)
    }

    private companion object {
        const val CHUNK_BYTES = 4L * 1024 * 1024
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
