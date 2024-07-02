package com.audiobookshelf.app.managers

import android.util.Log
import com.google.common.net.HttpHeaders.CONTENT_LENGTH
import okhttp3.*
import java.io.*
import java.util.*

class InternalDownloadManager(private val file:File, private val progressCallback: DownloadItemManager.InternalProgressCallback) : AutoCloseable {
  private val tag = "InternalDownloadManager"
  private val client: OkHttpClient = OkHttpClient()
  private val maxRetries: Int = 10  //Not sure what number this should be.  But at least 5

  @Throws(IOException::class)
  fun download(url:String, retryCount: Int=0, initialBytesWritten: Long = 0) {
    val existingFileSize: Long = if(file.exists())  file.length() else 0;
    val request: Request = Request.Builder()
      .url(url)
      .addHeader("Range","bytes=$existingFileSize-")
      .build()

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.e(tag, "download URL $url FAILED: ${e.message}")
        progressCallback.onComplete(true)
      }

      override fun onResponse(call: Call, response: Response) {
        val responseBody: ResponseBody = response.body
          ?: throw IllegalStateException("Response doesn't contain a file")

        val length: Long = (response.header(CONTENT_LENGTH, "1") ?: "0").toLong() + existingFileSize

        try{
          FileOutputStream(file,true).use {fos ->
            BinaryFileWriter(fos, progressCallback).use {writer ->
              writer.write(responseBody.byteStream(), length, existingFileSize)
            }
          }

        } catch(e:IOException){
          Log.e(tag,"Error Writing, Trying again... $retryCount: ${e.message}")
          if(retryCount<maxRetries) download(url, retryCount+1, existingFileSize)
          else progressCallback.onComplete(true)
        }

      }
    })
  }

  @Throws(Exception::class)
  override fun close() {
  }
}

class BinaryFileWriter(outputStream: OutputStream,
                       progressCallback: DownloadItemManager.InternalProgressCallback ) :
  AutoCloseable {
  private val outputStream: OutputStream
  private val progressCallback: DownloadItemManager.InternalProgressCallback

  init {
    this.outputStream = outputStream
    this.progressCallback = progressCallback
  }

  @Throws(IOException::class)
  fun write(inputStream: InputStream?, length: Long, initialBytesWritten: Long): Long {
    BufferedInputStream(inputStream).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var readBytes: Int
      var totalBytes: Long = initialBytesWritten
      var i: Int = 0;
      try {
        while (input.read(dataBuffer).also { readBytes = it } != -1) {
          totalBytes += readBytes.toLong()
          outputStream.write(dataBuffer, 0, readBytes)
          i++;
          if(i%1000==0) Log.d("Writer","Progress Info: $totalBytes")
          progressCallback.onProgress(totalBytes, (totalBytes * 100L) / length)
        }
        progressCallback.onComplete(false)
      } catch (e:IOException) {
        Log.e("BinaryFileWriter","IOException while writing: ${e.message}")
        throw e
      }
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
