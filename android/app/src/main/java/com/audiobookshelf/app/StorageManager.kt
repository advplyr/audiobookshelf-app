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
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "StorageManager")
class StorageManager : Plugin() {
  private val TAG = "StorageManager"

  lateinit var mainActivity:MainActivity

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
    var mediaType = call.data.getString("mediaType", "book").toString()

    mainActivity.storage.folderPickerCallback = object : FolderPickerCallback {
      override fun onFolderSelected(requestCode: Int, folder: DocumentFile) {
        Log.d(TAG, "ON FOLDER SELECTED ${folder.uri} ${folder.name}")
        var absolutePath = folder.getAbsolutePath(activity)
        var storageType = folder.getStorageType(activity)
        var simplePath = folder.getSimplePath(activity)
        var folderId = android.util.Base64.encodeToString(folder.id.toByteArray(), android.util.Base64.DEFAULT)

        var localFolder = LocalFolder(folderId, folder.name, folder.uri.toString(),absolutePath, simplePath, storageType.toString(), mediaType)

        DeviceManager.dbManager.saveLocalFolder(localFolder)
        call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(localFolder)))
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
  fun scanFolder(call: PluginCall) {
    var folderId =  call.data.getString("folderId", "").toString()
    var forceAudioProbe = call.data.getBoolean("forceAudioProbe")
    Log.d(TAG, "Scan Folder $folderId | Force Audio Probe $forceAudioProbe")

    var folder: LocalFolder? = DeviceManager.dbManager.getLocalFolder(folderId)
    folder?.let {
      var folderScanner = FolderScanner(context)
      var folderScanResult = folderScanner.scanForMediaItems(it, forceAudioProbe)
      if (folderScanResult == null) {
        Log.d(TAG, "NO Scan DATA")
        return call.resolve(JSObject())
      } else {
        Log.d(TAG, "Scan DATA ${jacksonObjectMapper().writeValueAsString(folderScanResult)}")
        return call.resolve(JSObject(jacksonObjectMapper().writeValueAsString(folderScanResult)))
      }
    } ?: call.resolve(JSObject())
  }

  @PluginMethod
  fun removeFolder(call: PluginCall) {
    var folderId = call.data.getString("folderId", "").toString()
    DeviceManager.dbManager.removeLocalFolder(folderId)
    call.resolve()
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
