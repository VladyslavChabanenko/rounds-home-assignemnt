package io.github.vladyslavchabanenko.imageloader.sample.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Turns the published image list JSON into [ImageEntry]s. */
object ImageListParser {
    private val format = Json { ignoreUnknownKeys = true }

    /**
     * Returns the valid entries in source order, repeated addresses included. Entries without an
     * integer id or a non-blank image address are skipped.
     *
     * @throws IllegalArgumentException when [json] is malformed or its root is not an array.
     */
    fun parse(json: String): List<ImageEntry> {
        val root = format.parseToJsonElement(json)
        require(root is JsonArray) { "The image list must be a JSON array" }
        return root.mapNotNull(::parseEntry)
    }

    private fun parseEntry(element: JsonElement): ImageEntry? {
        val dto =
            try {
                format.decodeFromJsonElement<ImageEntryDto>(element)
            } catch (e: IllegalArgumentException) {
                // A SerializationException is an IllegalArgumentException. A broken entry is skipped.
                return null
            }
        val id = dto.id ?: return null
        val imageUrl = dto.imageUrl
        return if (imageUrl.isNullOrBlank()) null else ImageEntry(id, imageUrl)
    }

    @Serializable
    private data class ImageEntryDto(
        val id: Long? = null,
        val imageUrl: String? = null,
    )
}
