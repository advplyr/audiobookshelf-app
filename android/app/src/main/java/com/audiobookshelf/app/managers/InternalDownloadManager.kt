package com.audiobookshelf.app.managers

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.*
import java.util.concurrent.TimeUnit
import okhttp3.*

/**
 * Manages the internal download process with support for resume.
 *
 * Thread Safety:
 * - Callbacks (progressCallback) are invoked on OkHttp's background threads, not the main thread.
 *   Implementations must handle thread synchronization if updating UI or shared state.
 * - The download() method is asynchronous and returns immediately.
 * - cancel() and close() are thread-safe and can be called from any thread.
 *
 * @property destinationFile The file to download to (used for resume).
 * @property progressCallback The callback to report download progress (called on background thread).
 */
class InternalDownloadManager(
        private val destinationFile: File,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  companion object {
    private const val TAG = "InternalDownloadManager"
    private const val CONNECT_TIMEOUT_SECONDS = 60L
    private const val READ_TIMEOUT_MINUTES = 5L
    private const val WRITE_TIMEOUT_SECONDS = 60L
    private const val CALL_TIMEOUT_HOURS = 6L
    private const val CONNECTION_POOL_MAX_IDLE = 5
    private const val CONNECTION_POOL_KEEP_ALIVE_MINUTES = 5L
    private const val BUFFER_SIZE = 256 * 1024 // 256KB
    private const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024 * 1024 // 10GB limit
    private const val MAX_RETRY_ATTEMPTS = 3 // Maximum number of retry attempts for transient failures
    private const val RETRY_DELAY_MS = 1000L // Initial delay between retries (exponential backoff)

    /**
     * Shared OkHttpClient instance for connection pooling and resource efficiency.
     * Reusing the client across all downloads improves performance and reduces memory overhead.
     */
    private val sharedClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
              .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .readTimeout(READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
              .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .callTimeout(CALL_TIMEOUT_HOURS, TimeUnit.HOURS)
              .retryOnConnectionFailure(true)
              .connectionPool(ConnectionPool(CONNECTION_POOL_MAX_IDLE, CONNECTION_POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
              // Add interceptor for connection keep-alive headers
              .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                        .header("Connection", "keep-alive")
                        .header("Keep-Alive", "timeout=600, max=1000")
                        .build()
                chain.proceed(request)
              }
              .build()
    }
  }

  @Volatile
  private var currentCall: Call? = null
  private var retryCount = 0

  /**
   * Downloads a file from the given URL with resume support.
   *
   * @param url The URL to download the file from.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun download(url: String) {
    // Validate URL
    require(url.isNotBlank()) { "URL cannot be blank" }
    require(url.startsWith("http://") || url.startsWith("https://")) {
      "URL must use HTTP or HTTPS protocol"
    }

    // Validate destination file
    val parentDir = destinationFile.parentFile
            ?: throw IOException("Invalid destination file path: ${destinationFile.absolutePath}")

    // Create parent directory if it doesn't exist
    if (!parentDir.exists() && !parentDir.mkdirs()) {
      throw IOException("Cannot create destination directory: ${parentDir.absolutePath}")
    }

    // Verify directory is writable
    if (!parentDir.canWrite()) {
      throw IOException("Destination directory is not writable: ${parentDir.absolutePath}")
    }

    // Validate file path doesn't contain path traversal
    val canonicalPath = destinationFile.canonicalPath
    val parentCanonicalPath = parentDir.canonicalPath
    if (!canonicalPath.startsWith(parentCanonicalPath)) {
      throw IOException("Invalid file path - potential path traversal detected")
    }

    // Check if file exists and get current size for resume
    val existingBytes = if (destinationFile.exists()) destinationFile.length() else 0L

    Log.d(TAG, "Starting download: url=$url, existingBytes=$existingBytes")

    val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept-Encoding", "identity")

    // Add Range header for resume if file partially exists
    if (existingBytes > 0) {
      requestBuilder.addHeader("Range", "bytes=$existingBytes-")
      Log.d(TAG, "Resuming download from byte $existingBytes")
    }

    val request = requestBuilder.build()

    executeWithRetry(request, existingBytes)
  }

  /**
   * Executes the download request with automatic retry on transient failures.
   */
  private fun executeWithRetry(request: Request, existingBytes: Long) {
    val call = sharedClient.newCall(request)
    currentCall = call

    call.enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        // Check if we should retry for transient network errors
                        val isRetryable = e is java.net.SocketTimeoutException ||
                                e is java.net.UnknownHostException ||
                                e is java.net.ConnectException ||
                                e.message?.contains("connection", ignoreCase = true) == true

                        if (isRetryable && retryCount < MAX_RETRY_ATTEMPTS) {
                          retryCount++
                          val delayMs = RETRY_DELAY_MS * (1 shl (retryCount - 1)) // Exponential backoff
                          Log.w(TAG, "Download failed, retrying (attempt $retryCount/$MAX_RETRY_ATTEMPTS) after ${delayMs}ms: ${e.message}")

                          // Schedule retry with exponential backoff using Handler (non-blocking)
                          Handler(Looper.getMainLooper()).postDelayed({
                            executeWithRetry(request, existingBytes)
                          }, delayMs)
                          return
                        }

                        // Download failed permanently - cleanup partial file
                        cleanupFailedDownload()

                        Log.e(TAG, "Download FAILED for URL: ${request.url}", e)
                        Log.e(TAG, "Error type: ${e.javaClass.simpleName}, Message: ${e.message}")
                        Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                        progressCallback.onComplete(true)
                      }

                      override fun onResponse(call: Call, response: Response) {
                        Log.d(TAG, "Got response: code=${response.code}, url=${request.url}")

                        // Check for 401 Unauthorized (token expired)
                        if (response.code == 401) {
                          Log.e(TAG, "Download failed with 401 Unauthorized - token may have expired")
                          Log.e(TAG, "Download URL: ${request.url}")
                          Log.e(TAG, "This typically happens when downloads exceed 1 hour. User needs to re-authenticate.")
                          progressCallback.onComplete(true)
                          return
                        }

                        if (!response.isSuccessful && response.code != 206) {
                          Log.e(TAG, "Download failed with status: ${response.code}, message: ${response.message}")
                          progressCallback.onComplete(true)
                          return
                        }

                        response.body?.let { responseBody ->
                          val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L

                          // Validate file size doesn't exceed maximum
                          if (contentLength > MAX_FILE_SIZE_BYTES) {
                            Log.e(TAG, "File size ($contentLength bytes) exceeds maximum allowed (${MAX_FILE_SIZE_BYTES} bytes)")
                            progressCallback.onComplete(true)
                            return
                          }

                          // Check if server supports Range requests
                          val acceptRanges = response.header("Accept-Ranges")
                          val supportsRanges = acceptRanges != null && acceptRanges != "none"

                          // Determine if we're resuming or starting fresh
                          val isResuming = response.code == 206
                          val shouldAppend = existingBytes > 0 && isResuming
                          val startBytes = if (shouldAppend) existingBytes else 0L

                          val totalLength = if (isResuming) {
                            // Partial content - add existing bytes to content length
                            existingBytes + contentLength
                          } else {
                            // Server doesn't support resume or fresh download
                            // If we requested resume but got 200, warn and restart
                            if (existingBytes > 0) {
                              if (!supportsRanges) {
                                Log.w(TAG, "Server doesn't support Range requests (Accept-Ranges: ${acceptRanges ?: "not set"}), restarting download")
                              } else {
                                Log.w(TAG, "Server supports ranges but returned ${response.code} instead of 206, restarting download")
                              }
                            }
                            contentLength
                          }

                          Log.d(TAG, "Response code: ${response.code}, Accept-Ranges: $acceptRanges, supportsRanges=$supportsRanges, contentLength=$contentLength, totalLength=$totalLength, resuming=$isResuming")

                          var fileOutputStream: FileOutputStream? = null
                          try {
                            // Check if file is writable before attempting to open
                            if (destinationFile.exists() && !destinationFile.canWrite()) {
                              Log.e(TAG, "File exists but is not writable (permission denied): ${destinationFile.absolutePath}")
                              progressCallback.onComplete(true)
                              return
                            }
                            
                            // Check parent directory permissions
                            val parentDir = destinationFile.parentFile
                            if (parentDir != null && !parentDir.canWrite()) {
                              Log.e(TAG, "Parent directory is not writable (permission denied): ${parentDir.absolutePath}")
                              progressCallback.onComplete(true)
                              return
                            }

                            Log.d(TAG, "Starting file write to: ${destinationFile.absolutePath}, append=$shouldAppend")
                            // Open file in append mode only if truly resuming
                            fileOutputStream = FileOutputStream(destinationFile, shouldAppend)
                            // BufferedInputStream in BinaryFileWriter provides sufficient buffering

                            val writer = BinaryFileWriter(fileOutputStream, progressCallback, startBytes)
                            val bytesWritten = writer.write(responseBody.byteStream(), totalLength)
                            Log.d(TAG, "Download completed successfully, bytes written: $bytesWritten")
                          } catch (e: IOException) {
                            Log.e(TAG, "Error writing to file: ${destinationFile.absolutePath}", e)
                            Log.e(TAG, "IOException details: ${e.javaClass.simpleName}, ${e.message}")
                            cleanupFailedDownload()
                            progressCallback.onComplete(true)
                          } catch (e: SecurityException) {
                            Log.e(TAG, "Permission denied while writing file: ${destinationFile.absolutePath}", e)
                            Log.e(TAG, "Storage permissions may have been revoked during download")
                            cleanupFailedDownload()
                            progressCallback.onComplete(true)
                          } catch (e: Exception) {
                            Log.e(TAG, "Unexpected error during download", e)
                            cleanupFailedDownload()
                            progressCallback.onComplete(true)
                          } finally {
                            // Close the file output stream
                            try {
                              fileOutputStream?.close()
                            } catch (e: Exception) {
                              Log.w(TAG, "Error closing file output stream", e)
                            }
                          }
                        } ?: run {
                          Log.e(TAG, "Response doesn't contain a file")
                          progressCallback.onComplete(true)
                        }
                      }
                    }
            )
  }

  /**
   * Cancels the current download if one is in progress.
   */
  fun cancel() {
    currentCall?.cancel()
    Log.d(TAG, "Download cancelled")
  }

  /**
   * Cleans up partial/failed download files to free up storage space.
   */
  private fun cleanupFailedDownload() {
    try {
      if (destinationFile.exists()) {
        val fileSize = destinationFile.length()
        val deleted = destinationFile.delete()
        if (deleted) {
          Log.d(TAG, "Cleaned up failed download: ${destinationFile.name} (freed ${fileSize / 1024}KB)")
        } else {
          Log.w(TAG, "Failed to delete partial download file: ${destinationFile.absolutePath}")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error cleaning up failed download", e)
    }
  }

  /**
   * Closes the download manager and releases all resources.
   * Note: The shared OkHttpClient is not shut down as it's reused across instances.
   */
  override fun close() {
    // Cancel any ongoing download
    currentCall?.cancel()
    currentCall = null
    retryCount = 0
    Log.d(TAG, "InternalDownloadManager closed")
  }
}

/**
 * Writes binary data to an output stream with resume support.
 *
 * @property outputStream The output stream to write the data to.
 * @property progressCallback The callback to report write progress.
 * @property startingBytes Bytes already downloaded (for resume calculation).
 */
class BinaryFileWriter(
        private val outputStream: OutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback,
        private val startingBytes: Long = 0L
) : AutoCloseable {

  /**
   * Writes data from the input stream to the output stream.
   *
   * @param inputStream The input stream to read the data from.
   * @param totalLength The total length of the file (including already downloaded bytes).
   * @return The total number of bytes written.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun write(inputStream: InputStream, totalLength: Long): Long {
    BufferedInputStream(inputStream, BUFFER_SIZE).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var bytesWritten: Long = 0
      var readBytes: Int

      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        bytesWritten += readBytes
        outputStream.write(dataBuffer, 0, readBytes)

        val totalBytesDownloaded = startingBytes + bytesWritten
        // Use safer division to avoid overflow: divide first, then multiply
        val progress = if (totalLength > 0) {
          (totalBytesDownloaded / totalLength) * 100L + ((totalBytesDownloaded % totalLength) * 100L / totalLength)
        } else {
          0L
        }

        progressCallback.onProgress(totalBytesDownloaded, progress)
      }

      // BufferedOutputStream handles flushing automatically; explicit flush is redundant
      // Only flush at the end to ensure all data is written
      outputStream.flush()

      progressCallback.onComplete(false)
      return bytesWritten
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
    private const val CHUNK_SIZE = 65536 // 64KB chunks for optimal throughput
    private const val BUFFER_SIZE = 256 * 1024 // 256KB buffer size
  }
}
