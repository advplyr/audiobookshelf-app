package com.audiobookshelf.app.managers

import com.audiobookshelf.app.plugins.AbsLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Streams a download into an app-owned staging file. */
class InternalDownloadManager(
        private val destinationFile: File,
        private val expectedSize: Long,
        private val progressCallback: DownloadItemManager.InternalProgressCallback,
        private val hasAvailableSpace: () -> Boolean
) {
  private val tag = "InternalDownloadManager"

  interface DownloadHandle {
    fun cancel()
  }

  private class ActiveDownloadHandle : DownloadHandle {
    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>()

    fun setCall(call: Call) {
      activeCall.set(call)
      if (cancelled.get()) call.cancel()
    }

    override fun cancel() {
      cancelled.set(true)
      activeCall.get()?.cancel()
    }
  }
  /**
   * Starts or resumes a download.
   *
   * @param url download URL
   * @param token access token sent in the Authorization header
   * @return logical handle used to cancel the active request, including a restarted request
   */
  fun download(url: String, token: String): DownloadHandle {
    destinationFile.parentFile?.mkdirs()
    val handle = ActiveDownloadHandle()
    startRequest(url, token, handle, allowRestart = true)
    return handle
  }

  private fun startRequest(
          url: String,
          token: String,
          handle: ActiveDownloadHandle,
          allowRestart: Boolean
  ) {
    var existingBytes = destinationFile.takeIf { it.exists() }?.length() ?: 0L
    AbsLogger.info(
            tag,
            "Starting ${if (existingBytes > 0L) "resumed" else "new"} download for ${destinationFile.name} at byte $existingBytes")
    val request =
            Request.Builder()
                    .url(url)
                    .addHeader("Accept-Encoding", "identity")
                    .addHeader("Authorization", "Bearer $token")
                    .apply { if (existingBytes > 0L) header("Range", "bytes=$existingBytes-") }
                    .build()
    val call = client.newCall(request)
    handle.setCall(call)
    call.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                AbsLogger.error(tag, "Download request failed for ${destinationFile.name}: ${e.message}")
                progressCallback.onComplete(true)
              }

              override fun onResponse(call: Call, response: Response) {
                response.use {
                  try {
                    if (response.code == 401) {
                      AbsLogger.error(tag, "Download unauthorized (401) for ${destinationFile.name}")
                      progressCallback.onAuthError()
                      return
                    }
                    if (response.code == 416) {
                      val serverSize =
                              response.header("Content-Range")
                                      ?.removePrefix("bytes */")
                                      ?.toLongOrNull()
                      if (serverSize != null) progressCallback.onSizeResolved(serverSize)
                      if (serverSize != null && existingBytes == serverSize) {
                        progressCallback.onProgress(existingBytes, 100L)
                        AbsLogger.info(tag, "Download completed for ${destinationFile.name} ($existingBytes bytes)")
                        progressCallback.onComplete(false)
                      } else if (allowRestart && destinationFile.delete()) {
                        AbsLogger.info(tag, "Restarting stale range from byte zero for ${destinationFile.name}")
                        startRequest(url, token, handle, allowRestart = false)
                      } else {
                        AbsLogger.error(tag, "Could not recover invalid range for ${destinationFile.name} at byte $existingBytes")
                        progressCallback.onComplete(true)
                      }
                      return
                    }
                    val append =
                            existingBytes > 0L &&
                                    response.code == 206 &&
                                    hasExpectedRange(response, existingBytes)
                    if (existingBytes > 0L && !append && response.code != 200) {
                      AbsLogger.error(
                              tag,
                              "Invalid resume response ${response.code} for ${destinationFile.name} at byte $existingBytes"
                      )
                      progressCallback.onComplete(true)
                      return
                    }
                    if (!response.isSuccessful || response.body == null) {
                      AbsLogger.error(tag, "Download HTTP failure ${response.code} for ${destinationFile.name}")
                      progressCallback.onComplete(true)
                      return
                    }

                    val startingBytes = if (append) existingBytes else 0L
                    val responseLength = response.body!!.contentLength()
                    val serverSize =
                            if (append) contentRangeTotal(response)
                            else responseLength.takeIf { it >= 0L }
                    if (serverSize != null) progressCallback.onSizeResolved(serverSize)
                    val totalLength =
                            serverSize ?: if (expectedSize > 0L) expectedSize else 0L

                    FileOutputStream(destinationFile, append).use { output ->
                      response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var totalBytes = startingBytes
                        while (true) {
                          val read = input.read(buffer)
                          if (read < 0) break
                          if (!hasAvailableSpace())
                                  throw IOException("Download paused to preserve free storage")
                          output.write(buffer, 0, read)
                          totalBytes += read
                          val progress =
                                  if (totalLength > 0L) (totalBytes * 100L) / totalLength else 0L
                          progressCallback.onProgress(totalBytes, progress.coerceAtMost(100L))
                        }
                      }
                    }

                    val downloadedSize = destinationFile.length()
                    if (serverSize != null && downloadedSize != serverSize) {
                      AbsLogger.error(
                              tag,
                              "Downloaded size for ${destinationFile.name} was $downloadedSize, expected server size $serverSize"
                      )
                      progressCallback.onComplete(true)
                    } else {
                      AbsLogger.info(
                              tag,
                              "Download completed for ${destinationFile.name} ($downloadedSize bytes)"
                      )
                      progressCallback.onComplete(false)
                    }
                  } catch (e: IOException) {
                    AbsLogger.error(tag, "Could not write staging file ${destinationFile.name}: ${e.message}")
                    progressCallback.onComplete(true)
                  }
                }
              }
            }
    )
  }

  private fun hasExpectedRange(response: Response, offset: Long): Boolean {
    val range = response.header("Content-Range") ?: return false
    val match = CONTENT_RANGE.matchEntire(range) ?: return false
    return match.groupValues[1].toLongOrNull() == offset &&
            match.groupValues[2].toLongOrNull()?.let { it >= offset } == true
  }

  private fun contentRangeTotal(response: Response): Long? =
          CONTENT_RANGE.matchEntire(response.header("Content-Range") ?: "")
                  ?.groupValues
                  ?.get(3)
                  ?.takeUnless { it == "*" }
                  ?.toLongOrNull()

  private companion object {
    const val CHUNK_SIZE = 512 * 1024 // 512 KB
    val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
  }
}
