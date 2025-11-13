package com.audiobookshelf.app.player

import android.net.Uri
import android.util.Log

import java.lang.reflect.Method

/**
 * Minimal Media3 wrapper placeholder.
 * This implementation intentionally avoids direct Media3 imports so it can compile even when
 * Media3 dependencies are not present. When Media3 is available we can extend this to use
 * the real Media3 player via reflection or by replacing this file with a real implementation.
 */
class Media3Wrapper() : PlayerWrapper {
  private val tag = "Media3Wrapper"

  override fun prepare() {
    Log.w(tag, "Media3Wrapper.prepare() - not implemented")
  }

  override fun play() {
    Log.w(tag, "Media3Wrapper.play() - not implemented")
  }

  override fun pause() {
    Log.w(tag, "Media3Wrapper.pause() - not implemented")
  }

  override fun release() {
    Log.w(tag, "Media3Wrapper.release() - not implemented")
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    Log.w(tag, "Media3Wrapper.setPlayWhenReady() - not implemented")
  }

  override fun seekTo(positionMs: Long) {
    Log.w(tag, "Media3Wrapper.seekTo() - not implemented")
  }

  override fun setMediaItems(items: List<com.audiobookshelf.app.player.PlayerMediaItem>, startIndex: Int, startPositionMs: Long) {
    Log.w(tag, "Media3Wrapper.setMediaItems() - not implemented")
  }

  override fun addMediaItems(items: List<com.audiobookshelf.app.player.PlayerMediaItem>) {
    Log.w(tag, "Media3Wrapper.addMediaItems() - not implemented")
  }

  override fun getCurrentPosition(): Long {
    Log.w(tag, "Media3Wrapper.getCurrentPosition() - not implemented")
    return 0L
  }

  override fun getMediaItemCount(): Int {
    Log.w(tag, "Media3Wrapper.getMediaItemCount() - not implemented")
    return 0
  }
  override fun setPlaybackSpeed(speed: Float) {
    Log.w(tag, "Media3Wrapper.setPlaybackSpeed() - not implemented")
  }

  override fun isPlaying(): Boolean {
    Log.w(tag, "Media3Wrapper.isPlaying() - not implemented")
    return false
  }

  // Exo-specific helpers removed; Media3Wrapper should accept PlayerMediaItem instead.

  override fun seekTo(windowIndex: Int, positionMs: Long) {
    Log.w(tag, "Media3Wrapper.seekTo(windowIndex, positionMs) - not implemented")
  }

  override fun getCurrentMediaItemIndex(): Int {
    Log.w(tag, "Media3Wrapper.getCurrentMediaItemIndex() - not implemented")
    return 0
  }

  override fun getBufferedPosition(): Long {
    Log.w(tag, "Media3Wrapper.getBufferedPosition() - not implemented")
    return 0L
  }

  override fun setVolume(volume: Float) {
    Log.w(tag, "Media3Wrapper.setVolume() - not implemented")
  }

  override fun clearMediaItems() {
    Log.w(tag, "Media3Wrapper.clearMediaItems() - not implemented")
  }

  override fun stop() {
    Log.w(tag, "Media3Wrapper.stop() - not implemented")
  }

  override fun seekToPrevious() {
    Log.w(tag, "Media3Wrapper.seekToPrevious() - not implemented")
  }

  override fun seekToNext() {
    Log.w(tag, "Media3Wrapper.seekToNext() - not implemented")
  }

  override fun getDuration(): Long {
    Log.w(tag, "Media3Wrapper.getDuration() - not implemented")
    return 0L
  }

  override fun getPlaybackState(): Int {
    Log.w(tag, "Media3Wrapper.getPlaybackState() - not implemented")
    return 0
  }

  override fun isLoading(): Boolean {
    Log.w(tag, "Media3Wrapper.isLoading() - not implemented")
    return false
  }
  override fun getPlaybackSpeed(): Float {
    Log.w(tag, "Media3Wrapper.getPlaybackSpeed() - not implemented")
    return 1f
  }

  /**
   * Try to build a Media3 MediaItem via reflection. Returns the built object or null
   * if Media3 classes are not present. This keeps this file safe to compile when
   * Media3 dependencies are absent while still providing a path for conversion
   * when Media3 is available at runtime.
   */
  fun toMedia3MediaItem(dto: PlayerMediaItem): Any? {
    return try {
      val builderClass = Class.forName("androidx.media3.common.MediaItem\$Builder")
      val builder = builderClass.getDeclaredConstructor().newInstance()

      try {
        val setUri: Method = builderClass.getMethod("setUri", Uri::class.java)
        setUri.invoke(builder, dto.uri)
      } catch (ignored: Exception) {
        // ignore if method missing
      }

      try {
        val setTag: Method = builderClass.getMethod("setTag", Any::class.java)
        dto.tag?.let { setTag.invoke(builder, it) }
      } catch (ignored: Exception) {
      }

      try {
        val setMime: Method = builderClass.getMethod("setMimeType", String::class.java)
        dto.mimeType?.let { setMime.invoke(builder, it) }
      } catch (ignored: Exception) {
      }

      val build: Method = builderClass.getMethod("build")
      build.invoke(builder)
    } catch (e: Exception) {
      Log.w(tag, "Media3 not available or failed to build MediaItem: ${e.message}")
      null
    }
  }

  fun toMedia3MediaItems(items: List<PlayerMediaItem>): List<Any?> = items.map { toMedia3MediaItem(it) }
}
