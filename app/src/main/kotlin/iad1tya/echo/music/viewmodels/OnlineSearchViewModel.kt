package iad1tya.echo.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echo.innertube.YouTube
import com.echo.innertube.models.filterExplicit
import com.echo.innertube.models.filterVideoSongs
import com.echo.innertube.models.filterYoutubeShorts
import com.echo.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import iad1tya.echo.music.constants.HideExplicitKey
import iad1tya.echo.music.constants.HideVideoSongsKey
import iad1tya.echo.music.constants.HideYoutubeShortsKey
import iad1tya.echo.music.models.ItemsPage
import iad1tya.echo.music.utils.SaavnAudioResolver
import iad1tya.echo.music.utils.dataStore
import iad1tya.echo.music.utils.get
import iad1tya.echo.music.utils.reportException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = savedStateHandle.get<String>("query")!!
    var autoplay by mutableStateOf(savedStateHandle.get<Boolean>("autoplay") ?: false)
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()
    val saavnResults = MutableStateFlow<List<SaavnAudioResolver.SaavnSearchResult>>(emptyList())

    private var saavnRequestRunning = false

    init {
        viewModelScope.launch { loadSaavnResults() }
        viewModelScope.launch {
            filter.collect { currentFilter ->
                if ((currentFilter == null || currentFilter == YouTube.SearchFilter.FILTER_SONG) && saavnResults.value.isEmpty()) {
                    loadSaavnResults()
                }

                if (currentFilter == null) {
                    if (summaryPage == null) {
                        YouTube.searchSummary(query)
                            .onSuccess {
                                summaryPage = it.filterExplicit(
                                    context.dataStore.get(HideExplicitKey, false),
                                ).filterVideoSongs(
                                    context.dataStore.get(HideVideoSongsKey, false),
                                ).filterYoutubeShorts(
                                    context.dataStore.get(HideYoutubeShortsKey, false),
                                )
                            }
                            .onFailure { reportException(it) }
                    }
                } else {
                    if (viewStateMap[currentFilter.value] == null) {
                        YouTube.search(query, currentFilter)
                            .onSuccess { result ->
                                viewStateMap[currentFilter.value] = ItemsPage(
                                    result.items
                                        .distinctBy { it.id }
                                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        .filterYoutubeShorts(context.dataStore.get(HideYoutubeShortsKey, false)),
                                    result.continuation,
                                )
                            }
                            .onFailure { reportException(it) }
                    }
                }
            }
        }
    }

    private suspend fun loadSaavnResults() {
        if (saavnRequestRunning) return
        saavnRequestRunning = true
        try {
            SaavnAudioResolver.searchSongs(query, limit = 18)
                .onSuccess { results ->
                    saavnResults.value = results.distinctBy { it.sourceSongId }
                }
                .onFailure {
                    reportException(it)
                    saavnResults.value = emptyList()
                }
        } finally {
            saavnRequestRunning = false
        }
    }

    fun loadMore() {
        val currentFilter = filter.value?.value
        viewModelScope.launch {
            if (currentFilter == null) return@launch
            val viewState = viewStateMap[currentFilter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[currentFilter] = ItemsPage(
                    (viewState.items + searchResult.items).distinctBy { it.id },
                    searchResult.continuation,
                )
            }
        }
    }
}
