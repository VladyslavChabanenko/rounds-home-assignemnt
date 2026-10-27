package io.github.vladyslavchabanenko.imageloader.sample.data

/** One entry of the published image list. Addresses may repeat across entries. */
data class ImageEntry(
    val id: Long,
    val imageUrl: String,
)
