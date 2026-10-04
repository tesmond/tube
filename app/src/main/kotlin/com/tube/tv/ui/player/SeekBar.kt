package com.tube.tv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.tube.tv.domain.Chapter
import com.tube.tv.ui.Accent

/** Seek step grows while a D-pad key is held: 10s, then 30s, then 60s. */
internal fun seekStep(repeatCount: Int): Long = when {
    repeatCount < 3 -> 10_000L
    repeatCount < 10 -> 30_000L
    else -> 60_000L
}

@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<Chapter>,
    seekable: Boolean,
    onScrub: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(32.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft ->
                        if (seekable) { onScrub(-seekStep(e.nativeKeyEvent.repeatCount)); true } else false
                    Key.DirectionRight ->
                        if (seekable) { onScrub(seekStep(e.nativeKeyEvent.repeatCount)); true } else false
                    Key.DirectionCenter, Key.Enter -> { onToggle(); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (focused) 12.dp else 6.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(Color(0x55FFFFFF), size = size, cornerRadius = radius)
            val dur = durationMs.coerceAtLeast(1)
            val buffered = (bufferedMs.toFloat() / dur).coerceIn(0f, 1f)
            val played = if (seekable) (positionMs.toFloat() / dur).coerceIn(0f, 1f) else 1f
            drawRoundRect(Color(0x99FFFFFF), size = Size(size.width * buffered, size.height), cornerRadius = radius)
            drawRoundRect(Accent, size = Size(size.width * played, size.height), cornerRadius = radius)
            if (seekable) {
                for (c in chapters) {
                    if (c.startMs <= 0) continue
                    val x = size.width * (c.startMs.toFloat() / dur).coerceIn(0f, 1f)
                    drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                }
            }
            if (focused) drawCircle(Color.White, radius = size.height * 1.1f, center = Offset(size.width * played, size.height / 2))
        }
    }
}
