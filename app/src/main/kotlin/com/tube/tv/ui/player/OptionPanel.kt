package com.tube.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tube.tv.ui.TvButton
import kotlinx.coroutines.android.awaitFrame

data class Option(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/** Side sheet used for quality / speed / captions / audio / sleep / chapters. Traps D-pad focus. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OptionPanel(title: String, options: List<Option>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    val selected = options.indexOfFirst { it.selected }.coerceAtLeast(0)

    LaunchedEffect(Unit) {
        if (options.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(selected)
        awaitFrame()
        runCatching { requesters[selected].requestFocus() }
    }

    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Color(0xF2121218))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(options) { i, option ->
                TvButton(
                    text = (if (option.selected) "● " else "") + option.label,
                    onClick = {
                        option.onSelect()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(requesters[i]),
                    selected = option.selected,
                )
            }
        }
    }
}
