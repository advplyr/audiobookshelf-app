package com.audiobookshelf.app.plugins

import android.util.Log
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "AbsCredentialManager")
class AbsCredentialManager : Plugin() {
  private val tag = "AbsCredentialManager"

  @PluginMethod
  fun saveCredential(call: PluginCall) {
    val username = call.getString("username")
    val password = call.getString("password")

    if (username.isNullOrBlank() || password.isNullOrBlank()) {
      call.reject("Username and password are required")
      return
    }

    CoroutineScope(Dispatchers.Main).launch {
      try {
        val credentialManager = CredentialManager.create(activity)
        val request = CreatePasswordRequest(id = username, password = password)
        credentialManager.createCredential(activity, request)
        Log.d(tag, "Credential saved for $username")
        call.resolve()
      } catch (e: CreateCredentialException) {
        Log.e(tag, "Failed to save credential: ${e.type} / ${e.message}")
        call.reject("Failed to save credential: ${e.message}")
      }
    }
  }

  @PluginMethod
  fun getCredential(call: PluginCall) {
    CoroutineScope(Dispatchers.Main).launch {
      try {
        val credentialManager = CredentialManager.create(activity)
        val request = GetCredentialRequest(
          credentialOptions = listOf(GetPasswordOption())
        )
        val response = credentialManager.getCredential(activity, request)
        val credential = response.credential

        if (credential is PasswordCredential) {
          val result = JSObject()
          result.put("username", credential.id)
          result.put("password", credential.password)
          Log.d(tag, "Retrieved credential for ${credential.id}")
          call.resolve(result)
        } else {
          call.reject("Unexpected credential type: ${credential.type}")
        }
      } catch (e: NoCredentialException) {
        Log.d(tag, "No saved credentials found")
        val result = JSObject()
        result.put("username", null)
        result.put("password", null)
        call.resolve(result)
      } catch (e: GetCredentialException) {
        Log.e(tag, "Failed to get credential: ${e.type} / ${e.message}")
        call.reject("Failed to get credential: ${e.message}")
      }
    }
  }
}
