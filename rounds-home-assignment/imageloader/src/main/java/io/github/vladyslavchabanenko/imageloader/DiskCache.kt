package io.github.vladyslavchabanenko.imageloader

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The original bytes of a downloaded image and the wall-clock time the download finished. */
internal class DiskEntry(
    val bytes: ByteArray,
    val downloadedAtMillis: Long,
)

/**
 * Stores downloaded images as files in [directory], one per URL, valid for [TTL_MILLIS] from the
 * download and limited to [maxBytes] in total, least recently used first out.
 *
 * Not thread-safe: every call must run on the same serialized disk dispatcher. It never throws
 * for disk problems. An unreadable, corrupt or expired entry is absent and is deleted.
 */
internal class DiskCache(
    private val directory: Lazy<File>,
    private val clock: Clock,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    // File name -> file size, least recently used first. Built from the directory on first use.
    private val index = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var totalBytes = 0L
    private var indexed = false

    fun get(url: String): DiskEntry? {
        ensureIndexed()
        val name = fileName(url)
        if (name !in index) return null
        val file = File(directory.value, name)
        val now = clock.nowMillis()
        val entry =
            try {
                read(file)
            } catch (e: IOException) {
                Log.w(TAG, "Unreadable cache file $name", e)
                null
            }
        if (entry == null || !isFresh(entry.downloadedAtMillis, now)) {
            delete(name)
            return null
        }
        index[name] // Moves the entry to the most recently used end.
        file.setLastModified(now)
        return entry
    }

    /** Stores [bytes], downloaded at [downloadedAtMillis]. A write that fails leaves no entry. */
    fun put(
        url: String,
        bytes: ByteArray,
        downloadedAtMillis: Long,
    ) {
        ensureIndexed()
        val size = HEADER_SIZE + bytes.size.toLong()
        if (size > maxBytes) return
        val name = fileName(url)
        val dir = directory.value
        val temp = File(dir, name + TEMP_SUFFIX)
        try {
            dir.mkdirs()
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.write(MAGIC)
                out.writeLong(downloadedAtMillis)
                out.write(bytes)
            }
            if (!temp.renameTo(File(dir, name))) throw IOException("Could not rename ${temp.name}")
        } catch (e: IOException) {
            temp.delete()
            Log.w(TAG, "Could not cache $url", e)
            return
        }
        totalBytes += size - (index.put(name, size) ?: 0L)
        evictLeastRecentlyUsed()
    }

    fun remove(url: String) {
        ensureIndexed()
        delete(fileName(url))
    }

    fun clear() {
        ensureIndexed()
        directory.value
            .listFiles()
            .orEmpty()
            .forEach { it.delete() }
        index.clear()
        totalBytes = 0L
    }

    private fun ensureIndexed() {
        if (indexed) return
        indexed = true
        val files = directory.value.listFiles().orEmpty()
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
        files
            .filter { it.name.endsWith(IMAGE_SUFFIX) }
            .sortedBy { it.lastModified() }
            .forEach { index[it.name] = it.length() }
        totalBytes = index.values.sum()
    }

    private fun read(file: File): DiskEntry? {
        val payloadSize = file.length() - HEADER_SIZE
        if (payloadSize <= 0 || payloadSize > Int.MAX_VALUE) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) return null
            val downloadedAtMillis = input.readLong()
            val bytes = ByteArray(payloadSize.toInt()).also(input::readFully)
            return DiskEntry(bytes, downloadedAtMillis)
        }
    }

    private fun delete(name: String) {
        totalBytes -= index.remove(name) ?: 0L
        val file = File(directory.value, name)
        if (!file.delete() && file.exists()) Log.w(TAG, "Could not delete cache file $name")
    }

    private fun evictLeastRecentlyUsed() {
        val eldest = index.entries.iterator()
        while (totalBytes > maxBytes && eldest.hasNext()) {
            val (name, size) = eldest.next()
            eldest.remove()
            totalBytes -= size
            File(directory.value, name).delete()
        }
    }

    private fun fileName(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + IMAGE_SUFFIX

    companion object {
        /** An entry is valid for 4 hours from the moment it was downloaded. */
        const val TTL_MILLIS = 14_400_000L

        private const val TAG = "ImageLoader"
        private const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024
        private const val IMAGE_SUFFIX = ".img"
        private const val TEMP_SUFFIX = ".tmp"
        private val MAGIC = "ILC1".toByteArray()
        private val HEADER_SIZE = MAGIC.size + Long.SIZE_BYTES

        /** The one validity rule, shared by the disk and memory caches. */
        fun isFresh(
            downloadedAtMillis: Long,
            nowMillis: Long,
        ): Boolean = downloadedAtMillis <= nowMillis && nowMillis < downloadedAtMillis + TTL_MILLIS
    }
}
