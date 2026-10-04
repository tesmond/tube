package com.tube.tv.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tube.tv.AppContainer
import com.tube.tv.domain.Profile
import com.tube.tv.ui.TextDim
import com.tube.tv.ui.TvButton

private val AvatarColors = listOf(
    Color(0xFFE5484D), Color(0xFF3E63DD), Color(0xFF30A46C),
    Color(0xFFF76B15), Color(0xFF8E4EC6), Color(0xFF12A594),
)

/** "Who's watching?" - shown at every launch; picking a profile switches account, history and resume positions. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfileScreen(container: AppContainer, onSelected: () -> Unit) {
    val repo = container.profiles
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    var manage by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(profiles.isEmpty()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(adding) { if (adding) runCatching { nameFocus.requestFocus() } }

    fun create() {
        repo.add(newName)
        newName = ""
        adding = false
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (manage) "Manage profiles" else "Who's watching?",
            style = MaterialTheme.typography.displaySmall,
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            items(profiles, key = { it.id }) { profile ->
                val isFirst = profile.id == profiles.first().id
                ProfileCard(
                    name = profile.name,
                    color = AvatarColors[profile.colorIndex.mod(AvatarColors.size)],
                    caption = when {
                        manage && confirmDelete == profile.id -> "Press again to delete"
                        manage -> "Delete"
                        repo.isSignedIn(profile.id) -> "Signed in"
                        else -> "Not signed in"
                    },
                    glyph = profile.name.firstOrNull()?.uppercase() ?: "?",
                    modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                    onClick = {
                        if (manage) {
                            if (confirmDelete == profile.id) {
                                repo.delete(profile.id)
                                confirmDelete = null
                            } else {
                                confirmDelete = profile.id
                            }
                        } else {
                            container.selectProfile(profile.id)
                            onSelected()
                        }
                    },
                )
            }
            if (!manage) {
                item {
                    ProfileCard(
                        name = "Add profile",
                        color = Color(0xFF3A3A4C),
                        caption = "",
                        glyph = "+",
                        modifier = if (profiles.isEmpty()) Modifier.focusRequester(first) else Modifier,
                        onClick = { adding = true },
                    )
                }
            }
        }

        if (adding && !manage) {
            NewProfileForm(
                name = newName,
                onName = { newName = it },
                focus = nameFocus,
                onCreate = ::create,
                onCancel = { adding = false; newName = "" },
                canCancel = profiles.isNotEmpty(),
            )
        }

        if (profiles.isNotEmpty()) {
            TvButton(
                if (manage) "Done" else "Manage profiles",
                { manage = !manage; confirmDelete = null; adding = false },
            )
        }
    }
}

@Composable
private fun ProfileCard(
    name: String,
    color: Color,
    caption: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(200.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF2A2A36),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(120.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            }
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(caption, fontSize = 15.sp, color = TextDim, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NewProfileForm(
    name: String,
    onName: (String) -> Unit,
    focus: FocusRequester,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    canCancel: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 26.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onCreate() }),
            modifier = Modifier
                .width(420.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                        keyboard?.show()
                    }
                    false
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .background(Color(0xFF1B1B24), shape)
                        .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    if (name.isEmpty()) Text("Profile name", color = TextDim, fontSize = 26.sp)
                    inner()
                }
            },
        )
        TvButton("Create", onCreate)
        if (canCancel) TvButton("Cancel", onCancel)
    }
}
