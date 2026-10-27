package io.github.vladyslavchabanenko.imageloader.sample.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vladyslavchabanenko.imageloader.sample.data.HttpImageListDataSource
import io.github.vladyslavchabanenko.imageloader.sample.data.ImageListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImageListViewModel(
    private val repository: ImageListRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ImageListUiState>(ImageListUiState.Loading)
    val uiState: StateFlow<ImageListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onRetry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.value = ImageListUiState.Loading
        loadJob =
            viewModelScope.launch {
                _uiState.value =
                    try {
                        val items = repository.loadImages().map { ImageListItem(it.id, it.imageUrl) }
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
                initializer { ImageListViewModel(ImageListRepository(HttpImageListDataSource(), Dispatchers.IO)) }
            }
    }
}
