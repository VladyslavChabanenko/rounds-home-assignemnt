package io.github.vladyslavchabanenko.imageloader.sample.ui

/** What the list screen shows. */
sealed interface ImageListUiState {
    data object Loading : ImageListUiState

    data class Content(
        val items: List<ImageListItem>,
    ) : ImageListUiState

    data object Error : ImageListUiState
}

/** One row of the list. A new [cacheGeneration] makes the row load its image again. */
data class ImageListItem(
    val id: Long,
    val imageUrl: String,
    val cacheGeneration: Int = 0,
)

/** One-off messages for the screen. They are not part of the state, so rotation does not repeat them. */
sealed interface ImageListEvent {
    data object CacheInvalidated : ImageListEvent
}
