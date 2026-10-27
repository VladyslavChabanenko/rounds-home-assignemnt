package io.github.vladyslavchabanenko.imageloader.sample.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageListParserTest {
    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("image_list.json")) { "Missing image_list.json fixture" }
            .bufferedReader()
            .use { it.readText() }

    private fun entry(id: Long) = ImageEntry(id, "https://example.test/$id.jpg")

    @Test
    fun fixtureParsesToFiftyEntriesInOrderKeepingDuplicates() {
        val entries = ImageListParser.parse(fixture())

        assertEquals(50, entries.size)
        assertEquals((0L..49L).toList(), entries.map { it.id })
        assertEquals(45, entries.map { it.imageUrl }.toSet().size)
    }

    @Test
    fun emptyArrayGivesAnEmptyList() {
        assertTrue(ImageListParser.parse("[]").isEmpty())
    }

    @Test
    fun unknownKeysAreIgnored() {
        val entries = ImageListParser.parse("""[{"id": 7, "imageUrl": "https://example.test/7.jpg", "extra": true}]""")

        assertEquals(listOf(entry(7)), entries)
    }

    @Test
    fun invalidElementsAreSkippedWhileValidNeighboursKeepTheirOrder() {
        val invalidElements =
            listOf(
                "1",
                """{"imageUrl": "https://example.test/x.jpg"}""",
                """{"id": null, "imageUrl": "https://example.test/x.jpg"}""",
                """{"id": "abc", "imageUrl": "https://example.test/x.jpg"}""",
                """{"id": 1.5, "imageUrl": "https://example.test/x.jpg"}""",
                """{"id": 9}""",
                """{"id": 9, "imageUrl": null}""",
                """{"id": 9, "imageUrl": "  "}""",
            )
        val json =
            invalidElements.joinToString(
                prefix = """[{"id": 1, "imageUrl": "https://example.test/1.jpg"},""",
                separator = ",",
                postfix = """,{"id": 2, "imageUrl": "https://example.test/2.jpg"}]""",
            )

        assertEquals(listOf(entry(1), entry(2)), ImageListParser.parse(json))
    }

    @Test
    fun aRootObjectThrows() {
        assertThrows(IllegalArgumentException::class.java) { ImageListParser.parse("{}") }
    }

    @Test
    fun malformedTextThrows() {
        assertThrows(IllegalArgumentException::class.java) { ImageListParser.parse("[{") }
    }
}
