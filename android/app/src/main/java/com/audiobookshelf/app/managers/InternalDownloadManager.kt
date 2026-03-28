package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.*
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ConnectionPool

/**
 * Manages the internal download process.
 *
 * @property outputStream The output stream to write the downloaded data.
 * @property progressCallback The callback to report download progress.
 */
class InternalDownloadManager(
        private val context: Context,
        private val fileUri: Uri,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  private val tag = "InternalDownloadManager"

  companion object {
    // Shared across all downloads so TCP connections are reused between files.
    // Pool keeps a few extra connections warm beyond maxSimultaneousDownloads.
    val client: OkHttpClient =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS) // generous per-read timeout; 0 would block threads permanently on stall
                    .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                    .build()
  }

  /**
   * Downloads a file from the given URL.
   *
   * @param url The URL to download the file from.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun download(url: String) {
    try {
      val existingSize = getInternalFileSize(fileUri)
      Log.d(tag, "Existing file size: $existingSize bytes")

      val request =
              Request.Builder()
                      .url(url)
                      .apply {
                        if (existingSize > 0) {
                          addHeader("Range", "bytes=$existingSize-") // Resume download
                        }
                      }
                      .addHeader("Accept-Encoding", "identity")
                      .build()

      client.newCall(request)
              .enqueue(
                      object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                          Log.e(tag, "Download failed: $url", e)
                          progressCallback.onComplete(true)
                        }

                        override fun onResponse(call: Call, response: Response) {
                          if (!response.isSuccessful) {
                            Log.e(tag, "Failed to download: ${response.code}")
                            progressCallback.onComplete(true)
                            return
                          }

                          response.body?.let { responseBody ->
                            val totalLength =
                                    (response.header("Content-Length")?.toLongOrNull()
                                            ?: 0L) + existingSize
                            val outputStream = getOutputStream(fileUri, existingSize)

                            outputStream?.let {
                              BinaryFileWriter(it, progressCallback, existingSize).use { writer ->
                                writer.write(responseBody.byteStream(), totalLength)
                              }
                            }
                                    ?: run {
                                      Log.e(tag, "Failed to open output stream")
                                      progressCallback.onComplete(true)
                                    }
                          }
                                  ?: run {
                                    Log.e(tag, "Response doesn't contain a file")
                                    progressCallback.onComplete(true)
                                  }
                        }
                      }
              )
    } catch (e: Exception) {
      Log.e(tag, "Download error", e)
      progressCallback.onComplete(true)
    }
  }

  private fun getInternalFileSize(uri: Uri): Long {
    val file = File(uri.path!!)
    return if (file.exists()) file.length() else 0L
  }

  private fun getOutputStream(uri: Uri, existingSize: Long): OutputStream? {
    val file = File(uri.path!!)
    return if (file.exists()) {
      FileOutputStream(file, true).apply {
        channel.position(existingSize) // Move to the end of the file to append
      }
    } else {
      FileOutputStream(file) // Create a new file if it doesn't exist
    }
  }

  @Throws(Exception::class) override fun close() {}
}

class BinaryFileWriter(
        private val outputStream: OutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback,
        private val existingSize: Long
) : AutoCloseable {

  /**
   * Writes data from the input stream to the output stream.
   *
   * @param inputStream The input stream to read the data from.
   * @param totalLength The total length of the data to be written.
   * @return The total number of bytes written.
   * @throws IOException If an I/O error occurs.
   */
  fun write(inputStream: InputStream, totalLength: Long): Long {
    val dataBuffer = ByteArray(CHUNK_SIZE)
    var totalBytes = existingSize
    var readBytes: Int
    try {
      while (inputStream.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        // Call onProgress every chunk so the stall detector in DownloadItemManager
        // receives frequent lastUpdateTime updates. The watcher loop crosses the
        // Capacitor bridge on its own 500ms schedule, so there is no bridge cost here.
        progressCallback.onProgress(totalBytes, if (totalLength > 0) (totalBytes * 100L) / totalLength else 0L)
      }
      progressCallback.onProgress(totalBytes, if (totalLength > 0) (totalBytes * 100L) / totalLength else 100L)
      progressCallback.onComplete(false)
    } catch (e: IOException) {
      Log.e("BinaryFileWriter", "IO error during download write after $totalBytes bytes", e)
      progressCallback.onComplete(true)
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
    private const val CHUNK_SIZE = 262144 // 256 KiB — reduces syscall overhead vs. the original 8 KiB
  }
}
