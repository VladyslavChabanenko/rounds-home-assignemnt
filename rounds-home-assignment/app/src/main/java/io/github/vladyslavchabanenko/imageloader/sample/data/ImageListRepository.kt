package io.github.vladyslavchabanenko.imageloader.sample.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Loads and parses the image list. Failures propagate to the caller. */
class ImageListRepository(
    private val dataSource: ImageListDataSource,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun loadImages(): List<ImageEntry> = withContext(ioDispatcher) { ImageListParser.parse(dataSource.fetchImageListJson()) }
}
