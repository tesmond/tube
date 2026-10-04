package com.tube.tv.data

import com.tube.tv.domain.BrowseItem
import com.tube.tv.domain.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant reader for InnerTube browse responses. Instead of following one fixed path (which
 * YouTube reshuffles often) it walks the whole tree and lifts out every video renderer it knows.
 */
internal object InnerTubeParser {

    class Parsed(val items: List<BrowseItem>, val continuation: String?)

    private val VIDEO_RENDERERS = setOf(
        "tileRenderer", "videoRenderer", "compactVideoRenderer", "gridVideoRenderer",
        "playlistVideoRenderer", "videoWithContextRenderer",
    )

    fun parse(root: JSONObject): Parsed {
        val items = ArrayList<BrowseItem>()
        var continuation: String? = null

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> for (key in node.keys()) {
                    val value = node.opt(key)
                    if (key in VIDEO_RENDERERS && value is JSONObject) {
                        video(value)?.let { items.add(it) }
                    } else if (key == "continuationItemRenderer" && value is JSONObject) {
                        if (continuation == null) {
                            continuation = value.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                                ?.takeIf { it.isNotEmpty() }
                        }
                    } else {
                        walk(value)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return Parsed(items, continuation)
    }

    /** Short summary of an unrecognised response, for error details. */
    fun describe(root: JSONObject): String =
        "keys=" + root.keys().asSequence().take(8).joinToString(",")

    private fun video(v: JSONObject): VideoItem? {
        val contentType = v.optString("contentType")
        if (contentType.isNotEmpty() && contentType != "TILE_CONTENT_TYPE_VIDEO") return null

        val id = v.optString("videoId").ifEmpty {
            v.optJSONObject("onSelectCommand")?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
        }
        if (id.isEmpty()) return null

        val tileMeta = v.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val lines = tileMeta?.optJSONArray("lines")
        val title = text(v.optJSONObject("title")) ?: text(tileMeta?.optJSONObject("title")) ?: return null

        val channel = text(v.optJSONObject("shortBylineText"))
            ?: text(v.optJSONObject("ownerText"))
            ?: text(v.optJSONObject("longBylineText"))
            ?: tileLine(lines, 0)
            ?: ""
        val published = text(v.optJSONObject("publishedTimeText")) ?: tileLine(lines, 1)

        val header = v.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val thumbs = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: header?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")

        var durationText = text(v.optJSONObject("lengthText"))
        var live = false
        val overlays = header?.optJSONArray("thumbnailOverlays") ?: v.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val status = overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
                if (status.optString("style") == "LIVE") live = true
                if (durationText == null) durationText = text(status.optJSONObject("text"))
            }
        }

        return VideoItem(
            videoId = id,
            title = title,
            channelName = channel,
            durationSeconds = if (live) -1 else parseDuration(durationText),
            thumbnailUrl = pickThumbnail(thumbs),
            published = published,
            isLive = live,
        )
    }

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /** Joins the item texts of one metadata line ("1.2M views • 3 days ago"). */
    private fun tileLine(lines: JSONArray?, index: Int): String? {
        val items = lines?.optJSONObject(index)?.optJSONObject("lineRenderer")?.optJSONArray("items") ?: return null
        val parts = ArrayList<String>()
        for (i in 0 until items.length()) {
            text(items.optJSONObject(i)?.optJSONObject("lineItemRenderer")?.optJSONObject("text"))?.let { parts.add(it) }
        }
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    private fun pickThumbnail(thumbs: JSONArray?): String? {
        if (thumbs == null || thumbs.length() == 0) return null
        var best: String? = null
        var bestWidth = Int.MAX_VALUE
        var largest: String? = null
        var largestWidth = -1
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val url = t.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val w = t.optInt("width", 0)
            if (w >= 320 && w < bestWidth) { best = url; bestWidth = w }
            if (w > largestWidth) { largest = url; largestWidth = w }
        }
        return (best ?: largest)?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun parseDuration(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        var total = 0L
        for (part in s.trim().split(':')) total = total * 60 + (part.toLongOrNull() ?: return 0)
        return total
    }
}
