package com.audiobookshelf.app

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.callback.FolderPickerCallback
import com.anggrayudi.storage.callback.StorageAccessCallback
import com.anggrayudi.storage.file.*
import com.anggrayudi.storage.media.FileDescription

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject
import java.io.File


@CapacitorPlugin(name = "AudioDownloader")
class AudioDownloader : Plugin() {
  private val tag = "AudioDownloader"

  lateinit var mainActivity:MainActivity
  lateinit var downloadManager:DownloadManager

  data class AudiobookItem(val uri: Uri, val name: String, val size: Long, val coverUrl: String) {
    fun toJSObject() : JSObject {
      var obj = JSObject()
      obj.put("uri", this.uri)
      obj.put("name", this.name)
      obj.put("size", this.size)
      obj.put("coverUrl", this.coverUrl)
      return obj
    }
  }

  override fun load() {
    mainActivity = (activity as MainActivity)
    downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
//    storage = SimpleStorage(mainActivity)

    var recieverEvent : (evt: String, id: Long) -> Unit = { evt: String, id: Long ->
      if (evt == "complete") {}
      if (evt == "clicked") {
        Log.d(tag, "Clicked $id back in the audiodownloader")
      }
    }
    mainActivity.registerBroadcastReceiver(recieverEvent)


    setupSimpleStorage()

    Log.d(tag, "Build SDK ${Build.VERSION.SDK_INT}")
    // Android 9 OR Below Request Permissions
//    if (Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
//      Log.d(tag, "Requires Permission")
////      storage.requestStorageAccess(9)
//      var jsobj = JSObject()
//      jsobj.put("value", "required")
//      notifyListeners("permission", jsobj)
//    } else {
//      Log.d(tag, "Does not request permission")
//    }
  }

  private fun setupSimpleStorage() {
    mainActivity.storageHelper.onFolderSelected = { requestCode, folder ->
      Log.d(tag, "FOLDER SELECTED $requestCode ${folder.name} ${folder.uri}")
      var jsobj = JSObject()
      jsobj.put("value", "granted")
      jsobj.put("uri", folder.uri)
      jsobj.put("absolutePath", folder.getAbsolutePath(context))
      jsobj.put("storageId", folder.getStorageId(context))
      jsobj.put("storageType", folder.getStorageType(context))
      jsobj.put("simplePath", folder.getSimplePath(context))
      jsobj.put("basePath", folder.getBasePath(context))
      notifyListeners("permission", jsobj)
    }

    mainActivity.storage.storageAccessCallback = object : StorageAccessCallback {
      override fun onRootPathNotSelected(
        requestCode: Int,
        rootPath: String,
        uri: Uri,
        selectedStorageType: StorageType,
        expectedStorageType: StorageType
      ) {
        Log.d(tag, "STORAGE ACCESS CALLBACK")
      }

      override fun onCanceledByUser(requestCode: Int) {
        Log.d(tag, "STORAGE ACCESS CALLBACK")
      }

      override fun onExpectedStorageNotSelected(requestCode: Int, selectedFolder: DocumentFile, selectedStorageType: StorageType, expectedBasePath: String, expectedStorageType: StorageType) {
        Log.d(tag, "STORAGE ACCESS CALLBACK")
      }

      override fun onStoragePermissionDenied(requestCode: Int) {
        Log.d(tag, "STORAGE ACCESS CALLBACK")
      }

      override fun onRootPathPermissionGranted(requestCode: Int, root: DocumentFile) {
        Log.d(tag, "STORAGE ACCESS CALLBACK")
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  @PluginMethod
  fun requestStoragePermission(call: PluginCall) {
    Log.d(tag, "Request Storage Permissions")
    mainActivity.storageHelper.requestStorageAccess()
    call.resolve()
  }

  @PluginMethod
  fun checkStoragePermission(call: PluginCall) {
    var res = false

    if (Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
      res = SimpleStorage.hasStoragePermission(context)
      Log.d(tag, "Check Storage Access $res")
    } else {
      Log.d(tag, "Has permission on Android 10 or up")
      res = true
    }

    var jsobj = JSObject()
    jsobj.put("value", res)
    call.resolve(jsobj)
  }

  fun checkUriExists(uri: Uri?): Boolean {
    if (uri == null) return false
    val resolver = context.contentResolver
    //1. Check Uri
    var cursor: Cursor? = null
    val isUriExist: Boolean = try {
      cursor = resolver.query(uri, null, null, null, null)
      //cursor null: content Uri was invalid or some other error occurred
      //cursor.moveToFirst() false: Uri was ok but no entry found.
      (cursor != null && cursor.moveToFirst())
    } catch (t: Throwable) {
     false
    } finally {
      try {
        cursor?.close()
      } catch (t: Throwable) {
      }
      false
    }
    return isUriExist
  }

    @PluginMethod
  fun load(call: PluginCall) {
    var audiobookUrls = call.data.getJSONArray("audiobookUrls")
    var len = audiobookUrls?.length()
    if (len == null) {
      len = 0
    }
    Log.d(tag, "CALLED LOAD $len")
    var audiobookItems:MutableList<AudiobookItem> = mutableListOf()

    (0 until len).forEach {
      var jsobj = audiobookUrls.get(it) as JSONObject
      var audiobookUrl = jsobj.get("contentUrl").toString()
      var coverUrl = jsobj.get("coverUrl").toString()
      var storageId = ""
      if(jsobj.has("storageId")) jsobj.get("storageId").toString()

      var basePath = ""
      if(jsobj.has("basePath")) jsobj.get("basePath").toString()

      var coverBasePath = ""
      if(jsobj.has("coverBasePath")) jsobj.get("coverBasePath").toString()

      Log.d(tag, "LOOKUP $storageId $basePath $audiobookUrl")

      var audiobookFile: DocumentFile? = null
      var coverFile: DocumentFile? = null

      // Android 9 OR Below use storage id and base path
      if (Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
        audiobookFile = DocumentFileCompat.fromSimplePath(context, storageId, basePath)
        if (coverUrl != null && coverUrl != "") {
          coverFile = DocumentFileCompat.fromSimplePath(context, storageId, coverBasePath)
        }
      } else {
        // Android 10 and up manually deleting will still load the file causing crash
        var exists = checkUriExists(Uri.parse(audiobookUrl))
        if (exists) {
          Log.d(tag, "Audiobook exists")
          audiobookFile = DocumentFileCompat.fromUri(context, Uri.parse(audiobookUrl))
        } else {
          Log.e(tag, "Audiobook does not exist")
        }

        var coverExists = checkUriExists(Uri.parse(coverUrl))
        if (coverExists) {
          Log.d(tag, "Cover Exists")
          coverFile = DocumentFileCompat.fromUri(context, Uri.parse(coverUrl))
        } else if (coverUrl != null && coverUrl != "") {
          Log.e(tag, "Cover does not exist")
        }
      }

      if (audiobookFile == null) {
        Log.e(tag, "Audiobook was not found $audiobookUrl")
      } else {
        Log.d(tag, "Audiobook File Found StorageId:${audiobookFile.getStorageId(context)} | AbsolutePath:${audiobookFile.getAbsolutePath(context)} | BasePath:${audiobookFile.getBasePath(context)}")

        var _name = audiobookFile.name
        if (_name == null) _name = ""

        var size = audiobookFile.length()

        if (audiobookFile.uri.toString() !== audiobookUrl) {
          Log.d(tag, "Audiobook URI ${audiobookFile.uri} is different from $audiobookUrl => using the latter")
        }

        // Use existing URI's - bug happening where new uri is different from initial
        var abItem = AudiobookItem(Uri.parse(audiobookUrl), _name, size, coverUrl)

        Log.d(tag, "Setting AB ITEM ${abItem.name} | ${abItem.size} | ${abItem.uri} | ${abItem.coverUrl}")

        audiobookItems.add(abItem)
      }
    }

    Log.d(tag, "Load Finished ${audiobookItems.size} found")

    var audiobookObjs:List<JSObject> = audiobookItems.map{ it.toJSObject() }
    var mediaItemNoticePayload = JSObject()
    mediaItemNoticePayload.put("items", audiobookObjs)
    notifyListeners("onMediaLoaded", mediaItemNoticePayload)
  }

  @PluginMethod
  fun download(call: PluginCall) {
    var audiobookId = call.data.getString("audiobookId", "audiobook").toString()
    var url = call.data.getString("downloadUrl", "unknown").toString()
    var coverDownloadUrl = call.data.getString("coverDownloadUrl", "").toString()
    var title = call.data.getString("title", "Audiobook").toString()
    var filename = call.data.getString("filename", "audiobook.mp3").toString()
    var coverFilename = call.data.getString("coverFilename", "cover.png").toString()
    var downloadFolderUrl = call.data.getString("downloadFolderUrl", "").toString()
    var folder = DocumentFileCompat.fromUri(context, Uri.parse(downloadFolderUrl))!!
    Log.d(tag, "Called download: $url | Folder: ${folder.name} | $downloadFolderUrl")

    var dlfilename = audiobookId + "." + File(filename).extension
    var coverdlfilename = audiobookId + "." + File(coverFilename).extension
    Log.d(tag, "DL Filename $dlfilename | Cover DL Filename $coverdlfilename")

    var canWriteToFolder = folder.canWrite()
    if (!canWriteToFolder) {
      Log.e(tag, "Error Cannot Write to Folder ${folder.baseName}")
      val ret = JSObject()
      ret.put("error", "Cannot write to ${folder.baseName}")
      call.resolve(ret)
      return
    }

    var dlRequest = DownloadManager.Request(Uri.parse(url))
    dlRequest.setTitle("Ab: $title")
    dlRequest.setDescription("Downloading to ${folder.name}")
    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, dlfilename)

    var audiobookDownloadId = downloadManager.enqueue(dlRequest)
    var coverDownloadId:Long? = null

    if (coverDownloadUrl != "") {
      var coverDlRequest = DownloadManager.Request(Uri.parse(coverDownloadUrl))
      coverDlRequest.setTitle("Cover: $title")
      coverDlRequest.setDescription("Downloading to ${folder.name}")
      coverDlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)
      coverDlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, coverdlfilename)
      coverDownloadId = downloadManager.enqueue(coverDlRequest)
    }

    var progressReceiver : (id:Long, prog: Long) -> Unit = { id:Long, prog: Long ->
      if (id == audiobookDownloadId) {
        var jsobj = JSObject()
        jsobj.put("audiobookId", audiobookId)
        jsobj.put("progress", prog)
        notifyListeners("onDownloadProgress", jsobj)
      }
    }

    var coverDocFile:DocumentFile? = null

    var doneReceiver : (id:Long, success: Boolean) -> Unit = { id:Long, success: Boolean ->
      Log.d(tag, "RECEIVER DONE $id, SUCCES? $success")
      var docfile:DocumentFile? = null

      // Download was complete, now find downloaded file
      if (id == coverDownloadId) {
        docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, coverdlfilename)
        Log.d(tag, "Move Cover File ${docfile?.name}")

        // For unknown reason, Android 10 test was using the title set in "setTitle" for the dl manager as the filename
        //  check if this was the case
        if (docfile?.name == null) {
          docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, "Cover: $title")
          Log.d(tag, "Cover File name attempt 2 ${docfile?.name}")
        }
      } else if (id == audiobookDownloadId) {
        docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, dlfilename)
        Log.d(tag, "Move Audiobook File ${docfile?.name}")

        if (docfile?.name == null) {
          docfile = DocumentFileCompat.fromPublicFolder(context, PublicDirectory.DOWNLOADS, "Ab: $title")
          Log.d(tag, "File name attempt 2 ${docfile?.name}")
        }
      }

      // Callback for moving the downloaded file
      var callback = object : FileCallback() {
        override fun onPrepare() {
          Log.d(tag, "PREPARING MOVE FILE")
        }
        override fun onFailed(errorCode:ErrorCode) {
          Log.e(tag, "FAILED MOVE FILE $errorCode")

          docfile?.delete()
          coverDocFile?.delete()

          if (id == audiobookDownloadId) {
            var jsobj = JSObject()
            jsobj.put("audiobookId", audiobookId)
            jsobj.put("error", "Move failed")
            notifyListeners("onDownloadFailed", jsobj)
          }
        }
        override fun onCompleted(result:Any) {
          var resultDocFile = result as DocumentFile
          var simplePath = resultDocFile.getSimplePath(context)
          var storageId = resultDocFile.getStorageId(context)
          var size = resultDocFile.length()
          Log.d(tag, "Finished Moving File, NAME: ${resultDocFile.name} | URI:${resultDocFile.uri} | AbsolutePath:${resultDocFile.getAbsolutePath(context)} | $storageId | SimplePath: $simplePath")

          var abFolder = folder.findFolder(title)
          var jsobj = JSObject()
          jsobj.put("audiobookId", audiobookId)
          jsobj.put("downloadId", id)
          jsobj.put("storageId", storageId)
          jsobj.put("storageType", resultDocFile.getStorageType(context))
          jsobj.put("folderUrl", abFolder?.uri)
          jsobj.put("folderName", abFolder?.name)
          jsobj.put("downloadFolderUrl", downloadFolderUrl)
          jsobj.put("contentUrl", resultDocFile.uri)
          jsobj.put("basePath", resultDocFile.getBasePath(context))
          jsobj.put("filename", filename)
          jsobj.put("simplePath", simplePath)
          jsobj.put("size", size)

          if (resultDocFile.name == filename) {
            Log.d(tag, "Audiobook Finishing Moving")
          } else if (resultDocFile.name == coverFilename) {
            coverDocFile = docfile
            Log.d(tag, "Audiobook Cover Finished Moving")
            jsobj.put("isCover", true)
          }
          notifyListeners("onDownloadComplete", jsobj)
        }
      }

      // After file is downloaded, move the files into an audiobook directory inside the user selected folder
        if (id == coverDownloadId) {
          docfile?.moveFileTo(context, folder, FileDescription(coverFilename, title, MimeType.IMAGE), callback)
        } else if (id == audiobookDownloadId) {
          docfile?.moveFileTo(context, folder, FileDescription(filename, title, MimeType.AUDIO), callback)
        }
    }

    var progressUpdater = DownloadProgressUpdater(downloadManager, audiobookDownloadId, progressReceiver, doneReceiver)
    progressUpdater.run()
    if (coverDownloadId != null) {
      var coverProgressUpdater = DownloadProgressUpdater(downloadManager, coverDownloadId, progressReceiver, doneReceiver)
      coverProgressUpdater.run()
    }

    val ret = JSObject()
    ret.put("audiobookDownloadId", audiobookDownloadId)
    ret.put("coverDownloadId", coverDownloadId)
    call.resolve(ret)
  }

  @PluginMethod
  fun selectFolder(call: PluginCall) {
    mainActivity.storage.folderPickerCallback = object : FolderPickerCallback {
      override fun onFolderSelected(requestCode: Int, folder: DocumentFile) {
        Log.d(tag, "ONF OLDER SELECRTED ${folder.uri} ${folder.name}")

        var absolutePath = folder.getAbsolutePath(activity)
        var storageId = folder.getStorageId(activity)
        var storageType = folder.getStorageType(activity)
        var simplePath = folder.getSimplePath(activity)
        var basePath = folder.getBasePath(activity)

        var jsobj = JSObject()
        jsobj.put("uri", folder.uri)
        jsobj.put("absolutePath", absolutePath)
        jsobj.put("storageId", storageId)
        jsobj.put("storageType", storageType)
        jsobj.put("simplePath", simplePath)
        jsobj.put("basePath", basePath)
        call.resolve(jsobj)
      }

      override fun onStorageAccessDenied(requestCode: Int, folder: DocumentFile?, storageType: StorageType) {
        Log.e(tag, "STORAGE ACCESS DENIED")
        var jsobj = JSObject()
        jsobj.put("error", "Access Denied")
        call.resolve(jsobj)
      }

      override fun onStoragePermissionDenied(requestCode: Int) {
        Log.d(tag, "STORAGE PERMISSION DENIED $requestCode")
        var jsobj = JSObject()
        jsobj.put("error", "Permission Denied")
        call.resolve(jsobj)
      }
    }
    mainActivity.storage.openFolderPicker(6)
  }

  @PluginMethod
  fun delete(call: PluginCall) {
    var url = call.data.getString("url", "").toString()
    var coverUrl = call.data.getString("coverUrl", "").toString()
    var folderUrl = call.data.getString("folderUrl", "").toString()

    if (folderUrl != "") {
      Log.d(tag, "CALLED DELETE FIOLDER: $folderUrl")
      var folder = DocumentFileCompat.fromUri(context, Uri.parse(folderUrl))
      var success = folder?.deleteRecursively(context)
      var jsobj = JSObject()
      jsobj.put("success", success)
      call.resolve()
    } else {
      // Older audiobooks did not store a folder url, use cover and audiobook url
      var abExists = checkUriExists(Uri.parse(url))
      if (abExists) {
        var abfile = DocumentFileCompat.fromUri(context, Uri.parse(url))
        abfile?.delete()
      }

      var coverExists = checkUriExists(Uri.parse(coverUrl))
      if (coverExists) {
        var coverfile = DocumentFileCompat.fromUri(context, Uri.parse(coverUrl))
        coverfile?.delete()
      }
    }
  }

  internal class DownloadProgressUpdater(private val manager: DownloadManager, private val downloadId: Long, private var receiver: (Long, Long) -> Unit, private var doneReceiver: (Long, Boolean) -> Unit) : Thread() {
    private val query: DownloadManager.Query = DownloadManager.Query()
    private var totalBytes: Int = 0
    private var TAG = "DownloadProgressUpdater"

    init {
      query.setFilterById(this.downloadId)
    }

    override fun run() {
      Log.d(TAG, "RUN FOR ID $downloadId")
      var keepRunning = true
      var increment = 0
      while (keepRunning) {
        Thread.sleep(500)
        increment++

        if (increment % 4 == 0) {
          Log.d(TAG, "Loop $increment : $downloadId")
        }

        manager.query(query).use {
          if (it.moveToFirst()) {
            //get total bytes of the file
            if (totalBytes <= 0) {
              totalBytes = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
              if (totalBytes <= 0) {
                Log.e(TAG, "Download Is 0 Bytes $downloadId")
                doneReceiver(downloadId, false)
                keepRunning = false
                this.interrupt()
                return
              }
            }

            val downloadStatus = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
            val bytesDownloadedSoFar = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

            if (increment % 4 == 0) {
              Log.d(TAG, "BYTES $increment : $downloadId : $bytesDownloadedSoFar : TOTAL: $totalBytes")
            }

            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL || downloadStatus == DownloadManager.STATUS_FAILED) {
              if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                doneReceiver(downloadId, true)
              } else {
                doneReceiver(downloadId, false)
              }
              keepRunning = false
              this.interrupt()
            } else {
              //update progress
              val percentProgress = ((bytesDownloadedSoFar * 100L) / totalBytes)
              receiver(downloadId, percentProgress)
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
