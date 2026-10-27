@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.vladyslavchabanenko.imageloader.sample.ui

import io.github.vladyslavchabanenko.imageloader.sample.data.ImageListDataSource
import io.github.vladyslavchabanenko.imageloader.sample.data.ImageListRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ImageListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class CountingDataSource(
        var json: String? = TWO_ENTRIES,
    ) : ImageListDataSource {
        var calls = 0
            private set

        override fun fetchImageListJson(): String {
            calls++
            return json ?: throw IOException("offline")
        }
    }

    private val dataSource = CountingDataSource()

    private fun createViewModel() = ImageListViewModel(ImageListRepository(dataSource, mainDispatcherRule.testDispatcher))

    @Test
    fun startsLoadingThenShowsTheItemsInSourceOrder() =
        runTest {
            val viewModel = createViewModel()
            assertEquals(ImageListUiState.Loading, viewModel.uiState.value)

            advanceUntilIdle()

            assertEquals(
                ImageListUiState.Content(
                    listOf(
                        ImageListItem(0, "https://example.test/a.jpg"),
                        ImageListItem(1, "https://example.test/b.jpg"),
                    ),
                ),
                viewModel.uiState.value,
            )
        }

    @Test
    fun aFailingFetchShowsErrorAndRetryGoesThroughLoadingToContent() =
        runTest {
            dataSource.json = null
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(ImageListUiState.Error, viewModel.uiState.value)

            dataSource.json = TWO_ENTRIES
            viewModel.onRetry()
            assertEquals(ImageListUiState.Loading, viewModel.uiState.value)
            advanceUntilIdle()

            assertEquals(2, (viewModel.uiState.value as ImageListUiState.Content).items.size)
        }

    @Test
    fun aSecondCollectorGetsTheCurrentContentWithoutAnotherFetch() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            val firstCollector = viewModel.uiState.first()

            val secondCollector = viewModel.uiState.first()

            assertEquals(firstCollector, secondCollector)
            assertEquals(2, (secondCollector as ImageListUiState.Content).items.size)
            assertEquals(1, dataSource.calls)
        }

    private companion object {
        const val TWO_ENTRIES =
            """[{"id": 0, "imageUrl": "https://example.test/a.jpg"},""" +
                """ {"id": 1, "imageUrl": "https://example.test/b.jpg"}]"""
    }
}
