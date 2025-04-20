package com.audiobookshelf.app.plugins

import android.util.Log
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.UUID

data class AbsLog(
  var id:String,
  var tag:String,
  var level:String,
  var message:String,
  var timestamp:Long
)

data class AbsLogList(val value:List<AbsLog>)

@CapacitorPlugin(name = "AbsLogger")
class AbsLogger : Plugin() {
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  override fun load() {
    Log.i("AbsLogger", "Initialize AbsLogger plugin")
  }

  companion object {
    fun info(tag:String, message:String) {
      Log.i("AbsLogger", message)
      DeviceManager.dbManager.saveLog(AbsLog(id = UUID.randomUUID().toString(), tag, level = "info", message, timestamp = System.currentTimeMillis()))
    }
    fun error(tag:String, message:String) {
      Log.e("AbsLogger", message)
      DeviceManager.dbManager.saveLog(AbsLog(id = UUID.randomUUID().toString(), tag, level = "error", message, timestamp = System.currentTimeMillis()))
    }
  }

  @PluginMethod
  fun info(call: PluginCall) {
    val msg = call.getString("message") ?: return call.reject("No message")
    val tag = call.getString("tag") ?: ""
    info(tag, msg)
    call.resolve()
  }

  @PluginMethod
  fun error(call: PluginCall) {
    val msg = call.getString("message") ?: return call.reject("No message")
    val tag = call.getString("tag") ?: ""
    error(tag, msg)
    call.resolve()
  }

  @PluginMethod
  fun getAllLogs(call: PluginCall) {
    val absLogs = DeviceManager.dbManager.getAllLogs()
    call.resolve(JSObject(jacksonMapper.writeValueAsString(AbsLogList(absLogs))))
  }

  @PluginMethod
  fun clearLogs(call: PluginCall) {
    DeviceManager.dbManager.removeAllLogs()
    call.resolve()
  }
}
