package com.audiobookshelf.app

import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.callback.FolderPickerCallback
import com.anggrayudi.storage.callback.StorageAccessCallback
import com.anggrayudi.storage.file.*
import com.audiobookshelf.app.device.FolderScanner
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "StorageManager")
class StorageManager : Plugin() {
  private val TAG = "StorageManager"

  lateinit var mainActivity:MainActivity

  data class MediaFile(val uri: Uri, val name: String, val simplePath: String, val size: Long, val type: String, val isAudio: Boolean) {
    fun toJSObject() : JSObject {
      var obj = JSObject()
      obj.put("uri", this.uri)
      obj.put("name", this.name)
      obj.put("simplePath", this.simplePath)
      obj.put("size", this.size)
      obj.put("type", this.type)
      obj.put("isAudio", this.isAudio)
      return obj
    }
  }

  data class MediaFolder(val uri: Uri, val name: String, val simplePath: String, val mediaFiles:List<MediaFile>) {
    fun toJSObject() : JSObject {
      var obj = JSObject()
      obj.put("uri", this.uri)
      obj.put("name", this.name)
      obj.put("simplePath", this.simplePath)
      obj.put("files", this.mediaFiles.map { it.toJSObject() })
      return obj
    }
  }

  override fun load() {
    mainActivity = (activity as MainActivity)

    mainActivity.storage.storageAccessCallback = object : StorageAccessCallback {
      override fun onRootPathNotSelected(
        requestCode: Int,
        rootPath: String,
        uri: Uri,
        selectedStorageType: StorageType,
        expectedStorageType: StorageType
      ) {
        Log.d(TAG, "STORAGE ACCESS CALLBACK")
      }

      override fun onCanceledByUser(requestCode: Int) {
        Log.d(TAG, "STORAGE ACCESS CALLBACK")
      }

      override fun onExpectedStorageNotSelected(requestCode: Int, selectedFolder: DocumentFile, selectedStorageType: StorageType, expectedBasePath: String, expectedStorageType: StorageType) {
        Log.d(TAG, "STORAGE ACCESS CALLBACK")
      }

      override fun onStoragePermissionDenied(requestCode: Int) {
        Log.d(TAG, "STORAGE ACCESS CALLBACK")
      }

      override fun onRootPathPermissionGranted(requestCode: Int, root: DocumentFile) {
        Log.d(TAG, "STORAGE ACCESS CALLBACK")
      }
    }
  }

  @PluginMethod
  fun selectFolder(call: PluginCall) {
    mainActivity.storage.folderPickerCallback = object : FolderPickerCallback {
      override fun onFolderSelected(requestCode: Int, folder: DocumentFile) {
        Log.d(TAG, "ON FOLDER SELECTED ${folder.uri} ${folder.name}")

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
        Log.e(TAG, "STORAGE ACCESS DENIED")
        var jsobj = JSObject()
        jsobj.put("error", "Access Denied")
        call.resolve(jsobj)
      }

      override fun onStoragePermissionDenied(requestCode: Int) {
        Log.d(TAG, "STORAGE PERMISSION DENIED $requestCode")
        var jsobj = JSObject()
        jsobj.put("error", "Permission Denied")
        call.resolve(jsobj)
      }
    }

    mainActivity.storage.openFolderPicker(6)
  }

  @RequiresApi(Build.VERSION_CODES.R)
  @PluginMethod
  fun requestStoragePermission(call: PluginCall) {
    Log.d(TAG, "Request Storage Permissions")
    mainActivity.storageHelper.requestStorageAccess()
    call.resolve()
  }

  @PluginMethod
  fun checkStoragePermission(call: PluginCall) {
    var res = false
    if (Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
      res = SimpleStorage.hasStoragePermission(context)
      Log.d(TAG, "checkStoragePermission: Check Storage Access $res")
    } else {
      Log.d(TAG, "checkStoragePermission: Has permission on Android 10 or up")
      res = true
    }

    var jsobj = JSObject()
    jsobj.put("value", res)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun checkFolderPermissions(call: PluginCall) {
    var folderUrl = call.data.getString("folderUrl", "").toString()
      Log.d(TAG, "Check Folder Permissions for $folderUrl")

    var hasAccess = SimpleStorage.hasStorageAccess(context,folderUrl,true)

    var jsobj = JSObject()
    jsobj.put("value", hasAccess)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun searchFolder(call: PluginCall) {
    var folderUrl = call.data.getString("folderUrl", "").toString()
    var mediaType = call.data.getString("mediaType", "book").toString()
    Log.d(TAG, "Searching folder $folderUrl")

    var folderScanner = FolderScanner(context)
    var folderScanResult = folderScanner.scanForMediaItems(folderUrl, mediaType)
    if (folderScanResult == null) {
      Log.d(TAG, "NO Scan DATA")
      call.resolve(JSObject())
    } else {
      Log.d(TAG, "Scan DATA ${jacksonObjectMapper().writeValueAsString(folderScanResult)}")
      call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(folderScanResult)))
    }

//
//    var df: DocumentFile? = DocumentFileCompat.fromUri(context, Uri.parse(folderUrl))
//
//    if (df == null) {
//      Log.e(TAG, "Folder Doc File Invalid $folderUrl")
//      var jsobj = JSObject()
//      jsobj.put("folders", JSArray())
//      jsobj.put("files", JSArray())
//      call.resolve(jsobj)
//      return
//    }
//
//    Log.d(TAG, "Folder as DF ${df.isDirectory} | ${df.getSimplePath(context)} | ${df.getBasePath(context)} | ${df.name}")
//
//    var mediaFolders = mutableListOf<MediaFolder>()
//    var foldersFound = df.search(false, DocumentFileType.FOLDER)
//
//    foldersFound.forEach {
//      Log.d(TAG, "Iterating over Folder Found ${it.name} | ${it.getSimplePath(context)} | URI: ${it.uri}")
//      var folderName = it.name ?: ""
//      var mediaFiles = mutableListOf<MediaFile>()
//
//      var filesInFolder = it.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
//      filesInFolder.forEach { it2 ->
//        var mimeType = it2?.mimeType ?: ""
//        var filename = it2?.name ?: ""
//        var isAudio = mimeType.startsWith("audio")
//        Log.d(TAG, "Found $mimeType file $filename in folder $folderName")
//        var imageFile = MediaFile(it2.uri, filename, it2.getSimplePath(context), it2.length(), mimeType, isAudio)
//        mediaFiles.add(imageFile)
//      }
//      if (mediaFiles.size > 0) {
//        mediaFolders.add(MediaFolder(it.uri, folderName, it.getSimplePath(context), mediaFiles))
//      }
//    }
//
//    // Files in root dir
//    var rootMediaFiles = mutableListOf<MediaFile>()
//    var mediaFilesFound:List<DocumentFile> = df.search(false, DocumentFileType.FILE, arrayOf("audio/*", "image/*"))
//    mediaFilesFound.forEach {
//      Log.d(TAG, "Folder Root File Found ${it.name} | ${it.getSimplePath(context)} | URI: ${it.uri} | ${it.mimeType}")
//      var mimeType = it?.mimeType ?: ""
//      var filename = it?.name ?: ""
//      var isAudio = mimeType.startsWith("audio")
//      Log.d(TAG, "Found $mimeType file $filename in root folder")
//      var imageFile = MediaFile(it.uri, filename, it.getSimplePath(context), it.length(), mimeType, isAudio)
//      rootMediaFiles.add(imageFile)
//    }
//
//    var jsobj = JSObject()
//    jsobj.put("folders", mediaFolders.map{ it.toJSObject() })
//    jsobj.put("files", rootMediaFiles.map{ it.toJSObject() })
//    call.resolve(jsobj)
  }


  @PluginMethod
  fun delete(call: PluginCall) {
    var url = call.data.getString("url", "").toString()
    var coverUrl = call.data.getString("coverUrl", "").toString()
    var folderUrl = call.data.getString("folderUrl", "").toString()

    if (folderUrl != "") {
      Log.d(TAG, "CALLED DELETE FOLDER: $folderUrl")
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


  fun checkUriExists(uri: Uri?): Boolean {
    if (uri == null) return false
    val resolver = context.contentResolver
    var cursor: Cursor? = null
    return try {
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
  }
}
