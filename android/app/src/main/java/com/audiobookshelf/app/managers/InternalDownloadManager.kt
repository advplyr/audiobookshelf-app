package com.audiobookshelf.app.managers

import android.util.Log
import java.io.*
import java.net.Socket
import java.util.concurrent.TimeUnit
import okhttp3.*

/**
 * Manages the internal download process with support for resume.
 *
 * @property destinationFile The file to download to (used for resume).
 * @property progressCallback The callback to report download progress.
 */
class InternalDownloadManager(
        private val destinationFile: File,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  private val tag = "InternalDownloadManager"
  private val client: OkHttpClient =
          OkHttpClient.Builder()
                  .connectTimeout(60, TimeUnit.SECONDS)
                  .readTimeout(0, TimeUnit.SECONDS) // Disable read timeout to prevent socket closure
                  .writeTimeout(60, TimeUnit.SECONDS)
                  .callTimeout(0, TimeUnit.SECONDS) // Disable call timeout
                  .retryOnConnectionFailure(true)
                  // Keep connections alive for reuse
                  .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                  // Add interceptor for connection keep-alive headers
                  .addNetworkInterceptor { chain ->
                    val request = chain.request().newBuilder()
                            .header("Connection", "keep-alive")
                            .header("Keep-Alive", "timeout=600, max=1000")
                            .build()
                    chain.proceed(request)
                  }
                  // Enable aggressive TCP keep-alive for socket connections
                  .socketFactory(object : javax.net.SocketFactory() {
                    override fun createSocket(): Socket {
                      return Socket().apply {
                        keepAlive = true
                        tcpNoDelay = true
                        soTimeout = 0 // No timeout - let TCP handle it
                        receiveBufferSize = 256 * 1024 // 256KB receive buffer for faster downloads
                        sendBufferSize = 256 * 1024 // 256KB send buffer
                        // Enable TCP keep-alive with aggressive settings
                        setPerformancePreferences(0, 1, 2) // connectionTime, latency, bandwidth
                        reuseAddress = true
                      }
                    }
                    override fun createSocket(host: String?, port: Int): Socket {
                      return createSocket().apply {
                        connect(java.net.InetSocketAddress(host, port), 60000)
                      }
                    }
                    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket {
                      return createSocket().apply {
                        bind(java.net.InetSocketAddress(localHost, localPort))
                        connect(java.net.InetSocketAddress(host, port), 60000)
                      }
                    }
                    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket {
                      return createSocket().apply {
                        connect(java.net.InetSocketAddress(host, port), 60000)
                      }
                    }
                    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket {
                      return createSocket().apply {
                        bind(java.net.InetSocketAddress(localAddress, localPort))
                        connect(java.net.InetSocketAddress(address, port), 60000)
                      }
                    }
                  })
                  .build()

  /**
   * Downloads a file from the given URL with resume support.
   *
   * @param url The URL to download the file from.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun download(url: String) {
    // Check if file exists and get current size for resume
    val existingBytes = if (destinationFile.exists()) destinationFile.length() else 0L

    Log.d(tag, "Starting download: url=$url, existingBytes=$existingBytes")

    val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept-Encoding", "identity")

    // Add Range header for resume if file partially exists
    if (existingBytes > 0) {
      requestBuilder.addHeader("Range", "bytes=$existingBytes-")
      Log.d(tag, "Resuming download from byte $existingBytes")
    }

    val request = requestBuilder.build()

    client.newCall(request)
            .enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        Log.e(tag, "Download FAILED for URL: $url", e)
                        Log.e(tag, "Error type: ${e.javaClass.simpleName}, Message: ${e.message}")
                        Log.e(tag, "Stack trace: ${e.stackTraceToString()}")
                        progressCallback.onComplete(true)
                      }

                      override fun onResponse(call: Call, response: Response) {
                        Log.d(tag, "Got response: code=${response.code}, url=$url")

                        if (!response.isSuccessful && response.code != 206) {
                          Log.e(tag, "Download failed with status: ${response.code}, message: ${response.message}")
                          progressCallback.onComplete(true)
                          return
                        }

                        response.body?.let { responseBody ->
                          val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L

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
                                Log.w(tag, "Server doesn't support Range requests (Accept-Ranges: ${acceptRanges ?: "not set"}), restarting download")
                              } else {
                                Log.w(tag, "Server supports ranges but returned ${response.code} instead of 206, restarting download")
                              }
                            }
                            contentLength
                          }

                          Log.d(tag, "Response code: ${response.code}, Accept-Ranges: $acceptRanges, supportsRanges=$supportsRanges, contentLength=$contentLength, totalLength=$totalLength, resuming=$isResuming")

                          try {
                            Log.d(tag, "Starting file write to: ${destinationFile.absolutePath}, append=$shouldAppend")
                            // Open file in append mode only if truly resuming
                            // Use BufferedOutputStream with large buffer for better performance
                            val fileOutputStream = FileOutputStream(destinationFile, shouldAppend)
                            val bufferedOutputStream = BufferedOutputStream(fileOutputStream, 256 * 1024) // 256KB buffer
                            val writer = BinaryFileWriter(bufferedOutputStream, progressCallback, startBytes)
                            val bytesWritten = writer.write(responseBody.byteStream(), totalLength)
                            bufferedOutputStream.close()
                            fileOutputStream.close()
                            Log.d(tag, "Download completed successfully, bytes written: $bytesWritten")
                          } catch (e: IOException) {
                            Log.e(tag, "Error writing to file: ${destinationFile.absolutePath}", e)
                            Log.e(tag, "IOException details: ${e.javaClass.simpleName}, ${e.message}")
                            progressCallback.onComplete(true)
                          } catch (e: Exception) {
                            Log.e(tag, "Unexpected error during download", e)
                            progressCallback.onComplete(true)
                          }
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
    // Resources are managed per-download now
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
    BufferedInputStream(inputStream, 256 * 1024).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var bytesWritten: Long = 0
      var readBytes: Int
      var chunksWritten = 0

      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        bytesWritten += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        chunksWritten++

        // Flush less frequently for better performance (every ~64MB)
        if (chunksWritten % 1000 == 0) {
          outputStream.flush()
        }

        val totalBytesDownloaded = startingBytes + bytesWritten
        val progress = if (totalLength > 0) {
          (totalBytesDownloaded * 100L) / totalLength
        } else {
          0L
        }

        progressCallback.onProgress(totalBytesDownloaded, progress)
      }

      // Final flush to ensure all data is written
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
  }
}
