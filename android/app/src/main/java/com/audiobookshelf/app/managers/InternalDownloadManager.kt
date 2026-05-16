package com.audiobookshelf.app.managers

import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.*

/**
 * Manages the internal download process for a single file part.
 *
 * For large files (>= MIN_CHUNKED_BYTES) where the server advertises Accept-Ranges,
 * the download is split into CHUNK_COUNT parallel byte-range requests that write
 * concurrently to a pre-allocated FileChannel at their respective offsets.
 * For small files or servers without range support, a single streaming download is used.
 */
class InternalDownloadManager(
        private val filePath: String,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  private val tag = "InternalDownloadManager"

  companion object {
    // Singleton OkHttpClient shared across ALL downloads. Creating a new client per file
    // means a fresh ConnectionPool per file — connections are never reused and every
    // file pays a full TCP + TLS handshake. One shared client means the pool stays warm
    // across concurrent slots and sequential batches.
    val client: OkHttpClient = run {
      val dispatcher = Dispatcher().also { it.maxRequestsPerHost = CHUNK_COUNT * 2 }
      OkHttpClient.Builder()
              .connectTimeout(30, TimeUnit.SECONDS)
              .readTimeout(5, TimeUnit.MINUTES)
              .writeTimeout(5, TimeUnit.MINUTES)
              .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
              .dispatcher(dispatcher)
              .build()
    }

    // Only use chunked mode for files at least this large. Small files complete so
    // fast that the HEAD round-trip + chunk overhead would be net slower.
    private const val MIN_CHUNKED_BYTES = 10L * 1024 * 1024 // 10 MB

    // Number of parallel range-request chunks for large files.
    private const val CHUNK_COUNT = 8

    // Per-chunk read buffer. 128 KiB keeps throughput high without excess heap pressure.
    private const val READ_BUFFER = 131072
  }

  /**
   * Starts the download asynchronously. Completion (success or failure) is signalled
   * via [progressCallback.onComplete].
   */
  fun download(url: String) {
    GlobalScope.launch(Dispatchers.IO) {
      try {
        // Probe the server for Content-Length and range support.
        val headRequest =
                Request.Builder().url(url).head().addHeader("Accept-Encoding", "identity").build()
        val headResponse = client.newCall(headRequest).execute()
        val contentLength = headResponse.header("Content-Length")?.toLongOrNull() ?: 0L
        val acceptsRanges = headResponse.header("Accept-Ranges").equals("bytes", ignoreCase = true)
        headResponse.close()

        if (acceptsRanges && contentLength >= MIN_CHUNKED_BYTES) {
          Log.d(tag, "Chunked download: $CHUNK_COUNT chunks for ${contentLength / (1024 * 1024)} MB")
          downloadChunked(url, contentLength)
        } else {
          Log.d(tag, "Single-stream download (contentLength=$contentLength, acceptsRanges=$acceptsRanges)")
          downloadSingle(url)
        }
      } catch (e: Exception) {
        Log.e(tag, "Download failed for $url", e)
        progressCallback.onComplete(true)
      }
    }
  }

  /**
   * Downloads the file in [CHUNK_COUNT] parallel byte-range requests.
   * Uses a FileChannel so each coroutine can write at its own absolute offset
   * concurrently without locking.
   */
  private suspend fun downloadChunked(url: String, contentLength: Long) {
    val fos = FileOutputStream(filePath)
    val fileChannel: FileChannel = fos.channel

    // Pre-allocate full file size so concurrent writers can seek freely.
    fileChannel.write(ByteBuffer.wrap(ByteArray(1)), contentLength - 1)

    val totalWritten = AtomicLong(0)

    try {
      coroutineScope {
        val chunkSize = contentLength / CHUNK_COUNT
        val jobs =
                (0 until CHUNK_COUNT).map { i ->
                  val start = i * chunkSize
                  val end =
                          if (i == CHUNK_COUNT - 1) contentLength - 1 else (start + chunkSize - 1)
                  async(Dispatchers.IO) {
                    downloadChunk(url, start, end, fileChannel, contentLength, totalWritten)
                  }
                }
        jobs.awaitAll()
      }
      progressCallback.onComplete(false)
    } catch (e: Exception) {
      Log.e(tag, "Chunked download failed for $url", e)
      progressCallback.onComplete(true)
    } finally {
      fileChannel.close()
      fos.close()
    }
  }

  /**
   * Downloads a single byte range (start to end inclusive) and writes it to fileChannel at
   * the correct absolute offset. FileChannel.write(buf, position) is thread-safe.
   */
  private fun downloadChunk(
          url: String,
          start: Long,
          end: Long,
          fileChannel: FileChannel,
          totalSize: Long,
          totalWritten: AtomicLong
  ) {
    val request =
            Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$start-$end")
                    .addHeader("Accept-Encoding", "identity")
                    .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful && response.code != 206) {
        throw IOException("Chunk $start-$end got ${response.code}")
      }
      response.body?.byteStream()?.use { input ->
        val buffer = ByteArray(READ_BUFFER)
        var offset = start
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
          fileChannel.write(ByteBuffer.wrap(buffer, 0, read), offset)
          offset += read
          val written = totalWritten.addAndGet(read.toLong())
          progressCallback.onProgress(written, (written * 100L) / totalSize)
        }
      }
              ?: throw IOException("Empty body for chunk $start-$end")
    }
  }

  /**
   * Single-stream fallback for small files or servers without range support.
   */
  private fun downloadSingle(url: String) {
    val request =
            Request.Builder().url(url).addHeader("Accept-Encoding", "identity").build()
    client.newCall(request)
            .enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        Log.e(tag, "Download URL $url FAILED", e)
                        progressCallback.onComplete(true)
                      }

                      override fun onResponse(call: Call, response: Response) {
                        val body = response.body
                        if (body == null) {
                          Log.e(tag, "Response doesn't contain a file")
                          progressCallback.onComplete(true)
                          return
                        }
                        try {
                          val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                          FileOutputStream(filePath).use { outputStream ->
                            BinaryFileWriter(outputStream, progressCallback)
                                    .write(body.byteStream(), length)
                          }
                        } catch (e: IOException) {
                          Log.e(tag, "Write failed for $url", e)
                          progressCallback.onComplete(true)
                        }
                      }
                    }
            )
  }

  override fun close() {}
}

/**
 * Writes binary data from an input stream to an output stream, reporting progress.
 */
class BinaryFileWriter(
        private val outputStream: OutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  @Throws(IOException::class)
  fun write(inputStream: InputStream, length: Long): Long {
    // Use the raw OkHttp response stream directly — OkHttp already buffers internally
    // via Okio, so wrapping in BufferedInputStream adds an extra copy layer with a
    // much smaller (8 KiB) default buffer, which hurts throughput.
    inputStream.use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var totalBytes: Long = 0
      var readBytes: Int
      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        val safeLength = if (length > 0L) length else totalBytes
        progressCallback.onProgress(totalBytes, (totalBytes * 100L) / safeLength)
      }
      progressCallback.onComplete(false)
      return totalBytes
    }
  }

  @Throws(IOException::class)
  override fun close() {
    outputStream.close()
  }

  companion object {
    // 128 KiB per read: large enough to keep throughput high on mobile networks
    // without over-committing heap.
    private const val CHUNK_SIZE = 131072
  }
}
