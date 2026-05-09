package com.audiobookshelf.app.utils

import android.util.Log

object DebugUtils {
  private const val MAX_STACK_DEPTH = 10

  fun logMethodEntry(tag: String, methodName: String) {
    val stackTrace = Thread.currentThread().stackTrace
    val callerInfo =
            if (stackTrace.size > 3) {
              "Called from: ${stackTrace[3].className}.${stackTrace[3].methodName}:${stackTrace[3].lineNumber}"
            } else {
              "Caller unknown"
            }

    Log.d(tag, "🔵 ENTER: $methodName [Thread: ${Thread.currentThread().name}] - $callerInfo")
  }

  fun logMethodExit(tag: String, methodName: String, durationMs: Long = -1) {
    val duration = if (durationMs >= 0) " (${durationMs}ms)" else ""
    Log.d(tag, "🔴 EXIT: $methodName [Thread: ${Thread.currentThread().name}]$duration")
  }

  fun logLongOperation(tag: String, operationName: String, thresholdMs: Long = 100): () -> Unit {
    val startTime = System.currentTimeMillis()
    Log.d(tag, "⏱️ START: $operationName [Thread: ${Thread.currentThread().name}]")

    return {
      val duration = System.currentTimeMillis() - startTime
      val emoji = if (duration > thresholdMs) "⚠️" else "✅"
      Log.d(
              tag,
              "$emoji FINISH: $operationName took ${duration}ms [Thread: ${Thread.currentThread().name}]"
      )
    }
  }

  fun logThreadInfo(tag: String, context: String = "") {
    val thread = Thread.currentThread()
    Log.d(
            tag,
            "🧵 THREAD INFO $context: Name=${thread.name}, ID=${thread.id}, State=${thread.state}, Priority=${thread.priority}"
    )
  }

  fun logStackTrace(tag: String, message: String = "Stack trace") {
    val stackTrace = Thread.currentThread().stackTrace
    Log.d(tag, "📋 $message:")
    stackTrace.take(MAX_STACK_DEPTH).forEachIndexed { index, element ->
      Log.d(tag, "  $index: ${element.className}.${element.methodName}:${element.lineNumber}")
    }
  }
}
