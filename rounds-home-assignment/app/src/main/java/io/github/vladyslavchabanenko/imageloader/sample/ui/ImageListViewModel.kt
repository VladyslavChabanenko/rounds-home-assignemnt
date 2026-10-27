package io.github.vladyslavchabanenko.imageloader.sample.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vladyslavchabanenko.imageloader.ImageLoader
import io.github.vladyslavchabanenko.imageloader.sample.data.HttpImageListDataSource
import io.github.vladyslavchabanenko.imageloader.sample.data.ImageListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ImageListViewModel(
    private val repository: ImageListRepository,
    private val invalidateImageCache: () -> Unit,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ImageListUiState>(ImageListUiState.Loading)
    val uiState: StateFlow<ImageListUiState> = _uiState.asStateFlow()

    private val _events = Channel<ImageListEvent>(Channel.BUFFERED)
    val events: Flow<ImageListEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var cacheGeneration = 0

    init {
        load()
    }

    fun onRetry() {
        load()
    }

    fun onInvalidateCacheClicked() {
        invalidateImageCache()
        cacheGeneration++
        _uiState.update { state ->
            if (state is ImageListUiState.Content) {
                ImageListUiState.Content(state.items.map { it.copy(cacheGeneration = cacheGeneration) })
            } else {
                state
            }
        }
        _events.trySend(ImageListEvent.CacheInvalidated)
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.value = ImageListUiState.Loading
        loadJob =
            viewModelScope.launch {
                _uiState.value =
                    try {
                        val items = repository.loadImages().map { ImageListItem(it.id, it.imageUrl, cacheGeneration) }
                        ImageListUiState.Content(items)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ImageListUiState.Error
                    }
            }
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ImageListViewModel(
                        ImageListRepository(HttpImageListDataSource(), Dispatchers.IO),
                        ImageLoader.get(checkNotNull(this[APPLICATION_KEY]))::invalidateCache,
                    )
                }
            }
    }
}
