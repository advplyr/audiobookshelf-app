package com.bookshelf.app.plugins

import android.app.AlertDialog
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
import com.bookshelf.app.MainActivity
import com.bookshelf.app.data.LocalFolder
import com.bookshelf.app.data.LocalLibraryItem
import com.bookshelf.app.device.DeviceManager
import com.bookshelf.app.device.FolderScanner
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
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

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
    val mediaType = call.data.getString("mediaType", "book").toString()
    val REQUEST_CODE_SELECT_FOLDER = 6
    val REQUEST_CODE_SDCARD_ACCESS = 7

    mainActivity.storage.folderPickerCallback = object : FolderPickerCallback {
      override fun onFolderSelected(requestCode: Int, folder: DocumentFile) {
        Log.d(TAG, "ON FOLDER SELECTED ${folder.uri} ${folder.name}")
        val absolutePath = folder.getAbsolutePath(activity)
        val storageType = folder.getStorageType(activity)
        val simplePath = folder.getSimplePath(activity)
        val basePath = folder.getBasePath(activity)
        val folderId = android.util.Base64.encodeToString(folder.id.toByteArray(), android.util.Base64.DEFAULT)

        val localFolder = LocalFolder(folderId, folder.name ?: "", folder.uri.toString(),basePath,absolutePath, simplePath, storageType.toString(), mediaType)

        DeviceManager.dbManager.saveLocalFolder(localFolder)
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localFolder)))
      }

      override fun onStorageAccessDenied(
        requestCode: Int,
        folder: DocumentFile?,
        storageType: StorageType,
        storageId: String
      ) {
        Log.e(tag, "Storage Access Denied ${folder?.getAbsolutePath(mainActivity)}")

        val jsobj = JSObject()
        if (requestCode == REQUEST_CODE_SELECT_FOLDER) {

          val builder: AlertDialog.Builder = AlertDialog.Builder(mainActivity)
          builder.setMessage(
            "You have no write access to this storage, thus selecting this folder is useless." +
              "\nWould you like to grant access to this folder?")
          builder.setNegativeButton("Dont Allow") { _, _ ->
            run {
              jsobj.put("error", "User Canceled, Access Denied")
              call.resolve(jsobj)
            }
          }
          builder.setPositiveButton("Allow.") { _, _ -> mainActivity.storageHelper.requestStorageAccess(REQUEST_CODE_SDCARD_ACCESS, initialPath = FileFullPath(mainActivity, storageId, "")) }
          builder.show()
        } else {
          Log.d(TAG, "STORAGE ACCESS DENIED $requestCode")
          jsobj.put("error", "Access Denied")
          call.resolve(jsobj)
        }
      }


      override fun onStoragePermissionDenied(requestCode: Int) {
        Log.d(TAG, "STORAGE PERMISSION DENIED $requestCode")
        val jsobj = JSObject()
        jsobj.put("error", "Permission Denied")
        call.resolve(jsobj)
      }

    }

    mainActivity.storage.openFolderPicker(REQUEST_CODE_SELECT_FOLDER)
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
    val res: Boolean
    if (Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
      res = SimpleStorage.hasStoragePermission(context)
      Log.d(TAG, "checkStoragePermission: Check Storage Access $res")
    } else {
      Log.d(TAG, "checkStoragePermission: Has permission on Android 10 or up")
      res = true
    }

    val jsobj = JSObject()
    jsobj.put("value", res)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun checkFolderPermissions(call: PluginCall) {
    val folderUrl = call.data.getString("folderUrl", "").toString()
    Log.d(TAG, "Check Folder Permissions for $folderUrl")

    val hasAccess = SimpleStorage.hasStorageAccess(context,folderUrl,true)

    val jsobj = JSObject()
    jsobj.put("value", hasAccess)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun scanFolder(call: PluginCall) {
    val folderId =  call.data.getString("folderId", "").toString()
    val forceAudioProbe = call.data.getBoolean("forceAudioProbe")
    Log.d(TAG, "Scan Folder $folderId | Force Audio Probe $forceAudioProbe")

    val folder: LocalFolder? = DeviceManager.dbManager.getLocalFolder(folderId)
    folder?.let {
      val folderScanner = FolderScanner(context)
      val folderScanResult = folderScanner.scanForMediaItems(it, forceAudioProbe)
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
    val folderId = call.data.getString("folderId", "").toString()
    DeviceManager.dbManager.removeLocalFolder(folderId)
    call.resolve()
  }

  @PluginMethod
  fun removeLocalLibraryItem(call: PluginCall) {
    val localLibraryItemId = call.data.getString("localLibraryItemId", "").toString()
    DeviceManager.dbManager.removeLocalLibraryItem(localLibraryItemId)
    call.resolve()
  }

  @PluginMethod
  fun scanLocalLibraryItem(call: PluginCall) {
    val localLibraryItemId = call.data.getString("localLibraryItemId", "").toString()
    val forceAudioProbe = call.data.getBoolean("forceAudioProbe")
    Log.d(TAG, "Scan Local library item $localLibraryItemId | Force Audio Probe $forceAudioProbe")
    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItem: LocalLibraryItem? = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
      localLibraryItem?.let {
        val folderScanner = FolderScanner(context)
        val scanResult = folderScanner.scanLocalLibraryItem(it, forceAudioProbe)
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
    val localLibraryItemId = call.data.getString("id", "").toString()
    val absolutePath = call.data.getString("absolutePath", "").toString()
    val contentUrl = call.data.getString("contentUrl", "").toString()
    Log.d(tag, "deleteItem $absolutePath | $contentUrl")

    val docfile = DocumentFileCompat.fromUri(mainActivity, Uri.parse(contentUrl))
    val success = docfile?.delete() == true
    if (success) {
      DeviceManager.dbManager.removeLocalLibraryItem(localLibraryItemId)
    }
    call.resolve(JSObject("{\"success\":$success}"))
  }

  @PluginMethod
  fun deleteTrackFromItem(call: PluginCall) {
    val localLibraryItemId = call.data.getString("id", "").toString()
    val trackLocalFileId = call.data.getString("trackLocalFileId", "").toString()
    val contentUrl = call.data.getString("trackContentUrl", "").toString()
    Log.d(tag, "deleteTrackFromItem $contentUrl")

    val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
    if (localLibraryItem == null) {
      Log.e(tag, "deleteTrackFromItem: LLI does not exist $localLibraryItemId")
      return call.resolve(JSObject("{\"success\":false}"))
    }

    val docfile = DocumentFileCompat.fromUri(mainActivity, Uri.parse(contentUrl))
    val success = docfile?.delete() == true
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
