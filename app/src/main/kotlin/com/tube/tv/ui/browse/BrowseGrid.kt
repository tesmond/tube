package com.tube.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.tube.tv.domain.BrowseItem
import com.tube.tv.domain.VideoItem
import com.tube.tv.ui.BrowseCard
import com.tube.tv.ui.ErrorPanel
import com.tube.tv.ui.Spinner
import com.tube.tv.ui.TextDim
import com.tube.tv.ui.TvButton
import com.tube.tv.ui.isRetriable

/**
 * Shared lazy grid for every list screen. Keyed items keep focus stable while pages append,
 * and the last-focused card is restored when returning from the player.
 *
 * @param queueFromList when true, playing a video queues the other videos in this list
 *        (used for playlists); otherwise a single video is played.
 */
@Composable
fun BrowseGrid(
    vm: PagedListViewModel,
    onOpen: (BrowseItem, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    queueFromList: Boolean = false,
    emptyText: String = "Nothing to show yet.",
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val initialFocus = remember { FocusRequester() }
    val restoreId = remember { vm.lastFocusedId }

    val hasItems = state.items.isNotEmpty()
    val initialId = remember(hasItems) {
        restoreId?.takeIf { id -> state.items.any { it.id == id } } ?: state.items.firstOrNull()?.id
    }
    LaunchedEffect(hasItems) {
        if (hasItems) runCatching { initialFocus.requestFocus() }
    }

    val size by rememberUpdatedState(state.items.size)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= 0 && last >= size - 8) vm.loadMore() }
    }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(300.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.id }, contentType = { it::class }) { item ->
                val focusMod = if (item.id == initialId) Modifier.focusRequester(initialFocus) else Modifier
                BrowseCard(
                    item = item,
                    modifier = focusMod.onFocusChanged { if (it.hasFocus) vm.lastFocusedId = item.id },
                    onClick = {
                        vm.lastFocusedId = item.id
                        val queue = if (queueFromList) {
                            state.items.filterIsInstance<VideoItem>().map { it.videoId }.take(200)
                        } else {
                            emptyList()
                        }
                        onOpen(item, queue)
                    },
                )
            }
            if (state.loading && hasItems) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() }
                }
            }
            if (state.error != null && hasItems) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TvButton("Couldn't load more — Retry", vm::retry)
                    }
                }
            }
        }

        if (!hasItems) {
            val error = state.error
            when {
                error != null -> ErrorPanel(error, if (error.isRetriable()) vm::retry else null)
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                state.started -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyText, color = TextDim)
                }
            }
        }
    }
}
