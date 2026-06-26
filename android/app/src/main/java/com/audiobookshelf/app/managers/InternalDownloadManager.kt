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
          OkHttpClient.Builder()
                  .connectTimeout(15, TimeUnit.SECONDS)
                  .readTimeout(15, TimeUnit.SECONDS)
                  .build()
  private val writer = BinaryFileWriter(outputStream, progressCallback)

  /**
   * Downloads a file from the given URL.
   *
   * @param url The URL to download the file from.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun download(url: String) {
    val request: Request = Request.Builder().url(url).addHeader("Accept-Encoding", "identity").build()
    client.newCall(request)
            .enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        Log.e(tag, "Download URL $url FAILED", e)
                        progressCallback.onComplete(true)
                      }

                      override fun onResponse(call: Call, response: Response) {
                        try {
                          response.body?.let { responseBody ->
                            val length: Long = response.header("Content-Length")?.toLongOrNull() ?: 0L
                            writer.write(responseBody.byteStream(), length)
                          }
                                  ?: run {
                                    Log.e(tag, "Response doesn't contain a file")
                                    progressCallback.onComplete(true)
                                  }
                        } catch (e: Exception) {
                          Log.e(tag, "Exception during internal download write for URL $url", e)
                          progressCallback.onComplete(true)
                        } finally {
                          response.close()
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
   * @return The total number of bytes written.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun write(inputStream: InputStream, length: Long): Long {
    inputStream.use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var totalBytes: Long = 0
      var readBytes: Int
      var lastProgressTime: Long = 0

      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)

        val currentTime = System.currentTimeMillis()
        // Only report progress at most every 250ms to avoid flooding the JS bridge
        if (currentTime - lastProgressTime > 250) {
          lastProgressTime = currentTime
          progressCallback.onProgress(totalBytes, if (length > 0) (totalBytes * 100L) / length else 0)
        }
      }
      progressCallback.onComplete(false)
      return totalBytes
    }
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
    private const val CHUNK_SIZE = 65536 // Increased to 64KB for much faster I/O
  }
}
