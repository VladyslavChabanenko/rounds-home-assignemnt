package io.github.vladyslavchabanenko.imageloader.sample.ui

/** What the list screen shows. */
sealed interface ImageListUiState {
    data object Loading : ImageListUiState

    data class Content(
        val items: List<ImageListItem>,
    ) : ImageListUiState

    data object Error : ImageListUiState
}

/** One row of the list. */
data class ImageListItem(
    val id: Long,
    val imageUrl: String,
)
