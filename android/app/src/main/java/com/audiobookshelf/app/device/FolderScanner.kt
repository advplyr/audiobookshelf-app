package com.audiobookshelf.app.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import java.io.File

/** Creates local-library records from the completed download manifest, not a recursive rescan. */
class FolderScanner(private val ctx: Context) {
  private val tag = "FolderScanner"

  data class DownloadItemScanResult(
          val localLibraryItem: LocalLibraryItem,
          var localMediaProgress: LocalMediaProgress?
  )

  private fun localLibraryItemId(mediaItemId: String) = "local_${DeviceManager.getBase64Id(mediaItemId)}"

  private fun createLocalFile(part: DownloadItemPart, externalFile: DocumentFile? = null): LocalFile? {
    if (part.isInternalStorage) {
      val file = File(part.finalDestinationPath)
      if (!file.exists()) return null
      return LocalFile(
              DeviceManager.getBase64Id(file.name),
              file.name,
              Uri.fromFile(file).toString(),
              file.getBasePath(ctx),
              file.absolutePath,
              file.getSimplePath(ctx),
              file.mimeType,
              file.length()
      )
    }

    part.completedDestinationUri?.let { contentUrl ->
      val uri = Uri.parse(contentUrl)
      val size =
              try {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                  descriptor.statSize.coerceAtLeast(0L)
                } ?: 0L
              } catch (e: Exception) {
                Log.e(tag, "Could not open completed SAF file: $contentUrl", e)
                return null
              }
      // Android 10 DownloadsProvider may not reconstruct a DocumentFile for an audio URI even
      // though the URI remains readable. Keep the URI as the authoritative local-file location.
      return LocalFile(
              DeviceManager.getBase64Id(contentUrl),
              part.filename,
              contentUrl,
              part.localFolderName,
              part.finalDestinationPath,
              part.finalDestinationPath,
              mimeTypeFor(part),
              size
      )
    }

    // Do not reconstruct a DocumentFile from an absolute path: on Android 10 that becomes a
    // file:// URI, which DocumentsContract rejects. The caller resolves this from the persisted
    // SAF tree grant instead.
    val document = externalFile
    if (document == null || !document.exists()) {
      Log.e(tag, "Could not resolve downloaded SAF file: ${part.finalDestinationPath}")
      return null
    }
    return LocalFile(
            DeviceManager.getBase64Id(document.id),
            document.name,
            document.uri.toString(),
            document.getBasePath(ctx),
            document.getAbsolutePath(ctx),
            document.getSimplePath(ctx),
            document.mimeType,
            document.length()
    )
  }

  private fun newLocalLibraryItem(
          id: String,
          downloadItem: DownloadItem,
          basePath: String,
          absolutePath: String,
          contentUrl: String
  ) =
          LocalLibraryItem(
                  id,
                  downloadItem.localFolder.id,
                  basePath,
                  absolutePath,
                  contentUrl,
                  false,
                  downloadItem.mediaType,
                  downloadItem.media.getLocalCopy(),
                  mutableListOf(),
                  null,
                  null,
                  true,
                  downloadItem.serverConnectionConfigId,
                  downloadItem.serverAddress,
                  downloadItem.serverUserId,
                  downloadItem.libraryItemId
          )

  private fun scanParts(
          item: DownloadItem,
          localItem: LocalLibraryItem,
          externalFolder: DocumentFile? = null,
          callback: (DownloadItemScanResult?) -> Unit
  ) {
    val tracks = mutableListOf<AudioTrack>()
    var foundEbook = false
    var localEpisodeId: String? = null

    item.downloadItemParts.forEach { part ->
      val externalFile =
              if (part.isInternalStorage) {
                null
              } else {
                part.completedDestinationUri
                        ?.let { DocumentFileCompat.fromUri(ctx, Uri.parse(it)) }
                        ?: resolveExternalFile(externalFolder, part)
              }
      Log.d(tag, "Resolve part ${part.filename}: externalFile=${externalFile?.uri}")
      val localFile = createLocalFile(part, externalFile) ?: return@forEach
      when {
        part.audioTrack != null -> {
          val serverTrack = part.audioTrack
          localItem.localFiles.removeAll { it.id == localFile.id }
          localItem.localFiles.add(localFile)
          val metadata =
                  FileMetadata(
                          localFile.filename ?: "",
                          File(localFile.filename ?: "").extension,
                          localFile.absolutePath,
                          localFile.basePath,
                          localFile.size
                  )
          val track =
                  AudioTrack(
                          serverTrack.index,
                          serverTrack.startOffset,
                          serverTrack.duration,
                          localFile.filename ?: "",
                          localFile.contentUrl,
                          localFile.mimeType ?: "",
                          metadata,
                          true,
                          localFile.id,
                          serverTrack.index
                  )
          tracks.add(track)
          Log.d(tag, "Added local audio track ${track.contentUrl} (${track.metadata?.path})")
          part.episode?.let { episode ->
            val podcast = localItem.media as Podcast
            localEpisodeId = podcast.addEpisode(track, episode).id
          }
        }
        part.ebookFile != null -> {
          foundEbook = true
          localItem.localFiles.removeAll { it.id == localFile.id }
          localItem.localFiles.add(localFile)
          (localItem.media as Book).ebookFile =
                  EBookFile(
                          part.ebookFile.ino,
                          part.ebookFile.metadata,
                          part.ebookFile.ebookFormat,
                          true,
                          localFile.id,
                          localFile.contentUrl
                  )
        }
        else -> {
          localItem.coverAbsolutePath = localFile.absolutePath
          localItem.coverContentUrl = localFile.contentUrl
          localItem.localFiles.removeAll { it.id == localFile.id }
          localItem.localFiles.add(localFile)
        }
      }
    }

    if (tracks.isEmpty() && !foundEbook) {
      callback(null)
      return
    }
    if (item.mediaType == "book") {
      tracks.sortBy { it.index }
      var expectedIndex = 1
      var offset = 0.0
      tracks.forEach { track ->
        track.index = expectedIndex++
        track.startOffset = offset
        offset += track.duration
      }
      localItem.media.setAudioTracks(tracks)
    }

    val result = DownloadItemScanResult(localItem, null)
    item.userMediaProgress?.let { progress ->
      val progressId = if (item.episodeId.isNullOrEmpty()) localItem.id else "${localItem.id}-$localEpisodeId"
      result.localMediaProgress =
              LocalMediaProgress(
                      progressId,
                      localItem.id,
                      localEpisodeId,
                      progress.duration,
                      progress.progress,
                      progress.currentTime,
                      progress.isFinished,
                      progress.ebookLocation,
                      progress.ebookProgress,
                      progress.lastUpdate,
                      progress.startedAt,
                      progress.finishedAt,
                      item.serverConnectionConfigId,
                      item.serverAddress,
                      item.serverUserId,
                      item.libraryItemId,
                      item.episodeId
              )
      DeviceManager.dbManager.saveLocalMediaProgress(result.localMediaProgress!!)
    }
    DeviceManager.dbManager.saveLocalLibraryItem(localItem)
    callback(result)
  }

  private fun findFolderByPath(root: DocumentFile, subPath: String): DocumentFile? {
    if (subPath.isBlank()) return root
    var current = root
    subPath.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      current = current.findFile(segment) ?: return null
    }
    return current
  }

  /**
   * DownloadsProvider on Android 10 may expose an audio document without its extension through
   * DocumentFile.findFile(). Match the manifest first, then match the provider-normalized base
   * filename. MIME type and server-reported size are unreliable for Opus on this platform.
   */
  private fun resolveExternalFile(folder: DocumentFile?, part: DownloadItemPart): DocumentFile? {
    if (folder == null) return null
    folder.findFile(part.filename)?.let { return it }
    val expectedBaseName = part.filename.substringBeforeLast('.')
    return folder.listFiles().firstOrNull { document ->
      document.name == part.filename ||
              document.fullName == part.filename ||
              (part.audioTrack != null && document.isFile &&
                      (document.name ?: "").substringBeforeLast('.') == expectedBaseName) ||
              (part.audioTrack != null && document.isFile &&
                      document.fullName.substringBeforeLast('.') == expectedBaseName)
    }
  }

  private fun mimeTypeFor(part: DownloadItemPart): String? {
    return part.audioTrack?.mimeType
            ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
              "epub" -> "application/epub+zip"
              "pdf" -> "application/pdf"
              else -> "image/jpeg"
            }
  }

  fun scanDownloadItem(item: DownloadItem, callback: (DownloadItemScanResult?) -> Unit) {
    if (item.isInternalStorage) {
      val id = "local_${item.libraryItemId}"
      val localItem =
              DeviceManager.dbManager.getLocalLibraryItem(id)
                      ?: newLocalLibraryItem(id, item, item.itemFolderPath, item.itemFolderPath, "")
      scanParts(item, localItem, callback = callback)
      return
    }

    val root = DocumentFileCompat.fromUri(ctx, Uri.parse(item.localFolder.contentUrl))
    if (root == null) {
      Log.e(tag, "Invalid SAF root: ${item.localFolder.contentUrl}")
      callback(null)
      return
    }
    val itemFolder = findFolderByPath(root, item.itemSubfolder)
    if (itemFolder == null) {
      Log.e(tag, "SAF item folder not found: ${item.itemSubfolder}")
      callback(null)
      return
    }
    val id = localLibraryItemId(itemFolder.id)
    val localItem =
            DeviceManager.dbManager.getLocalLibraryItem(id)
                    ?: newLocalLibraryItem(
                            id,
                            item,
                            itemFolder.getBasePath(ctx),
                            itemFolder.getAbsolutePath(ctx),
                            itemFolder.uri.toString()
                    )
    scanParts(item, localItem, itemFolder, callback)
  }
}
