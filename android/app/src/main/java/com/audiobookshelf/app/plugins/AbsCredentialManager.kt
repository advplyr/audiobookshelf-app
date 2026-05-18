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
import androidx.lifecycle.lifecycleScope
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "AbsCredentialManager")
class AbsCredentialManager : Plugin() {
  private val tag = "AbsCredentialManager"

  @PluginMethod
  fun saveCredential(call: PluginCall) {
    val username = call.getString("username")
    val password = call.getString("password")

    if (username.isNullOrBlank() || password.isNullOrBlank()) {
      Log.e(tag, "saveCredential: username or password is blank")
      call.reject("Username and password are required")
      return
    }

    val currentActivity = activity
    if (currentActivity == null) {
      Log.e(tag, "saveCredential: activity is null")
      call.reject("Activity not available")
      return
    }

    Log.d(tag, "saveCredential: saving credential for $username")

    currentActivity.lifecycleScope.launch {
      try {
        val credentialManager = CredentialManager.create(currentActivity)
        val request = CreatePasswordRequest(id = username, password = password)
        credentialManager.createCredential(currentActivity, request)
        Log.d(tag, "saveCredential: credential saved successfully for $username")
        call.resolve()
      } catch (e: CreateCredentialException) {
        Log.e(tag, "saveCredential failed: type=${e.type} message=${e.message}", e)
        call.reject("Failed to save credential: ${e.type} - ${e.message}")
      } catch (e: Exception) {
        Log.e(tag, "saveCredential unexpected error", e)
        call.reject("Unexpected error: ${e.message}")
      }
    }
  }

  @PluginMethod
  fun getCredential(call: PluginCall) {
    val currentActivity = activity
    if (currentActivity == null) {
      Log.e(tag, "getCredential: activity is null")
      call.reject("Activity not available")
      return
    }

    Log.d(tag, "getCredential: retrieving saved credentials")

    currentActivity.lifecycleScope.launch {
      try {
        val credentialManager = CredentialManager.create(currentActivity)
        val request = GetCredentialRequest(
          credentialOptions = listOf(GetPasswordOption())
        )
        val response = credentialManager.getCredential(currentActivity, request)
        val credential = response.credential

        if (credential is PasswordCredential) {
          Log.d(tag, "getCredential: retrieved credential for ${credential.id}")
          val result = JSObject()
          result.put("username", credential.id)
          result.put("password", credential.password)
          call.resolve(result)
        } else {
          Log.w(tag, "getCredential: unexpected credential type: ${credential.type}")
          call.reject("Unexpected credential type: ${credential.type}")
        }
      } catch (e: NoCredentialException) {
        Log.d(tag, "getCredential: no saved credentials found")
        val result = JSObject()
        result.put("username", null)
        result.put("password", null)
        call.resolve(result)
      } catch (e: GetCredentialException) {
        Log.e(tag, "getCredential failed: type=${e.type} message=${e.message}", e)
        call.reject("Failed to get credential: ${e.type} - ${e.message}")
      } catch (e: Exception) {
        Log.e(tag, "getCredential unexpected error", e)
        call.reject("Unexpected error: ${e.message}")
      }
    }
  }
}
