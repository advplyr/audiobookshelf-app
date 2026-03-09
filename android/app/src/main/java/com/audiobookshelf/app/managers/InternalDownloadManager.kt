package com.audiobookshelf.app.managers

import android.util.Log
import java.io.*
import java.util.concurrent.TimeUnit
import okhttp3.*

/**
 * Manages the internal download process.
 *
 * @property outputStream The output stream to write the downloaded data.
 * @property progressCallback The callback to report download progress.
 */
class InternalDownloadManager(
        private val outputStream: FileOutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  private val tag = "InternalDownloadManager"
  private val client: OkHttpClient =
          OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
  private val writer = BinaryFileWriter(outputStream, progressCallback)
  /** The in-flight OkHttp call; used by [cancel] to abort an ongoing download. */
  private var activeCall: Call? = null

  /**
   * Cancels the ongoing download, if any.
   * The OkHttp call is aborted, causing the stream to throw IOException, which
   * the writer treats as a failure and reports via [progressCallback.onComplete].
   */
  fun cancel() {
    activeCall?.cancel()
    Log.d(tag, "Download cancelled")
  }

  /**
   * Downloads a file from the given URL.
   *
   * @param url The URL to download the file from.
   */
  fun download(url: String) {
    val request: Request = Request.Builder().url(url).addHeader("Accept-Encoding", "identity").build()
    activeCall = client.newCall(request)
    activeCall!!.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                Log.e(tag, "Download URL $url FAILED", e)
                progressCallback.onComplete(true)
              }

              override fun onResponse(call: Call, response: Response) {
                response.body?.let { responseBody ->
                  val length: Long = response.header("Content-Length")?.toLongOrNull() ?: 0L
                  writer.write(responseBody.byteStream(), length, call::isCanceled)
                } ?: run {
                  Log.e(tag, "Response doesn't contain a file")
                  progressCallback.onComplete(true)
                }
              }
            }
    )
  }

  /**
   * Closes the download manager and releases resources.
   *
   * @throws Exception If an error occurs during closing.
   */
  @Throws(Exception::class)
  override fun close() {
    writer.close()
  }
}

/**
 * Writes binary data to an output stream.
 *
 * @property outputStream The output stream to write the data to.
 * @property progressCallback The callback to report write progress.
 */
class BinaryFileWriter(
        private val outputStream: OutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  /**
   * Writes data from the input stream to the output stream.
   *
   * @param inputStream The input stream to read the data from.
   * @param length The total length of the data to be written.
   * @param isCancelled Optional lambda checked each chunk; when true the write aborts cleanly.
   * @return The total number of bytes written.
   */
  fun write(inputStream: InputStream, length: Long, isCancelled: () -> Boolean = { false }): Long {
    var totalBytes: Long = 0
    var onCompleteCalled = false
    try {
      BufferedInputStream(inputStream).use { input ->
        val dataBuffer = ByteArray(CHUNK_SIZE)
        var readBytes: Int = input.read(dataBuffer)
        while (!isCancelled() && readBytes != -1) {
          totalBytes += readBytes
          outputStream.write(dataBuffer, 0, readBytes)
          progressCallback.onProgress(totalBytes, (totalBytes * 100L) / length)
          readBytes = input.read(dataBuffer)
        }
        // Report cancellation as failure so the caller can clean up
        progressCallback.onComplete(isCancelled())
        onCompleteCalled = true
      }
    } catch (e: IOException) {
      // Stream closed due to call cancellation or network error
      if (!onCompleteCalled) progressCallback.onComplete(true)
    }
    return totalBytes
  }

  /**
   * Closes the writer and releases resources.
   *
   * @throws IOException If an error occurs during closing.
   */
  @Throws(IOException::class)
  override fun close() {
    outputStream.close()
  }

  companion object {
    private const val CHUNK_SIZE = 8192 // Increased chunk size for better performance
  }
}
