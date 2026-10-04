package com.tube.tv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tube.tv.domain.BrowseItem
import com.tube.tv.domain.ContentException
import com.tube.tv.domain.ErrorKind
import com.tube.tv.domain.PageToken
import com.tube.tv.domain.ResultPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PagedState(
    val items: List<BrowseItem> = emptyList(),
    val loading: Boolean = false,
    val error: ContentException? = null,
    val endReached: Boolean = false,
    val started: Boolean = false,
)

typealias PageLoader = suspend (PageToken?) -> ResultPage<BrowseItem>

/**
 * One instance per screen (scoped to its nav entry). Replacing the loader (a new search) cancels
 * the in-flight request; leaving the screen cancels via viewModelScope (ADR section 13).
 */
class PagedListViewModel : ViewModel() {
    private val _state = MutableStateFlow(PagedState())
    val state: StateFlow<PagedState> = _state.asStateFlow()

    private var loader: PageLoader? = null
    private var next: PageToken? = null
    private var job: Job? = null

    /** Survives navigation to the player so focus can return to the same card. */
    var lastFocusedId: String? = null

    fun start(loader: PageLoader, force: Boolean = false) {
        if (this.loader != null && !force) return
        job?.cancel()
        this.loader = loader
        next = null
        lastFocusedId = null
        _state.value = PagedState(started = true)
        load(first = true)
    }

    fun loadMore() = load(first = false)

    fun retry() = load(first = _state.value.items.isEmpty())

    private fun load(first: Boolean) {
        val l = loader ?: return
        val s = _state.value
        if (s.loading || (!first && s.endReached)) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = l(if (first) null else next)
                next = page.next
                _state.update { cur ->
                    val merged = LinkedHashMap<String, BrowseItem>()
                    if (!first) cur.items.forEach { merged[it.id] = it }
                    page.items.forEach { merged.putIfAbsent(it.id, it) }
                    val items = merged.values.take(MAX_ITEMS)
                    cur.copy(
                        items = items,
                        loading = false,
                        endReached = page.next == null || page.items.isEmpty() || items.size >= MAX_ITEMS,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ContentException) {
                _state.update { it.copy(loading = false, error = e) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = ContentException(ErrorKind.UNKNOWN, e)) }
            }
        }
    }

    private companion object {
        /** Bounds list memory during very long browsing sessions. */
        const val MAX_ITEMS = 240
    }
}
