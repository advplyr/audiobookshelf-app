package com.audiobookshelf.app.managers

import android.content.Context
import android.net.Uri
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
        private val context: Context,
        private val fileUri: Uri,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  private val tag = "InternalDownloadManager"
  private val client: OkHttpClient =
          OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()

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
  @Throws(IOException::class)
  fun write(inputStream: InputStream, totalLength: Long): Long {
    BufferedInputStream(inputStream).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var totalBytes = existingSize
      var readBytes: Int

      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        progressCallback.onProgress(totalBytes, (totalBytes * 100L) / totalLength)
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
    private const val CHUNK_SIZE = 8192 // Increased chunk size for better performance
  }
}
