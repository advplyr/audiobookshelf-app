package com.audiobookshelf.app

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File


@CapacitorPlugin(name = "AudioDownloader")
class AudioDownloader : Plugin() {
  private val tag = "AudioDownloader"

  lateinit var mainActivity:MainActivity
  lateinit var downloadManager:DownloadManager

  data class AudiobookDownload(val url: String, val filename: String, val downloadId: Long)
  var downloads:MutableList<AudiobookDownload> = mutableListOf()

  data class CoverItem(val name: String, val coverUrl: String)
  data class AudiobookItem(val id: Long, val uri: Uri, val name: String, val size: Int, val duration: Int, val coverUrl: String) {
    fun toJSObject() : JSObject {
      var obj = JSObject()
      obj.put("id", this.id)
      obj.put("uri", this.uri)
      obj.put("name", this.name)
      obj.put("size", this.size)
      obj.put("duration", this.duration)
      obj.put("coverUrl", this.coverUrl)
      return obj
    }
  }
  var audiobookItems:MutableList<AudiobookItem> = mutableListOf()

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    var recieverEvent : (evt: String, id: Long) -> Unit = { evt: String, id: Long ->
        Log.d(tag, "RECEIVE EVT $evt $id")
      if (evt == "complete") {
        var path = downloadManager.getUriForDownloadedFile(id)

        var download = downloads.find { it.downloadId == id }
        var filename = download?.filename

        var jsobj = JSObject()
        jsobj.put("downloadId", id)
        jsobj.put("contentUrl", path)
        jsobj.put("filename", filename)
        notifyListeners("onDownloadComplete", jsobj)
        downloads = downloads.filter { it.downloadId != id } as MutableList<AudiobookDownload>
      }
      if (evt == "clicked") {
        Log.d(tag, "Clicked $id back in the audiodownloader")
      }
    }

    mainActivity.registerBroadcastReceiver(recieverEvent)
  }

  fun loadAudiobooks() {
    var covers = loadCovers()

    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.SIZE,
      MediaStore.Audio.Media.IS_AUDIOBOOK,
      MediaStore.Audio.Media.RELATIVE_PATH
    )

    var _audiobookItems:MutableList<AudiobookItem> = mutableListOf()
    val selection = "${MediaStore.Audio.Media.IS_AUDIOBOOK} == ?"
    val selectionArgs = arrayOf("1")
    val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

    activity.applicationContext.contentResolver.query(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
      projection,
      selection,
      selectionArgs,
      sortOrder
    )?.use { cursor ->

      val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
      val nameColumn =
        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
      val durationColumn =
        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
      val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
      val isAudiobookColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_AUDIOBOOK)
      var relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idColumn)
        val name = cursor.getString(nameColumn)
        val duration = cursor.getInt(durationColumn)
        val size = cursor.getInt(sizeColumn)
        var isAudiobook = cursor.getInt(isAudiobookColumn)
        var relativePath = cursor.getString(relativePathColumn)

        if (isAudiobook == 1) {
          val contentUri: Uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            id
          )

          Log.d(tag, "Got Content FRom MEdia STORE $id $contentUri, Name: $name, Dur: $duration, Size: $size, relativePath: $relativePath")
          var audiobookId = File(name).nameWithoutExtension
          var coverItem:CoverItem? = covers.find{it.name == audiobookId}
          var coverUrl = coverItem?.coverUrl ?: ""

          _audiobookItems.add(AudiobookItem(id, contentUri, name, duration, size, coverUrl))
        }
      }
      audiobookItems = _audiobookItems

      var audiobookObjs:List<JSObject> = _audiobookItems.map{ it.toJSObject() }

      var mediaItemNoticePayload = JSObject()
      mediaItemNoticePayload.put("items", audiobookObjs)
      notifyListeners("onMediaLoaded", mediaItemNoticePayload)
    }
  }

  fun loadCovers() : MutableList<CoverItem> {
    val projection = arrayOf(
      MediaStore.Images.Media._ID,
      MediaStore.Images.Media.DISPLAY_NAME
    )
    val sortOrder = "${MediaStore.Images.Media.DISPLAY_NAME} ASC"

    var coverItems:MutableList<CoverItem> = mutableListOf()

    activity.applicationContext.contentResolver.query(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      projection,
      null,
      null,
      sortOrder
    )?.use { cursor ->
      val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
      val nameColumn =
        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idColumn)
        val filename = cursor.getString(nameColumn)
        val contentUri: Uri = ContentUris.withAppendedId(
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          id
        )

        var name = File(filename).nameWithoutExtension
          Log.d(tag, "Got IMAGE FRom Media STORE $id $contentUri, Name: $name")

        var coverItem = CoverItem(name, contentUri.toString())
        coverItems.add(coverItem)
      }
    }
    return coverItems
  }

  @PluginMethod
  fun load(call: PluginCall) {
    loadAudiobooks()
    call.resolve()
  }

  @PluginMethod
  fun downloadCover(call: PluginCall) {
    var audiobookId = call.data.getString("audiobookId", "audiobook").toString()
    var url = call.data.getString("downloadUrl", "unknown").toString()
    var title = call.data.getString("title", "Cover").toString()
    var filename = call.data.getString("filename", "audiobook.jpg").toString()

    Log.d(tag, "Called download cover: $url")

    var dlRequest = DownloadManager.Request(Uri.parse(url))
    dlRequest.setTitle("Cover Art: $title")
    dlRequest.setDescription("Cover art for audiobook")
    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)

    var file:File = File(audiobookId, filename)
    Log.d(tag, "FILE ${file.path} | ${file.canonicalPath}")
    dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_AUDIOBOOKS, file.path)
    var downloadId = downloadManager.enqueue(dlRequest)

    var progressReceiver : (prog: Long) -> Unit = { prog: Long ->
      //
    }

    var doneReceiver : (success: Boolean) -> Unit = { success: Boolean ->
      var jsobj = JSObject()
      if (success) {
        var path = downloadManager.getUriForDownloadedFile(downloadId)
        jsobj.put("url", path)
        call.resolve(jsobj)
      } else {
        jsobj.put("failed", true)
        call.resolve(jsobj)
      }
    }

    var progressUpdater = DownloadProgressUpdater(downloadManager, downloadId, progressReceiver, doneReceiver)
    progressUpdater.run()
  }

  @PluginMethod
  fun download(call: PluginCall) {
    var audiobookId = call.data.getString("audiobookId", "audiobook").toString()
    var url = call.data.getString("downloadUrl", "unknown").toString()
    var title = call.data.getString("title", "Audiobook").toString()
    var filename = call.data.getString("filename", "audiobook.mp3").toString()

    Log.d(tag, "Called download: $url")

    var dlRequest = DownloadManager.Request(Uri.parse(url))
    dlRequest.setTitle(title)
    dlRequest.setDescription("Downloading to Audiobooks directory")
    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)

    var file:File = File(audiobookId, filename)
    Log.d(tag, "FILE ${file.path} | ${file.canonicalPath}")
    dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_AUDIOBOOKS, file.path)

    var downloadId = downloadManager.enqueue(dlRequest)

    var download = AudiobookDownload(url, filename, downloadId)
    downloads.add(download)

    var progressReceiver : (prog: Long) -> Unit = { prog: Long ->
      var jsobj = JSObject()
      jsobj.put("filename", filename)
      jsobj.put("downloadId", downloadId)
      jsobj.put("progress", prog)
      notifyListeners("onDownloadProgress", jsobj)
    }

    var doneReceiver : (success: Boolean) -> Unit = { success: Boolean ->
      Log.d(tag, "RECIEVER DONE, SUCCES? $success")
    }

    var progressUpdater = DownloadProgressUpdater(downloadManager, downloadId, progressReceiver, doneReceiver)
    progressUpdater.run()

    val ret = JSObject()
    ret.put("value", downloadId)
    call.resolve(ret)
  }

  @PluginMethod
  fun delete(call: PluginCall) {
    var audiobookId = call.data.getString("audiobookId", "audiobook").toString()
    var filename = call.data.getString("filename", "audiobook.mp3").toString()
    var url = call.data.getString("url", "").toString()
    var coverUrl = call.data.getString("coverUrl", "").toString()

    // Does Not Work
//    var audiobookDirRoot = activity.applicationContext.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS)
//    Log.d(tag, "AUDIOBOOK DIR ROOT $audiobookDirRoot")
//    var result = audiobookDirRoot?.deleteRecursively()
//    Log.d(tag, "DONE DELETING FOLDER $result")

    // Does Not Work
//    var audiobookDir = File(audiobookDirRoot, audiobookId + "/")
//    Log.d(tag, "Delete Audiobook DIR ${audiobookDir.path} is dir ${audiobookDir.isDirectory}")
//    var result = audiobookDir.deleteRecursively()
//

    // Does Not Work
//    var audiobookDir = activity.applicationContext.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS)
//    Log.d(tag, "AUDIOBOOK DIR ${audiobookDir?.path}")
//    var dir = File(audiobookDir, "$audiobookId/")
//    Log.d(tag, "DIR DIR ${dir.path}")
//    var res = dir.delete()
//    Log.d(tag, "DELETED $res")

    var contentResolver = activity.applicationContext.contentResolver
    contentResolver.delete(Uri.parse(url), null, null)

    if (coverUrl != "") {
      contentResolver.delete(Uri.parse(coverUrl), null, null)
    }

    call.resolve()
  }

  internal class DownloadProgressUpdater(private val manager: DownloadManager, private val downloadId: Long, private var receiver: (Long) -> Unit, private var doneReceiver: (Boolean) -> Unit) : Thread() {
    private val query: DownloadManager.Query = DownloadManager.Query()
    private var totalBytes: Int = 0
    private var TAG = "DownloadProgressUpdater"

    init {
      query.setFilterById(this.downloadId)
    }

    override fun run() {
      Log.d(TAG, "RUN FOR ID $downloadId")
      var keepRunning = true
      while (keepRunning) {
        Thread.sleep(500)

        manager.query(query).use {
          if (it.moveToFirst()) {

            //get total bytes of the file
            if (totalBytes <= 0) {
              totalBytes = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }

            val downloadStatus = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
            val bytesDownloadedSoFar = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL || downloadStatus == DownloadManager.STATUS_FAILED) {
              if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                doneReceiver(true)
              } else {
                doneReceiver(false)
              }
              keepRunning = false
              this.interrupt()
            } else {
              //update progress
              val percentProgress = ((bytesDownloadedSoFar * 100L) / totalBytes)
              receiver(percentProgress)
            }

          } else {
            Log.e(TAG, "NOT FOUND IN QUERY")
            keepRunning = false
          }
        }
      }
    }

  }
}
