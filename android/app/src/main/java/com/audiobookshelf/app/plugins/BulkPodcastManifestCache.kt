package com.audiobookshelf.app.plugins

import java.io.File
import java.io.IOException
import java.util.UUID

/** Disposable, bridge-owned preflight data. The download queue is the durable state. */
internal class BulkPodcastManifestCache(
        private val directory: File,
        private val byteLimit: Long = 64L * 1024 * 1024,
        private val freeSpaceReserve: Long = 32L * 1024 * 1024
) {
  companion object {
    private val processPrefix = UUID.randomUUID().toString() + "-"
  }

  init {
    // Another live bridge in this process may still be using its own manifests.
    directory.listFiles()?.filterNot { it.name.startsWith(processPrefix) }?.forEach { it.delete() }
  }

  private data class Entry(val file: File, val bytes: Int, var consumed: Boolean = false)
  private val files = mutableMapOf<String, Entry>()
  private var stagedBytes = 0L
  private var closed = false

  @Synchronized
  fun stage(manifest: String): String {
    check(!closed) { "Download bridge is closed" }
    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create manifest cache")
    val bytes = manifest.toByteArray(Charsets.UTF_8)
    if (bytes.size > byteLimit - stagedBytes || bytes.size > directory.usableSpace - freeSpaceReserve) {
      throw IOException("Insufficient space for podcast download preparation")
    }
    val id = processPrefix + UUID.randomUUID().toString()
    val file = File(directory, id)
    try {
      file.writeBytes(bytes)
    } catch (e: IOException) {
      file.delete()
      throw e
    }
    files[id] = Entry(file, bytes.size)
    stagedBytes += bytes.size
    return id
  }

  // Consumes a staged manifest once; missing or already-consumed IDs throw.
  @Synchronized
  fun consume(id: String): String {
    val entry = files[id]?.takeUnless { it.consumed } ?: throw IOException("Podcast manifest expired")
    val data = entry.file.readText()
    entry.consumed = true
    discard(listOf(id))
    return data
  }

  @Synchronized
  fun discard(ids: Iterable<String>) {
    for (id in ids) {
      val entry = files[id] ?: continue
      if (entry.file.delete() || !entry.file.exists()) {
        files.remove(id)
        stagedBytes -= entry.bytes
      }
    }
  }

  @Synchronized
  fun close() {
    closed = true
    discard(files.keys.toList())
  }
}
