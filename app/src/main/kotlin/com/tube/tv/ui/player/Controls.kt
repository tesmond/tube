package com.tube.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tube.tv.playback.Phase
import com.tube.tv.playback.PlaybackManager
import com.tube.tv.playback.PlaybackState
import com.tube.tv.playback.QUALITY_AUTO
import com.tube.tv.ui.TextDim
import com.tube.tv.ui.TvButton
import com.tube.tv.ui.formatDuration

enum class Panel { QUALITY, SPEED, CAPTIONS, AUDIO, SLEEP, CHAPTERS }

internal fun formatMs(ms: Long): String = formatDuration(ms / 1000)

@Composable
fun ControlsOverlay(
    state: PlaybackState,
    manager: PlaybackManager,
    positionMs: Long,
    scrubMs: Long?,
    onScrub: (Long) -> Unit,
    onPanel: (Panel) -> Unit,
    poke: () -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val duration = manager.durationMs()
    val shown = scrubMs ?: positionMs

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.channel, color = TextDim, fontSize = 18.sp, maxLines = 1)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.isLive) "LIVE" else formatMs(shown), fontSize = 18.sp, modifier = Modifier.width(96.dp))
            SeekBar(
                positionMs = shown,
                durationMs = duration,
                bufferedMs = manager.bufferedMs(),
                chapters = state.chapters,
                seekable = !state.isLive && duration > 0,
                onScrub = onScrub,
                onToggle = { manager.togglePlay(); poke() },
                modifier = Modifier.weight(1f),
            )
            Text(
                if (state.isLive) "" else formatMs(duration),
                fontSize = 18.sp,
                modifier = Modifier.width(96.dp).padding(start = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasPrevious) item { TvButton("Previous", { manager.previous(); poke() }) }
            item {
                val label = when {
                    state.phase == Phase.ENDED -> "Replay"
                    state.isPlaying -> "Pause"
                    else -> "Play"
                }
                TvButton(label, { manager.togglePlay(); poke() }, Modifier.focusRequester(playFocus))
            }
            if (!state.isLive) {
                item { TvButton("−10s", { onScrub(-10_000) }) }
                item { TvButton("+10s", { onScrub(10_000) }) }
            }
            if (state.hasNext) item { TvButton("Next", { manager.next(); poke() }) }
            if (state.qualities.isNotEmpty()) item {
                val label = if (state.quality == QUALITY_AUTO) {
                    "Quality: Auto" + (state.activeHeight?.let { " (${it}p)" } ?: "")
                } else "Quality: ${state.quality}p"
                TvButton(label, { onPanel(Panel.QUALITY) })
            }
            item { TvButton("Speed: ${speedLabel(state.speed)}", { onPanel(Panel.SPEED) }) }
            if (state.subtitles.isNotEmpty()) item {
                val on = state.subtitleIndex >= 0
                TvButton("Captions: " + if (on) state.subtitles[state.subtitleIndex].label else "Off", { onPanel(Panel.CAPTIONS) })
            }
            if (state.audioTracks.size > 1) item { TvButton("Audio", { onPanel(Panel.AUDIO) }) }
            item { TvButton("Loop: " + if (state.loop) "On" else "Off", { manager.setLoop(!state.loop); poke() }) }
            item { TvButton("Sleep" + if (state.sleepAtMs != null) ": On" else "", { onPanel(Panel.SLEEP) }) }
            if (state.chapters.isNotEmpty()) item { TvButton("Chapters", { onPanel(Panel.CHAPTERS) }) }
        }
    }
}

internal fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
internal val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60, 90)

internal fun optionsFor(panel: Panel, state: PlaybackState, manager: PlaybackManager): List<Option> = when (panel) {
    Panel.QUALITY -> buildList {
        add(Option("Auto", state.quality == QUALITY_AUTO) { manager.setQuality(QUALITY_AUTO) })
        state.qualities.forEach { h -> add(Option("${h}p", state.quality == h) { manager.setQuality(h) }) }
    }
    Panel.SPEED -> SPEEDS.map { s -> Option(speedLabel(s), state.speed == s) { manager.setSpeed(s) } }
    Panel.CAPTIONS -> buildList {
        add(Option("Off", state.subtitleIndex < 0) { manager.setSubtitle(-1) })
        state.subtitles.forEachIndexed { i, t ->
            val label = t.label + if (t.autoGenerated) " (auto)" else ""
            add(Option(label, state.subtitleIndex == i) { manager.setSubtitle(i) })
        }
    }
    Panel.AUDIO -> state.audioTracks.map { t ->
        Option(t.label, state.audioTrackId == t.id) { manager.setAudioTrack(t.id) }
    }
    Panel.SLEEP -> SLEEP_MINUTES.map { m ->
        Option(
            if (m == 0) "Off" else "$m minutes",
            selected = if (m == 0) state.sleepAtMs == null else false,
        ) { manager.setSleepTimer(m) }
    }
    Panel.CHAPTERS -> state.chapters.mapIndexed { i, c ->
        Option("${formatMs(c.startMs)}  ${c.title}", selected = false) { manager.seekToChapter(i) }
    }
}

internal fun panelTitle(panel: Panel): String = when (panel) {
    Panel.QUALITY -> "Video quality"
    Panel.SPEED -> "Playback speed"
    Panel.CAPTIONS -> "Captions"
    Panel.AUDIO -> "Audio track"
    Panel.SLEEP -> "Sleep timer"
    Panel.CHAPTERS -> "Chapters"
}
