package com.audiobookshelf.app.managers

import android.util.Log
import com.google.common.net.HttpHeaders.CONTENT_LENGTH
import okhttp3.*
import java.io.*
import java.util.*

class InternalDownloadManager(outputStream:FileOutputStream, private val progressCallback: DownloadItemManager.InternalProgressCallback) : AutoCloseable {
  private val tag = "InternalDownloadManager"

  private val client: OkHttpClient = OkHttpClient()
  private val writer = BinaryFileWriter(outputStream, progressCallback)

  @Throws(IOException::class)
  fun download(url:String) {
    val request: Request = Request.Builder().url(url).build()
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.e(tag, "download URL $url FAILED")
        progressCallback.onComplete(true)
      }

      override fun onResponse(call: Call, response: Response) {
        val responseBody: ResponseBody = response.body
          ?: throw IllegalStateException("Response doesn't contain a file")

        val length: Long = (response.header(CONTENT_LENGTH, "1") ?: "0").toLong()
        writer.write(responseBody.byteStream(), length)
      }
    })
  }

  @Throws(Exception::class)
  override fun close() {
    writer.close()
  }
}

class BinaryFileWriter(outputStream: OutputStream, progressCallback: DownloadItemManager.InternalProgressCallback) :
  AutoCloseable {
  private val outputStream: OutputStream
  private val progressCallback: DownloadItemManager.InternalProgressCallback

  init {
    this.outputStream = outputStream
    this.progressCallback = progressCallback
  }

  @Throws(IOException::class)
  fun write(inputStream: InputStream?, length: Long): Long {
    BufferedInputStream(inputStream).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var readBytes: Int
      var totalBytes: Long = 0
      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes.toLong()
        outputStream.write(dataBuffer, 0, readBytes)
        progressCallback.onProgress(totalBytes, (totalBytes * 100L) / length)
      }
      progressCallback.onComplete(false)
      return totalBytes
    }
  }

  @Throws(IOException::class)
  override fun close() {
    outputStream.close()
  }

  companion object {
    private const val CHUNK_SIZE = 1024
  }
}
