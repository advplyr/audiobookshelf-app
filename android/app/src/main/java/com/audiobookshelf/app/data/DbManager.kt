package com.audiobookshelf.app.data

import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.paperdb.Paper
import org.json.JSONObject

@CapacitorPlugin(name = "DbManager")
class DbManager : Plugin() {
  val tag = "DbManager"

  fun saveObject(db:String, key:String, value:JSONObject) {
    Log.d(tag, "Saving Object $key ${value.toString()}")
    Paper.book(db).write(key, value)
  }

  fun loadObject(db:String, key:String):JSONObject? {
    var json: JSONObject? = Paper.book(db).read(key)
    Log.d(tag, "Loaded Object $key $json")
    return json
  }

  @PluginMethod
  fun saveFromWebview(call: PluginCall) {
    var db = call.getString("db", "").toString()
    var key = call.getString("key", "").toString()
    var value = call.getObject("value")
    if (db == "" || key == "" || value == null) {
      Log.d(tag, "saveFromWebview Invalid key/value")
    } else {
      var json = value as JSONObject
      saveObject(db, key, json)
    }
    call.resolve()
  }

  @PluginMethod
  fun loadFromWebview(call:PluginCall) {
    var db = call.getString("db", "").toString()
    var key = call.getString("key", "").toString()
    if (db == "" || key == "") {
      Log.d(tag, "loadFromWebview Invalid Key")
      call.resolve()
      return
    }
    var json = loadObject(db, key)
    var jsobj = JSObject.fromJSONObject(json)
    call.resolve(jsobj)
  }
}
