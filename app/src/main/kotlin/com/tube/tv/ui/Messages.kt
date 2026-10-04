package com.tube.tv.ui

import com.tube.tv.domain.ContentException
import com.tube.tv.domain.ErrorKind

fun ContentException.userMessage(): String = when (kind) {
    ErrorKind.UNAVAILABLE -> "This video isn't available."
    ErrorKind.AGE_RESTRICTED -> "This video is age-restricted and can't be played here."
    ErrorKind.REGION_RESTRICTED -> "This video isn't available in your region."
    ErrorKind.PRIVATE -> "This video is private."
    ErrorKind.REMOVED -> "This video has been removed."
    ErrorKind.NETWORK -> "Network problem. Check your connection."
    ErrorKind.EXTRACTION -> "Couldn't get the video stream. Try again in a moment."
    ErrorKind.UNSUPPORTED_CODEC -> "This device can't decode any available format of this video."
    ErrorKind.STREAM_EXPIRED -> "The stream link expired."
    ErrorKind.UNKNOWN -> "Something went wrong."
}

fun ContentException.isRetriable(): Boolean = when (kind) {
    ErrorKind.NETWORK, ErrorKind.EXTRACTION, ErrorKind.STREAM_EXPIRED, ErrorKind.UNKNOWN -> true
    else -> false
}

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds < 0) return "LIVE"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
