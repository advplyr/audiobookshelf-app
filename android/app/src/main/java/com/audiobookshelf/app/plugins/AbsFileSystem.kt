package com.audiobookshelf.app.plugins

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
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "AbsFileSystem")
class AbsFileSystem : Plugin() {
  private val TAG = "AbsFileSystem"
  private val tag = "AbsFileSystem"
  var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity

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
        var basePath = folder.getBasePath(activity)
        var folderId = android.util.Base64.encodeToString(folder.id.toByteArray(), android.util.Base64.DEFAULT)

        var localFolder = LocalFolder(folderId, folder.name ?: "", folder.uri.toString(),basePath,absolutePath, simplePath, storageType.toString(), mediaType)

        DeviceManager.dbManager.saveLocalFolder(localFolder)
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localFolder)))
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
    var res: Boolean
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
        Log.d(TAG, "Scan DATA ${jacksonMapper.writeValueAsString(folderScanResult)}")
        return call.resolve(JSObject(jacksonMapper.writeValueAsString(folderScanResult)))
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
  fun removeLocalLibraryItem(call: PluginCall) {
    var localLibraryItemId = call.data.getString("localLibraryItemId", "").toString()
    DeviceManager.dbManager.removeLocalLibraryItem(localLibraryItemId)
    call.resolve()
  }

  @PluginMethod
  fun scanLocalLibraryItem(call: PluginCall) {
    var localLibraryItemId = call.data.getString("localLibraryItemId", "").toString()
    var forceAudioProbe = call.data.getBoolean("forceAudioProbe")
    Log.d(TAG, "Scan Local library item $localLibraryItemId | Force Audio Probe $forceAudioProbe")
    GlobalScope.launch(Dispatchers.IO) {
      var localLibraryItem: LocalLibraryItem? = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
      localLibraryItem?.let {
        var folderScanner = FolderScanner(context)
        var scanResult = folderScanner.scanLocalLibraryItem(it, forceAudioProbe)
        if (scanResult == null) {
          Log.d(TAG, "NO Scan DATA")
          call.resolve(JSObject())
        } else {
          Log.d(TAG, "Scan DATA ${jacksonMapper.writeValueAsString(scanResult)}")
          call.resolve(JSObject(jacksonMapper.writeValueAsString(scanResult)))
        }
      } ?: call.resolve(JSObject())
    }
  }

  @PluginMethod
  fun deleteItem(call: PluginCall) {
    var localLibraryItemId = call.data.getString("id", "").toString()
    var absolutePath = call.data.getString("absolutePath", "").toString()
    var contentUrl = call.data.getString("contentUrl", "").toString()
    Log.d(tag, "deleteItem $absolutePath | $contentUrl")

    var docfile = DocumentFileCompat.fromUri(mainActivity, Uri.parse(contentUrl))
    var success = docfile?.delete() == true
    if (success) {
      DeviceManager.dbManager.removeLocalLibraryItem(localLibraryItemId)
    }
    call.resolve(JSObject("{\"success\":$success}"))
  }

  @PluginMethod
  fun deleteTrackFromItem(call: PluginCall) {
    var localLibraryItemId = call.data.getString("id", "").toString()
    var trackLocalFileId = call.data.getString("trackLocalFileId", "").toString()
    var contentUrl = call.data.getString("trackContentUrl", "").toString()
    Log.d(tag, "deleteTrackFromItem $contentUrl")

    var localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
    if (localLibraryItem == null) {
      Log.e(tag, "deleteTrackFromItem: LLI does not exist $localLibraryItemId")
      return call.resolve(JSObject("{\"success\":false}"))
    }

    var docfile = DocumentFileCompat.fromUri(mainActivity, Uri.parse(contentUrl))
    var success = docfile?.delete() == true
    if (success) {
      localLibraryItem.media.removeAudioTrack(trackLocalFileId)
      localLibraryItem.removeLocalFile(trackLocalFileId)
      DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
    } else {
      call.resolve(JSObject("{\"success\":false}"))
    }
  }
}
