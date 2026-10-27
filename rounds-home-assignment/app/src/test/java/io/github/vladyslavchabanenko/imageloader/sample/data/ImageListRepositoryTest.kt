package io.github.vladyslavchabanenko.imageloader.sample.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageListRepositoryTest {
    @Test
    fun mapsTheJsonToEntries() =
        runTest {
            val json = """[{"id": 0, "imageUrl": "https://example.test/a.jpg"}, {"id": 1, "imageUrl": "https://example.test/b.jpg"}]"""
            val repository = ImageListRepository({ json }, StandardTestDispatcher(testScheduler))

            val entries = repository.loadImages()

            assertEquals(
                listOf(
                    ImageEntry(0, "https://example.test/a.jpg"),
                    ImageEntry(1, "https://example.test/b.jpg"),
                ),
                entries,
            )
        }

    @Test
    fun anIOExceptionFromTheDataSourcePropagates() =
        runTest {
            val repository = ImageListRepository({ throw IOException("offline") }, StandardTestDispatcher(testScheduler))

            val failure = runCatching { repository.loadImages() }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("offline", failure?.message)
        }

    @Test
    fun malformedJsonThrows() =
        runTest {
            val repository = ImageListRepository({ "[{" }, StandardTestDispatcher(testScheduler))

            val failure = runCatching { repository.loadImages() }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }
}
