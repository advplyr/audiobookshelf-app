package com.audiobookshelf.app.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.data.TTSBook
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.TTSPlaybackEngine
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray

/**
 * Capacitor bridge for the native read aloud (TTS) player.
 * JS contract: plugins/capacitor/AbsTTSPlayer.js, design in
 * docs/native-tts-player-design.md
 */
@CapacitorPlugin(name = "AbsTTSPlayer")
class AbsTTSPlayer : Plugin() {
  private val tag = "AbsTTSPlayer"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  lateinit var playerNotificationService: PlayerNotificationService

  private val mainHandler = Handler(Looper.getMainLooper())

  override fun load() {
    mainActivity = (activity as MainActivity)

    val foregroundServiceReady: () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.ttsClientEventEmitter = (object : PlayerNotificationService.TTSClientEventEmitter {
        override fun onTTSStateChange(state: String) {
          val ret = JSObject()
          ret.put("state", state)
          notifyListeners("onStateChange", ret)
        }

        override fun onTTSParagraph(chapterIndex: Int, paragraphIndex: Int, location: String?, progress: Double) {
          val ret = JSObject()
          ret.put("chapterIndex", chapterIndex)
          ret.put("paragraphIndex", paragraphIndex)
          ret.put("location", location)
          ret.put("progress", progress)
          notifyListeners("onParagraph", ret)
        }

        override fun onTTSError(message: String) {
          val ret = JSObject()
          ret.put("error", message)
          notifyListeners("onError", ret)
        }
      })
    }
    mainActivity.pluginCallbacks.add(foregroundServiceReady)
  }

  private fun isServiceReady(): Boolean {
    return this::playerNotificationService.isInitialized
  }

  @PluginMethod
  fun prepareBook(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    val book: TTSBook
    try {
      book = jacksonMapper.readValue(call.data.toString(), TTSBook::class.java)
    } catch (e: Exception) {
      Log.e(tag, "prepareBook failed to parse book", e)
      return call.reject("Invalid book payload")
    }
    if (book.libraryItemId.isEmpty() || book.chapters.isEmpty()) {
      return call.reject("Book has no id or no chapters")
    }
    mainHandler.post {
      playerNotificationService.prepareTTSBook(book)
      call.resolve()
    }
  }

  @PluginMethod
  fun play(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    val libraryItemId = call.getString("libraryItemId")
    val chapterIndex = if (call.data.has("chapterIndex")) call.getInt("chapterIndex") else null
    val paragraphIndex = if (call.data.has("paragraphIndex")) call.getInt("paragraphIndex") else null
    mainHandler.post {
      playerNotificationService.playTTS(libraryItemId, chapterIndex, paragraphIndex)
      call.resolve()
    }
  }

  @PluginMethod
  fun pause(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    mainHandler.post {
      playerNotificationService.ttsEngine?.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun stop(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    mainHandler.post {
      playerNotificationService.ttsEngine?.stop()
      call.resolve()
    }
  }

  @PluginMethod
  fun seekTo(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    val chapterIndex = call.getInt("chapterIndex") ?: 0
    val paragraphIndex = call.getInt("paragraphIndex") ?: 0
    mainHandler.post {
      playerNotificationService.ttsEngine?.seekTo(chapterIndex, paragraphIndex)
      call.resolve()
    }
  }

  @PluginMethod
  fun nextChapter(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    mainHandler.post {
      playerNotificationService.ttsEngine?.seekChapter(1)
      call.resolve()
    }
  }

  @PluginMethod
  fun prevChapter(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    mainHandler.post {
      playerNotificationService.ttsEngine?.seekChapter(-1)
      call.resolve()
    }
  }

  @PluginMethod
  fun setRate(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    val rate = call.getFloat("rate") ?: 1f
    mainHandler.post {
      playerNotificationService.ttsEngine?.setPlaybackRate(rate)
      call.resolve()
    }
  }

  @PluginMethod
  fun setLanguage(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    val lang = call.getString("lang") ?: return call.reject("Missing lang")
    mainHandler.post {
      playerNotificationService.ttsEngine?.setLanguage(lang)
      call.resolve()
    }
  }

  @PluginMethod
  fun getState(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    mainHandler.post {
      val engine = playerNotificationService.ttsEngine
      val ret = JSObject()
      ret.put("state", engine?.state?.value ?: TTSPlaybackEngine.TTSState.STOPPED.value)
      ret.put("libraryItemId", engine?.book?.libraryItemId)
      ret.put("chapterIndex", engine?.chapterIndex ?: 0)
      ret.put("paragraphIndex", engine?.paragraphIndex ?: 0)
      ret.put("location", engine?.currentLocation)
      ret.put("progress", engine?.progress ?: 0.0)
      ret.put("rate", engine?.rate ?: 1f)
      ret.put("language", engine?.language ?: "en-US")
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun listCachedBooks(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    mainHandler.post {
      val ret = JSObject()
      ret.put("books", JSONArray(jacksonMapper.writeValueAsString(playerNotificationService.ttsBookCache.list())))
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun removeCachedBook(call: PluginCall) {
    if (!isServiceReady()) return call.reject("Player service not ready")
    val libraryItemId = call.getString("libraryItemId") ?: return call.reject("Missing libraryItemId")
    mainHandler.post {
      playerNotificationService.ttsBookCache.remove(libraryItemId)
      playerNotificationService.notifyEbooksChanged()
      call.resolve()
    }
  }
}
