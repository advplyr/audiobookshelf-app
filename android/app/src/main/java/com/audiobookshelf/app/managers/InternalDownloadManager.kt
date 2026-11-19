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

/**
 * Streams a download into an app-owned staging file, resuming from the bytes already on disk.
 *
 * A resumed request sends If-Range with the validator saved from an earlier response, so the server
 * replies with the full file (and the staging file is rewritten from zero) when the file changed.
 */
class InternalDownloadManager(
        private val destinationFile: File,
        private val expectedSize: Long,
        private val etag: String?,
        private val lastModified: String?,
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

    val isCancelled: Boolean
      get() = cancelled.get()

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

  private fun restart(url: String, token: String, handle: ActiveDownloadHandle, reason: String) {
    AbsLogger.info(tag, "$reason; restarting ${destinationFile.name} from byte zero")
    if (destinationFile.exists() && !destinationFile.delete()) {
      AbsLogger.error(tag, "Could not delete stale staging file ${destinationFile.name}")
      progressCallback.onComplete(true)
      return
    }
    progressCallback.onValidatorResolved(null, null)
    startRequest(url, token, handle, allowRestart = false)
  }

  private fun startRequest(
          url: String,
          token: String,
          handle: ActiveDownloadHandle,
          allowRestart: Boolean
  ) {
    val existingBytes = destinationFile.takeIf { it.exists() }?.length() ?: 0L
    val ifRange = if (existingBytes > 0L && allowRestart) ifRangeValidator() else null
    AbsLogger.info(
            tag,
            "Starting ${if (existingBytes > 0L) "resumed" else "new"} download for ${destinationFile.name} at byte $existingBytes")
    val request =
            Request.Builder()
                    .url(url)
                    .addHeader("Accept-Encoding", "identity")
                    .addHeader("Authorization", "Bearer $token")
                    .apply {
                      if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
                      if (ifRange != null) header("If-Range", ifRange)
                    }
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
                    if (response.code in PERMANENT_FAILURE_CODES) {
                      progressCallback.onPermanentFailure("Server responded ${response.code}")
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
                      } else if (allowRestart) {
                        restart(url, token, handle, "Server rejected range at byte $existingBytes")
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

                    val serverSize =
                            if (append) contentRangeTotal(response)
                            else response.body!!.contentLength().takeIf { it >= 0L }
                    // Without a validator, a changed total size is the only sign the file was replaced.
                    if (append && ifRange == null && expectedSize > 0L && serverSize != null && serverSize != expectedSize) {
                      response.close()
                      restart(url, token, handle, "Server size changed from $expectedSize to $serverSize")
                      return
                    }
                    if (existingBytes > 0L && !append) {
                      AbsLogger.info(tag, "Server sent the full file for ${destinationFile.name}; discarding $existingBytes partial bytes")
                    }
                    if (serverSize != null) progressCallback.onSizeResolved(serverSize)
                    progressCallback.onValidatorResolved(response.header("ETag"), response.header("Last-Modified"))
                    val startingBytes = if (append) existingBytes else 0L
                    val totalLength =
                            serverSize ?: if (expectedSize > 0L) expectedSize else 0L

                    FileOutputStream(destinationFile, append).use { output ->
                      response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var totalBytes = startingBytes
                        while (true) {
                          val read = input.read(buffer)
                          if (read < 0) break
                          if (handle.isCancelled) throw IOException("Download cancelled")
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

  /**
   * If-Range only allows strong comparison (RFC 9110 13.1.5), so a strict proxy would answer a
   * weak ETag with the full file every time. Express sends weak ETags, so prefer Last-Modified
   * and fall back to a weak ETag only when it is the sole validator (Express itself accepts it).
   */
  private fun ifRangeValidator(): String? {
    val strongEtag = etag?.takeUnless { it.startsWith("W/") }
    return strongEtag ?: lastModified ?: etag
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
    /** Responses that retrying cannot fix: no download permission, or the file is gone. */
    val PERMANENT_FAILURE_CODES = setOf(403, 404, 410)
    val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
  }
}
