package com.tube.tv.compat

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Stand-ins for URLDecoder.decode/URLEncoder.encode(String, Charset), which need API 33.
 * Library calls to the originals are rewritten to these at build time (see buildSrc/UrlCharsetCompatFactory).
 */
object UrlCompat {
    @JvmStatic
    fun decode(s: String, charset: Charset): String = URLDecoder.decode(s, charset.name())

    @JvmStatic
    fun encode(s: String, charset: Charset): String = URLEncoder.encode(s, charset.name())
}
