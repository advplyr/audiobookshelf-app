package com.audiobookshelf.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import java.lang.reflect.Method

/**
 * Media3-backed PlayerWrapper implemented via reflection so this file can remain
 * compilable even when Media3 dependencies are not added. If Media3 is present
 * in the classpath (Gradle flag enabled), this wrapper will attempt to construct
 * a Media3 ExoPlayer instance and forward calls to it. Otherwise methods fall
 * back to no-op with helpful logs.
 */
class Media3Wrapper(private val ctx: Context) : PlayerWrapper {
  private val tag = "Media3Wrapper"
  private var playerInstance: Any? = null
  private var playerClass: Class<*>? = null

  init {
    try {
      // Try to construct androidx.media3.exoplayer.ExoPlayer via reflection: new ExoPlayer.Builder(ctx).build()
      val builderClass = Class.forName("androidx.media3.exoplayer.ExoPlayer\$Builder")
      val ctor = builderClass.getDeclaredConstructor(Context::class.java)
      val builder = ctor.newInstance(ctx)
      val buildMethod = builderClass.getMethod("build")
      playerInstance = buildMethod.invoke(builder)
      playerClass = playerInstance?.javaClass
      Log.i(tag, "Media3 player constructed via reflection")
    } catch (e: Exception) {
      Log.w(tag, "Media3 not available on classpath or failed to instantiate: ${e.message}")
      playerInstance = null
      playerClass = null
    }
  }

  private fun invokePlayerMethod(methodName: String, vararg args: Any?): Any? {
    try {
      if (playerInstance == null || playerClass == null) return null
      val argTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
      val method = playerClass!!.methods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }
              ?: playerClass!!.getMethod(methodName, *argTypes)
      return method.invoke(playerInstance, *args)
    } catch (e: Exception) {
      Log.w(tag, "Failed to invoke Media3 method $methodName: ${e.message}")
      return null
    }
  }

  override fun prepare() {
    invokePlayerMethod("prepare")
  }

  override fun play() {
    invokePlayerMethod("play")
  }

  override fun pause() {
    invokePlayerMethod("pause")
  }

  override fun release() {
    try {
      invokePlayerMethod("release")
    } catch (e: Exception) {
      Log.w(tag, "release failed: ${e.message}")
    }
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    invokePlayerMethod("setPlayWhenReady", playWhenReady)
  }

  override fun seekTo(positionMs: Long) {
    invokePlayerMethod("seekTo", positionMs)
  }

  override fun setMediaItems(items: List<PlayerMediaItem>, startIndex: Int, startPositionMs: Long) {
    try {
      val media3Items = items.mapNotNull { toMedia3MediaItem(it) }
      if (media3Items.isEmpty()) return
      // find setMediaItems(List) or setMediaItems(List, int, long)
      val method = playerClass?.methods?.firstOrNull { m ->
        m.name == "setMediaItems" && m.parameterTypes.size >= 1
      }
      method?.invoke(playerInstance, media3Items, startIndex, startPositionMs)
    } catch (e: Exception) {
      Log.w(tag, "setMediaItems failed: ${e.message}")
    }
  }

  override fun addMediaItems(items: List<PlayerMediaItem>) {
    try {
      val media3Items = items.mapNotNull { toMedia3MediaItem(it) }
      if (media3Items.isEmpty()) return
      val method = playerClass?.methods?.firstOrNull { m -> m.name == "addMediaItems" }
      method?.invoke(playerInstance, media3Items)
    } catch (e: Exception) {
      Log.w(tag, "addMediaItems failed: ${e.message}")
    }
  }

  override fun getCurrentPosition(): Long {
    return (invokePlayerMethod("getCurrentPosition") as? Number)?.toLong() ?: 0L
  }

  override fun getMediaItemCount(): Int {
    return (invokePlayerMethod("getMediaItemCount") as? Number)?.toInt() ?: 0
  }

  override fun setPlaybackSpeed(speed: Float) {
    // Try setPlaybackSpeed or setPlaybackParameters
    invokePlayerMethod("setPlaybackSpeed", speed)
  }

  override fun isPlaying(): Boolean {
    return (invokePlayerMethod("isPlaying") as? Boolean) ?: false
  }

  override fun seekTo(windowIndex: Int, positionMs: Long) {
    invokePlayerMethod("seekTo", windowIndex, positionMs)
  }

  override fun getCurrentMediaItemIndex(): Int {
    return (invokePlayerMethod("getCurrentMediaItemIndex") as? Number)?.toInt() ?: 0
  }

  override fun getBufferedPosition(): Long {
    return (invokePlayerMethod("getBufferedPosition") as? Number)?.toLong() ?: 0L
  }

  override fun setVolume(volume: Float) {
    invokePlayerMethod("setVolume", volume)
  }

  override fun clearMediaItems() {
    invokePlayerMethod("clearMediaItems")
  }

  override fun stop() {
    invokePlayerMethod("stop")
  }

  override fun seekToPrevious() {
    invokePlayerMethod("seekToPrevious")
  }

  override fun seekToNext() {
    invokePlayerMethod("seekToNext")
  }

  override fun getDuration(): Long {
    return (invokePlayerMethod("getDuration") as? Number)?.toLong() ?: 0L
  }

  override fun getPlaybackState(): Int {
    return (invokePlayerMethod("getPlaybackState") as? Number)?.toInt() ?: 0
  }

  override fun isLoading(): Boolean {
    return (invokePlayerMethod("isLoading") as? Boolean) ?: false
  }

  override fun getPlaybackSpeed(): Float {
    return (invokePlayerMethod("getPlaybackSpeed") as? Number)?.toFloat() ?: 1f
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
      val builderCtor = builderClass.getDeclaredConstructor()
      val builder = builderCtor.newInstance()

      try {
        val setUri: java.lang.reflect.Method = builderClass.getMethod("setUri", Uri::class.java)
        setUri.invoke(builder, dto.uri)
      } catch (ignored: Exception) {
      }

      try {
        val setTag: java.lang.reflect.Method = builderClass.getMethod("setTag", Any::class.java)
        dto.tag?.let { setTag.invoke(builder, it) }
      } catch (ignored: Exception) {
      }

      try {
        val setMime: java.lang.reflect.Method = builderClass.getMethod("setMimeType", String::class.java)
        dto.mimeType?.let { setMime.invoke(builder, it) }
      } catch (ignored: Exception) {
      }

      val build: java.lang.reflect.Method = builderClass.getMethod("build")
      build.invoke(builder)
    } catch (e: Exception) {
      Log.w(tag, "Media3 not available or failed to build MediaItem: ${e.message}")
      null
    }
  }

  fun toMedia3MediaItems(items: List<PlayerMediaItem>): List<Any?> = items.map { toMedia3MediaItem(it) }

}
