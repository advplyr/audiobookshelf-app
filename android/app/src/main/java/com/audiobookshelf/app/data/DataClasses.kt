package com.audiobookshelf.app.data

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import com.fasterxml.jackson.annotation.*
import com.google.android.exoplayer2.MediaMetadata

@JsonIgnoreProperties(ignoreUnknown = true)
data class LibraryItem(
  var id:String,
  var ino:String,
  var libraryId:String,
  var folderId:String,
  var path:String,
  var relPath:String,
  var mtimeMs:Long,
  var ctimeMs:Long,
  var birthtimeMs:Long,
  var addedAt:Long,
  var updatedAt:Long,
  var lastScan:Long?,
  var scanVersion:String?,
  var isMissing:Boolean,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaEntity,
  var libraryFiles:MutableList<LibraryFile>
)

// This auto-detects whether it is a Book or Podcast
@JsonTypeInfo(use=JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
  JsonSubTypes.Type(Book::class),
  JsonSubTypes.Type(Podcast::class)
)
open class MediaEntity {}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Podcast(
  var metadata:PodcastMetadata,
  var coverPath:String?,
  var tags:MutableList<String>,
  var episodes:MutableList<PodcastEpisode>,
  var autoDownloadEpisodes:Boolean
) : MediaEntity()

@JsonIgnoreProperties(ignoreUnknown = true)
data class Book(
  var metadata:BookMetadata,
  var coverPath:String?,
  var tags:MutableList<String>,
  var audiobooks:MutableList<Audiobook>
) : MediaEntity()

// This auto-detects whether it is a Book or Podcast
@JsonTypeInfo(use=JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
  JsonSubTypes.Type(BookMetadata::class),
  JsonSubTypes.Type(PodcastMetadata::class)
)
open class MediaEntityMetadata {}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BookMetadata(
  var title:String,
  var subtitle:String?,
  var authors:MutableList<Author>
) : MediaEntityMetadata()

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodcastMetadata(
  var title:String,
  var author:String?,
  var feedUrl:String,
  var genres:MutableList<String>
) : MediaEntityMetadata()

@JsonIgnoreProperties(ignoreUnknown = true)
data class Author(
  var id:String,
  var name:String,
  var coverPath:String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Audiobook(
  var id:String,
  var index:Int,
  var name:String,
  var audioFiles:MutableList<AudioFile>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodcastEpisode(
  var id:String,
  var index:Int,
  var episode:String?,
  var episodeType:String?,
  var title:String?,
  var subtitle:String?,
  var description:String?,
  var audioFile:AudioFile
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LibraryFile(
  var ino:String,
  var metadata:FileMetadata
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FileMetadata(
  var filename:String,
  var ext:String,
  var path:String,
  var relPath:String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioFile(
  var index:Int,
  var ino:String,
  var metadata:FileMetadata
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Library(
  var id:String,
  var name:String,
  var folders:MutableList<Folder>,
  var icon:String,
  var mediaType:String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Folder(
  var id:String,
  var fullPath:String
)

@JsonIgnoreProperties(ignoreUnknown = true)
class PlaybackSession(
  var id:String,
  var userId:String,
  var libraryItemId:String,
  var mediaEntityId:String,
  var mediaType:String,
  var mediaMetadata:MediaEntityMetadata,
  var duration:Double,
  var playMethod:Int,
  var audioTracks:MutableList<AudioTrack>,
  var currentTime:Double,
  var serverUrl:String,
  var token:String
) {
  fun getTitle():String {
    var metadata = mediaMetadata as BookMetadata
    return metadata.title
  }
  fun getAuthor():String {
    var metadata = mediaMetadata as BookMetadata
    return  metadata.authors.joinToString(",") { it.name }
  }
  fun getContentUri():String {
    // TODO: Using Uri.parse here is throwing error with jackson
    var audioTrack = audioTracks[0]
    return "$serverUrl${audioTrack.contentUrl}?token=$token"
  }
  fun getMimeType():String {
    var audioTrack = audioTracks[0]
    return audioTrack.mimeType
  }
  fun getMediaMetadataCompat(): MediaMetadataCompat {
      var metadata = mediaMetadata as BookMetadata

    var metadataBuilder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, metadata.title)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, metadata.authors.joinToString(",") { it.name })
      .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, metadata.authors.joinToString(",") { it.name })
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.authors.joinToString(",") { it.name })
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "series")
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
    return metadataBuilder.build()
  }
  fun getMediaMetadata(): MediaMetadata {
    var metadata = mediaMetadata as BookMetadata
    var authorName = metadata.authors.joinToString(",") { it.name }
    var metadataBuilder = MediaMetadata.Builder()
      .setTitle(metadata.title)
      .setDisplayTitle(metadata.title)
      .setArtist(authorName)
      .setAlbumArtist(authorName)
      .setSubtitle(authorName)

//    var contentUri = this.getContentUri()
//      metadataBuilder.setMediaUri(contentUri)

    return metadataBuilder.build()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioTrack(
  var index:Int,
  var startOffset:Double,
  var duration:Double,
  var title:String,
  var contentUrl:String,
  var mimeType:String
)
