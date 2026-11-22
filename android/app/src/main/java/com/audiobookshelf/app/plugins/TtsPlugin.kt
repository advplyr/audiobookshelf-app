package com.audiobookshelf.app.plugins

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.Locale

@CapacitorPlugin(name = "TtsPlugin")
class TtsPlugin : Plugin(), TextToSpeech.OnInitListener {

    private val TAG = "TtsPlugin"
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var rate: Float = 1.0f
    private var pitch: Float = 1.0f
    private var selectedVoice: android.speech.tts.Voice? = null

    override fun load() {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val data = JSObject()
                    data.put("utteranceId", utteranceId)
                    notifyListeners("ttsStart", data)
                }

                override fun onDone(utteranceId: String?) {
                    val data = JSObject()
                    data.put("utteranceId", utteranceId)
                    notifyListeners("ttsDone", data)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    val data = JSObject()
                    data.put("utteranceId", utteranceId)
                    data.put("error", "unknown")
                    notifyListeners("ttsError", data)
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val data = JSObject()
                    data.put("utteranceId", utteranceId)
                    data.put("start", start)
                    data.put("end", end)
                    notifyListeners("ttsRangeStart", data)
                }
            })
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status $status")
        }
    }

    @PluginMethod
    fun initialize(call: PluginCall) {
        val result = JSObject()
        result.put("ready", isReady)
        call.resolve(result)
    }

    @PluginMethod
    fun getVoices(call: PluginCall) {
        val voicesArray = JSArray()
        tts?.voices?.forEach { voice ->
            val v = JSObject()
            v.put("name", voice.name)
            v.put("lang", voice.locale.toLanguageTag())
            v.put("localService", !voice.isNetworkConnectionRequired)
            voicesArray.put(v)
        }
        val result = JSObject()
        result.put("voices", voicesArray)
        call.resolve(result)
    }

    @PluginMethod
    fun speak(call: PluginCall) {
        if (!isReady) {
            call.reject("TTS not initialized")
            return
        }
        val text = call.getString("text") ?: run { call.reject("text required"); return }
        val utteranceId = call.getString("utteranceId") ?: "tts_utt"
        val callRate = call.getFloat("rate", rate) ?: rate
        val callPitch = call.getFloat("pitch", pitch) ?: pitch
        val voiceName = call.getString("voiceName")

        tts?.setSpeechRate(callRate)
        tts?.setPitch(callPitch)

        if (voiceName != null) {
            val voice = tts?.voices?.find { it.name == voiceName }
            if (voice != null) tts?.voice = voice
        } else if (selectedVoice != null) {
            tts?.voice = selectedVoice
        }

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        val queueResult = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        val result = JSObject()
        result.put("started", queueResult == TextToSpeech.SUCCESS)
        call.resolve(result)
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        tts?.stop()
        val result = JSObject()
        result.put("stopped", true)
        call.resolve(result)
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        // Android TTS has no native pause — stop and remember position
        tts?.stop()
        val result = JSObject()
        result.put("paused", true)
        call.resolve(result)
    }

    @PluginMethod
    fun resume(call: PluginCall) {
        // Resume handled at JS layer by re-calling speak from saved index
        val result = JSObject()
        result.put("resumed", true)
        call.resolve(result)
    }

    @PluginMethod
    fun setRate(call: PluginCall) {
        rate = call.getFloat("rate", 1.0f) ?: 1.0f
        tts?.setSpeechRate(rate)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun setPitch(call: PluginCall) {
        pitch = call.getFloat("pitch", 1.0f) ?: 1.0f
        tts?.setPitch(pitch)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun setVoice(call: PluginCall) {
        val voiceName = call.getString("voiceName")
        if (voiceName != null) {
            selectedVoice = tts?.voices?.find { it.name == voiceName }
        }
        call.resolve(JSObject().put("ok", true))
    }

    override fun handleOnDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.handleOnDestroy()
    }
}
