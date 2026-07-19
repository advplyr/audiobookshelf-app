package com.audiobookshelf.app.managers

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) {
  private val tag = "InternalDownloadManager"
  private val client =
          OkHttpClient.Builder()
                  .connectTimeout(30, TimeUnit.SECONDS)
                  .readTimeout(60, TimeUnit.SECONDS)
                  .writeTimeout(60, TimeUnit.SECONDS)
                  .build()

  /**
   * Returns the active call so the queue can cancel a stalled transfer. A partial staging file is
   * retained only when the server proves that it honoured a subsequent range request.
   */
  fun download(url: String): Call {
    destinationFile.parentFile?.mkdirs()
    val existingBytes = destinationFile.takeIf { it.exists() }?.length() ?: 0L
    val request =
            Request.Builder()
                    .url(url)
                    .addHeader("Accept-Encoding", "identity")
                    .apply {
                      if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
                    }
                    .build()
    val call = client.newCall(request)
    call.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                Log.e(tag, "Download URL failed", e)
                progressCallback.onComplete(true)
              }

              override fun onResponse(call: Call, response: Response) {
                response.use {
                  try {
                    val append = existingBytes > 0L && response.code == 206 && hasExpectedRange(response, existingBytes)
                    if (existingBytes > 0L && !append && response.code != 200) {
                      Log.e(tag, "Invalid resume response ${response.code} for offset $existingBytes")
                      progressCallback.onComplete(true)
                      return
                    }
                    if (!response.isSuccessful || response.body == null) {
                      Log.e(tag, "Download HTTP failure ${response.code}")
                      progressCallback.onComplete(true)
                      return
                    }

                    val startingBytes = if (append) existingBytes else 0L
                    val responseLength = response.body!!.contentLength()
                    val totalLength =
                            if (expectedSize > 0L) expectedSize
                            else if (responseLength >= 0L) startingBytes + responseLength
                            else 0L

                    FileOutputStream(destinationFile, append).use { output ->
                      response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var totalBytes = startingBytes
                        while (true) {
                          val read = input.read(buffer)
                          if (read < 0) break
                          output.write(buffer, 0, read)
                          totalBytes += read
                          val progress = if (totalLength > 0L) (totalBytes * 100L) / totalLength else 0L
                          progressCallback.onProgress(totalBytes, progress.coerceAtMost(100L))
                        }
                      }
                    }

                    if (expectedSize > 0L && destinationFile.length() != expectedSize) {
                      Log.e(tag, "Downloaded size ${destinationFile.length()} did not match $expectedSize")
                      progressCallback.onComplete(true)
                    } else {
                      progressCallback.onComplete(false)
                    }
                  } catch (e: IOException) {
                    Log.e(tag, "Could not write staging file", e)
                    progressCallback.onComplete(true)
                  }
                }
              }
            }
    )
    return call
  }

  private fun hasExpectedRange(response: Response, offset: Long): Boolean {
    val range = response.header("Content-Range") ?: return false
    return range.startsWith("bytes $offset-")
  }

  private companion object {
    const val CHUNK_SIZE = 8 * 1024
  }
}
