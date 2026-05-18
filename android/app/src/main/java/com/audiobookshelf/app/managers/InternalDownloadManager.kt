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
                  .connectTimeout(30, TimeUnit.SECONDS)
                  .readTimeout(60, TimeUnit.SECONDS)
                  .build()
  private val writer = BinaryFileWriter(outputStream, progressCallback)

  /**
   * Downloads a file from the given URL, optionally resuming from [resumeFrom] bytes.
   *
   * When [resumeFrom] > 0 the request includes a Range header. A 206 response appends to
   * the existing partial file; a 200 response means the server does not support ranges and
   * the caller is responsible for having opened the stream in truncate mode.
   */
  fun download(url: String, resumeFrom: Long = 0L) {
    val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept-Encoding", "identity")
    if (resumeFrom > 0L) {
      requestBuilder.addHeader("Range", "bytes=$resumeFrom-")
    }
    client.newCall(requestBuilder.build())
            .enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        Log.e(tag, "Download URL $url FAILED", e)
                        progressCallback.onComplete(true)
                      }

                      override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful && response.code != 206) {
                          Log.e(tag, "Download URL $url returned HTTP ${response.code}")
                          progressCallback.onComplete(true)
                          return
                        }
                        response.body?.let { responseBody ->
                          // Content-Length on a 206 is the remaining bytes; add the already-written
                          // offset so progress percentage is computed against the full file size.
                          val remaining: Long = response.header("Content-Length")?.toLongOrNull() ?: 0L
                          val totalLength = if (response.code == 206) resumeFrom + remaining else remaining
                          try {
                            writer.write(responseBody.byteStream(), totalLength, resumeFrom)
                          } catch (e: IOException) {
                            Log.e(tag, "Stream interrupted during download of $url", e)
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
   * Writes data from [inputStream] to the output stream.
   *
   * @param inputStream Source stream.
   * @param totalFileLength Full expected file size (used for progress percentage).
   * @param bytesAlreadyWritten Bytes already on disk from a previous partial download;
   *   progress callbacks include this offset so the percentage reflects the whole file.
   */
  fun write(inputStream: InputStream, totalFileLength: Long, bytesAlreadyWritten: Long = 0L): Long {
    BufferedInputStream(inputStream).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var newBytes: Long = 0
      var readBytes: Int
      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        newBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        val written = bytesAlreadyWritten + newBytes
        val progress = if (totalFileLength > 0) (written * 100L) / totalFileLength else 0L
        progressCallback.onProgress(written, progress)
      }
      progressCallback.onComplete(false)
      return newBytes
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
