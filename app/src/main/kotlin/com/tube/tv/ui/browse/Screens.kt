package com.tube.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.tube.tv.domain.ContentException
import com.tube.tv.domain.DeviceCode
import com.tube.tv.domain.LoginResult
import com.tube.tv.ui.Spinner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tube.tv.AppContainer
import com.tube.tv.domain.BrowseItem
import com.tube.tv.ui.Panel
import com.tube.tv.ui.TextDim
import com.tube.tv.ui.TvButton

@Composable
private fun Header(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        actions()
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onSwitchProfile: () -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onAccount: () -> Unit,
    onOpen: (BrowseItem, List<String>) -> Unit,
) {
    val vm: PagedListViewModel = viewModel()
    val signedIn by container.auth.signedIn.collectAsStateWithLifecycle()
    val profile by container.profiles.active.collectAsStateWithLifecycle()
    // Reloads when the account changes (personalised feed vs public), but not when returning from the player.
    LaunchedEffect(signedIn) { vm.start({ token -> container.feeds.home(token) }, key = signedIn) }
    Column {
        Header(if (signedIn) "Home" else "Trending") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Search", onSearch)
                TvButton("History", onHistory)
                TvButton(if (signedIn) "Account" else "Sign in", onAccount)
                TvButton(profile?.name?.let { "Profile: $it" } ?: "Profiles", onSwitchProfile)
            }
        }
        BrowseGrid(vm, onOpen, emptyText = "Nothing to show right now. Try searching.")
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(container: AppContainer, onOpen: (BrowseItem, List<String>) -> Unit) {
    val vm: PagedListViewModel = viewModel()
    var query by rememberSaveable { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    var lastSubmitted by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        lastSubmitted = q
        keyboard?.hide()
        // Searching only on submit (not per keystroke) avoids needless network requests.
        vm.start({ token -> container.contentRepository.search(q, token) }, force = true)
    }

    LaunchedEffect(Unit) {
        if (!vm.state.value.started) runCatching { fieldFocus.requestFocus() }
    }
    // TV keyboards don't always deliver the Search action, so also search once typing pauses.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || q == lastSubmitted) return@LaunchedEffect
        delay(1_000)
        submit()
    }

    val shape = RoundedCornerShape(10.dp)
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 26.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                            keyboard?.show()
                        }
                        false
                    },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .background(Panel, shape)
                            .border(3.dp, if (fieldFocused) Color.White else Color.Transparent, shape)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        if (query.isEmpty()) Text("Search YouTube", color = TextDim, fontSize = 26.sp)
                        inner()
                    }
                },
            )
            TvButton("Search", ::submit)
        }
        BrowseGrid(vm, onOpen, emptyText = "No results.")
    }
}

@Composable
fun ChannelScreen(
    container: AppContainer,
    channelUrl: String,
    title: String,
    onOpen: (BrowseItem, List<String>) -> Unit,
) {
    val vm: PagedListViewModel = viewModel()
    LaunchedEffect(channelUrl) { vm.start({ token -> container.contentRepository.channelVideos(channelUrl, token) }) }
    Column {
        Header(title)
        BrowseGrid(vm, onOpen, emptyText = "This channel has no videos.")
    }
}

@Composable
fun PlaylistScreen(
    container: AppContainer,
    playlistUrl: String,
    title: String,
    onOpen: (BrowseItem, List<String>) -> Unit,
) {
    val vm: PagedListViewModel = viewModel()
    LaunchedEffect(playlistUrl) { vm.start({ token -> container.contentRepository.playlistVideos(playlistUrl, token) }) }
    Column {
        Header(title)
        BrowseGrid(vm, onOpen, queueFromList = true, emptyText = "This playlist is empty.")
    }
}

@Composable
fun HistoryScreen(
    container: AppContainer,
    onAccount: () -> Unit,
    onOpen: (BrowseItem, List<String>) -> Unit,
) {
    val vm: PagedListViewModel = viewModel()
    val signedIn by container.auth.signedIn.collectAsStateWithLifecycle()
    if (!signedIn) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Sign in to see your YouTube watch history.", style = MaterialTheme.typography.headlineSmall)
            TvButton("Sign in", onAccount, Modifier.focusRequester(focus))
        }
        return
    }
    LaunchedEffect(Unit) { vm.start({ token -> container.feeds.history(token) }) }
    Column {
        Header("History")
        BrowseGrid(vm, onOpen, emptyText = "Your watch history is empty.")
    }
}

private sealed interface AccountUi {
    data object Idle : AccountUi
    data object Working : AccountUi
    data class ShowCode(val code: DeviceCode) : AccountUi
    data class Failed(val message: String) : AccountUi
}

@Composable
fun AccountScreen(container: AppContainer, onHistory: () -> Unit) {
    val auth = container.auth
    val signedIn by auth.signedIn.collectAsStateWithLifecycle()
    var ui by remember { mutableStateOf<AccountUi>(AccountUi.Idle) }
    var attempt by remember { mutableIntStateOf(0) }
    val primary = remember { FocusRequester() }

    // Device-code sign-in. Lives in composition, so leaving the screen cancels the polling.
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        ui = AccountUi.Working
        try {
            val code = auth.startDeviceLogin()
            ui = AccountUi.ShowCode(code)
            val deadline = SystemClock.elapsedRealtime() + code.expiresInSec * 1000L
            var interval = code.intervalSec
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(interval * 1000L)
                when (val result = auth.pollLogin(code)) {
                    LoginResult.Success -> {
                        ui = AccountUi.Idle
                        return@LaunchedEffect
                    }
                    LoginResult.SlowDown -> interval += 5
                    LoginResult.Pending -> Unit
                    is LoginResult.Failed -> {
                        ui = AccountUi.Failed(result.reason)
                        return@LaunchedEffect
                    }
                }
            }
            ui = AccountUi.Failed("The code expired. Try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ui = AccountUi.Failed((e as? ContentException)?.detail ?: e.message ?: "Network problem. Try again.")
        }
    }
    LaunchedEffect(signedIn, ui) { runCatching { primary.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("YouTube account", style = MaterialTheme.typography.headlineMedium)
        container.profiles.active.value?.let { Text("Profile: ${it.name}", color = TextDim) }
        val current = ui
        when {
            signedIn -> {
                Text("You're signed in. Your recommendations and watch history are available.", color = TextDim)
                TvButton("View history", onHistory, Modifier.focusRequester(primary))
                TvButton("Sign out", { auth.signOut() })
            }
            current is AccountUi.Working -> {
                Spinner()
                TvButton("Cancel", { ui = AccountUi.Idle; attempt = 0 }, Modifier.focusRequester(primary))
            }
            current is AccountUi.ShowCode -> {
                Text("On your phone or computer, go to", color = TextDim)
                Text(current.code.verificationUrl, style = MaterialTheme.typography.headlineSmall)
                Text("and enter this code", color = TextDim)
                Text(
                    current.code.userCode,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("Waiting for you to approve…", color = TextDim)
                TvButton("Cancel", { ui = AccountUi.Idle; attempt = 0 }, Modifier.focusRequester(primary))
            }
            else -> {
                if (current is AccountUi.Failed) Text(current.message, color = TextDim)
                TvButton("Sign in with a code", { attempt++ }, Modifier.focusRequester(primary))
            }
        }
    }
}
