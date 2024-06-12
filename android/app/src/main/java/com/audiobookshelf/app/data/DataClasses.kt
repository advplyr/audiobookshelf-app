package com.audiobookshelf.app.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.media.MediaManager
import com.fasterxml.jackson.annotation.*

// This auto-detects whether it is a Book or Podcast
@JsonTypeInfo(use=JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
  JsonSubTypes.Type(Book::class),
  JsonSubTypes.Type(Podcast::class)
)
open class MediaType(var metadata:MediaTypeMetadata, var coverPath:String?) {
  @JsonIgnore
  open fun getAudioTracks():List<AudioTrack> { return mutableListOf() }
  @JsonIgnore
  open fun setAudioTracks(audioTracks:MutableList<AudioTrack>) { }
  @JsonIgnore
  open fun addAudioTrack(audioTrack:AudioTrack) { }
  @JsonIgnore
  open fun removeAudioTrack(localFileId:String) { }
  @JsonIgnore
  open fun getLocalCopy():MediaType { return MediaType(MediaTypeMetadata("", false),null) }

}

@JsonIgnoreProperties(ignoreUnknown = true)
class Podcast(
  metadata:PodcastMetadata,
  coverPath:String?,
  var tags:MutableList<String>,
  var episodes:MutableList<PodcastEpisode>?,
  var autoDownloadEpisodes:Boolean,
  var numEpisodes:Int?
) : MediaType(metadata, coverPath) {
  @JsonIgnore
  override fun getAudioTracks():List<AudioTrack> {
    val tracks = episodes?.map { it.audioTrack }
    return tracks?.filterNotNull() ?: mutableListOf()
  }
  @JsonIgnore
  override fun setAudioTracks(audioTracks:MutableList<AudioTrack>) {
    // Remove episodes no longer there in tracks
    episodes = episodes?.filter { ep ->
      audioTracks.find { it.localFileId == ep.audioTrack?.localFileId } != null
    } as MutableList<PodcastEpisode>

    // Add new episodes
    audioTracks.forEach { at ->
      if (episodes?.find{ it.audioTrack?.localFileId == at.localFileId } == null) {
        val localEpisodeId = "local_ep_" + at.localFileId
        val newEpisode = PodcastEpisode(localEpisodeId,(episodes?.size ?: 0) + 1,null,null,at.title,null,null,null, null, null, at,null,at.duration,0, null, localEpisodeId)
        episodes?.add(newEpisode)
      }
    }

    var index = 1
    episodes?.forEach {
      it.index = index
      index++
    }
  }
  @JsonIgnore
  override fun addAudioTrack(audioTrack:AudioTrack) {
    val localEpisodeId = "local_ep_" + audioTrack.localFileId
    val newEpisode = PodcastEpisode(localEpisodeId,(episodes?.size ?: 0) + 1,null,null,audioTrack.title,null,null,null, null, null,audioTrack,null,audioTrack.duration,0, null, localEpisodeId)
    episodes?.add(newEpisode)

    var index = 1
    episodes?.forEach {
      it.index = index
      index++
    }
  }
  @JsonIgnore
  override fun removeAudioTrack(localFileId:String) {
    episodes?.removeIf { it.audioTrack?.localFileId == localFileId }

    var index = 1
    episodes?.forEach {
      it.index = index
      index++
    }
  }

  // Used for FolderScanner local podcast item to get copy of Podcast excluding episodes
  @JsonIgnore
  override fun getLocalCopy(): Podcast {
    return Podcast(metadata as PodcastMetadata,coverPath,tags, mutableListOf(),autoDownloadEpisodes, 0)
  }

  @JsonIgnore
  fun addEpisode(audioTrack:AudioTrack, episode:PodcastEpisode):PodcastEpisode {
    val localEpisodeId = "local_ep_" + episode.id
    val newEpisode = PodcastEpisode(localEpisodeId,(episodes?.size ?: 0) + 1,episode.episode,episode.episodeType,episode.title,episode.subtitle,episode.description,null,null,null,audioTrack,episode.chapters,audioTrack.duration,episode.size, episode.id, localEpisodeId)
    episodes?.add(newEpisode)

    var index = 1
    episodes?.forEach {
      it.index = index
      index++
    }
    return newEpisode
  }

  @JsonIgnore
  fun getNextUnfinishedEpisode(libraryItemId:String, mediaManager: MediaManager):PodcastEpisode? {
    val sortedEpisodes = episodes?.sortedByDescending { it.publishedAt }
    val podcastEpisode = sortedEpisodes?.find { episode ->
      val progress = mediaManager.serverUserMediaProgress.find { it.libraryItemId == libraryItemId && it.episodeId == episode.id }
      progress == null || !progress.isFinished
    }
    return podcastEpisode
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Book(
  metadata:BookMetadata,
  coverPath:String?,
  var tags:List<String>,
  var audioFiles:List<AudioFile>?,
  var chapters:List<BookChapter>?,
  var tracks:MutableList<AudioTrack>?,
  var ebookFile: EBookFile?,
  var size:Long?,
  var duration:Double?,
  var numTracks:Int?
) : MediaType(metadata, coverPath) {
  @JsonIgnore
  override fun getAudioTracks():List<AudioTrack> {
    return tracks ?: mutableListOf()
  }
  @JsonIgnore
  override fun setAudioTracks(audioTracks:MutableList<AudioTrack>) {
    tracks = audioTracks
    tracks?.sortBy { it.index }

    var totalDuration = 0.0
    var currentStartOffset = 0.0
    tracks?.forEach {
      totalDuration += it.duration
      it.startOffset = currentStartOffset
      currentStartOffset += it.duration
    }
    duration = totalDuration
  }
  @JsonIgnore
  override fun addAudioTrack(audioTrack:AudioTrack) {
    tracks?.add(audioTrack)

    var totalDuration = 0.0
    tracks?.forEach {
      totalDuration += it.duration
    }
    duration = totalDuration
  }
  @JsonIgnore
  override fun removeAudioTrack(localFileId:String) {
    tracks?.removeIf { it.localFileId == localFileId }

    tracks?.sortBy { it.index }

    var index = 1
    var startOffset = 0.0
    var totalDuration = 0.0
    tracks?.forEach {
      it.index = index
      it.startOffset = startOffset
      totalDuration += it.duration

      index++
      startOffset += it.duration
    }
    duration = totalDuration
  }
  @JsonIgnore
  override fun getLocalCopy(): Book {
    return Book(metadata as BookMetadata,coverPath,tags, mutableListOf(),chapters,mutableListOf(), ebookFile, null,null, 0)
  }
}

// This auto-detects whether it is a BookMetadata or PodcastMetadata
@JsonTypeInfo(use=JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
  JsonSubTypes.Type(BookMetadata::class),
  JsonSubTypes.Type(PodcastMetadata::class)
)
open class MediaTypeMetadata(var title:String, var explicit:Boolean) {
  @JsonIgnore
  open fun getAuthorDisplayName():String { return "Unknown" }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class BookMetadata(
  title:String,
  var subtitle:String?,
  var authors:MutableList<Author>?,
  var narrators:MutableList<String>?,
  var genres:MutableList<String>,
  var publishedYear:String?,
  var publishedDate:String?,
  var publisher:String?,
  var description:String?,
  var isbn:String?,
  var asin:String?,
  var language:String?,
  explicit:Boolean,
  // In toJSONExpanded
  var authorName:String?,
  var authorNameLF:String?,
  var narratorName:String?,
  var seriesName:String?
) : MediaTypeMetadata(title, explicit) {
  @JsonIgnore
  override fun getAuthorDisplayName():String { return authorName ?: "Unknown" }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class PodcastMetadata(
  title:String,
  var author:String?,
  var feedUrl:String?,
  var genres:MutableList<String>,
  explicit:Boolean
) : MediaTypeMetadata(title, explicit) {
  @JsonIgnore
  override fun getAuthorDisplayName():String { return author ?: "Unknown" }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Author(
  var id:String,
  var name:String,
  var coverPath:String?
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
  var pubDate:String?,
  var publishedAt:Long?,
  var audioFile:AudioFile?,
  var audioTrack:AudioTrack?,
  var chapters:List<BookChapter>?,
  var duration:Double?,
  var size:Long?,
  var serverEpisodeId:String?, // For local podcasts to match with server podcasts
  var localEpisodeId:String? // For Android Auto server episodes with local copy
) {
  @JsonIgnore
  fun getMediaDescription(libraryItem:LibraryItemWrapper, progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    val coverUri = if (libraryItem is LocalLibraryItem) {
      libraryItem.getCoverUri(ctx)
    } else {
      (libraryItem as LibraryItem).getCoverUri()
    }

    val extras = Bundle()

    if (localEpisodeId != null) {
      extras.putLong(
        MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
        MediaDescriptionCompat.STATUS_DOWNLOADED
      )
    }

    if (progress != null) {
      if (progress.isFinished) {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        )
      } else {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        extras.putDouble(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress
        )
      }
    } else {
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
      )
    }

    val libraryItemDescription = libraryItem.getMediaDescription(null, ctx)
    val mediaId = localEpisodeId ?: id
    val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      .setIconUri(coverUri)
      .setSubtitle(libraryItemDescription.title)
      .setExtras(extras)

    libraryItemDescription.iconBitmap?.let {
      mediaDescriptionBuilder.setIconBitmap(it)
    }

    return mediaDescriptionBuilder.build()
  }
}

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
  var relPath:String,
  var size:Long?
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
) {
  @JsonIgnore
  fun getMediaMetadata(): MediaMetadataCompat {
    return MediaMetadataCompat.Builder().apply {
      putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
      putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name)
      putString(MediaMetadataCompat.METADATA_KEY_TITLE, name)
    }.build()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Folder(
  var id:String,
  var fullPath:String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BookChapter(
  var id:Int,
  var start:Double,
  var end:Double,
  var title:String?
) {
  @get:JsonIgnore
  val startMs get() = (start * 1000L).toLong()
  @get:JsonIgnore
  val endMs get() = (end * 1000L).toLong()
}

@JsonTypeInfo(use= JsonTypeInfo.Id.DEDUCTION, defaultImpl = MediaProgress::class)
@JsonSubTypes(
  JsonSubTypes.Type(MediaProgress::class),
  JsonSubTypes.Type(LocalMediaProgress::class)
)
open class MediaProgressWrapper(var isFinished:Boolean, var currentTime:Double, var progress:Double) {
  open val mediaItemId get() = ""
}

// Helper class
data class LibraryItemWithEpisode(
  var libraryItemWrapper:LibraryItemWrapper,
  var episode:PodcastEpisode
)
