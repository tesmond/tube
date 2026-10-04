package com.tube.tv.ui.player

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tube.tv.playback.Phase
import com.tube.tv.playback.PlaybackManager
import com.tube.tv.ui.Spinner
import com.tube.tv.ui.TvButton
import com.tube.tv.ui.isRetriable
import com.tube.tv.ui.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/**
 * Remote handling contract (ADR section 7):
 *  - Play/Pause, FF/Rewind, Next/Previous media keys always work (preview handler, even with controls hidden).
 *  - Controls hidden: Left/Right scrub, OK/Up/Down show controls.
 *  - Controls shown: normal D-pad focus; Left/Right on the seek bar scrub.
 *  - Back closes the option panel, then the controls, then leaves playback.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(manager: PlaybackManager, videoId: String, onExit: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val player by manager.playerFlow.collectAsStateWithLifecycle()

    // Start playback, and leave when the manager tears itself down (e.g. after a long time in background).
    LaunchedEffect(videoId) {
        manager.play(videoId)
        manager.state.first { it.phase == Phase.IDLE }
        onExit()
    }
    // Leaving the screen always releases the player, session and decoders.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { manager.stop() } }

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var hudStamp by remember { mutableIntStateOf(0) }
    var hudVisible by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun poke() {
        interaction++
        controlsVisible = true
    }

    fun scrubBy(delta: Long) {
        val dur = manager.durationMs()
        if (state.isLive || dur <= 0) return
        val base = scrubMs ?: manager.positionMs()
        scrubMs = (base + delta).coerceIn(0, dur)
        hudStamp++
        hudVisible = true
        interaction++
    }

    // Debounced commit: holding a key moves a cursor; the player only seeks once input settles.
    LaunchedEffect(scrubMs) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(400)
        manager.seekTo(target)
        scrubMs = null
    }
    LaunchedEffect(hudStamp) {
        if (hudStamp == 0) return@LaunchedEffect
        delay(1_800)
        hudVisible = false
    }

    // Auto-hide while playing and nothing is open.
    LaunchedEffect(controlsVisible, interaction, state.isPlaying, panel) {
        if (controlsVisible && state.isPlaying && panel == null) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.ENDED) controlsVisible = true
    }

    // There is always somewhere for focus to live.
    LaunchedEffect(controlsVisible, panel, state.phase) {
        if (state.phase == Phase.ERROR || panel != null) return@LaunchedEffect
        runCatching { if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus() }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            controlsVisible && state.phase != Phase.ERROR -> controlsVisible = false
            else -> onExit()
        }
    }

    val position by produceState(0L, controlsVisible) {
        if (controlsVisible) {
            while (isActive) {
                value = manager.positionMs()
                delay(500)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause -> { manager.togglePlay(); true }
                    Key.MediaPlay -> { manager.setPlaying(true); true }
                    Key.MediaPause -> { manager.setPlaying(false); true }
                    Key.MediaFastForward -> { scrubBy(30_000); true }
                    Key.MediaRewind -> { scrubBy(-10_000); true }
                    Key.MediaNext -> { manager.next(); true }
                    Key.MediaPrevious -> { manager.previous(); true }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                // Only reached for keys the focused child did not consume.
                if (e.type != KeyEventType.KeyDown || controlsVisible || panel != null) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { scrubBy(-seekStep(e.nativeKeyEvent.repeatCount)); true }
                    Key.DirectionRight -> { scrubBy(seekStep(e.nativeKeyEvent.repeatCount)); true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown -> { poke(); true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        // Native video surface; decoding is hardware-backed by MediaCodec. No web view anywhere.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.4f)
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
        )

        if (state.phase == Phase.RESOLVING || state.phase == Phase.BUFFERING) {
            Box(Modifier.align(Alignment.Center)) { Spinner() }
        }

        if (!controlsVisible && !state.isLive && (state.phase == Phase.READY || state.phase == Phase.BUFFERING)) {
            MiniProgress(manager, Modifier.align(Alignment.BottomCenter))
        }

        if (hudVisible && !controlsVisible) {
            SeekHud(scrubMs ?: position, manager.durationMs(), Modifier.align(Alignment.BottomCenter))
        }

        if (controlsVisible && state.phase != Phase.ERROR) {
            ControlsOverlay(
                state = state,
                manager = manager,
                positionMs = position,
                scrubMs = scrubMs,
                onScrub = { scrubBy(it) },
                onPanel = { panel = it },
                poke = ::poke,
                playFocus = playFocus,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        panel?.let { p ->
            OptionPanel(
                title = panelTitle(p),
                options = optionsFor(p, state, manager),
                onDismiss = { panel = null; poke() },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        val error = state.error
        if (state.phase == Phase.ERROR && error != null) {
            val firstAction = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { firstAction.requestFocus() } }
            Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(error.userMessage(), style = MaterialTheme.typography.headlineSmall)
                    error.detail?.let { Text(it, fontSize = 16.sp, color = Color(0xFFB4B4C0)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (error.isRetriable()) {
                            TvButton("Retry", manager::retry, Modifier.focusRequester(firstAction))
                            TvButton("Back", onExit)
                        } else {
                            TvButton("Back", onExit, Modifier.focusRequester(firstAction))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekHud(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(bottom = 64.dp)
            .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(formatMs(positionMs), style = MaterialTheme.typography.headlineSmall)
        Text("/ ${formatMs(durationMs)}", fontSize = 22.sp, color = Color(0xFFB4B4C0))
    }
}

/** Slim progress line shown while the full controls are hidden. Ticks once a second. */
@Composable
private fun MiniProgress(manager: PlaybackManager, modifier: Modifier = Modifier) {
    val fraction by produceState(0f) {
        while (isActive) {
            val d = manager.durationMs()
            value = if (d > 0) (manager.positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(1_000)
        }
    }
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Color(0xFFFF3D3D)))
    }
}
