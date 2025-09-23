package com.tomesonic.app.plugins

import android.content.Context
import android.os.Build
import android.util.TypedValue
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "DynamicColor")
class DynamicColorPlugin : Plugin() {

    @PluginMethod
    fun getSystemColors(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val context: Context = getContext()
                val colors = JSObject()
                val resources = context.resources

                // Check if dark theme is requested
                val isDark = call.getBoolean("isDark") ?: false

                // Get Material You dynamic colors from Android 12+
                // Use appropriate color variants based on theme
                if (isDark) {
                    // Dark theme colors - using only guaranteed available colors
                    // Primary colors
                    val primaryColor = resources.getColor(android.R.color.system_accent1_200, context.theme)
                    colors.put("primary", String.format("#%06X", (0xFFFFFF and primaryColor)))

                    val onPrimaryColor = resources.getColor(android.R.color.system_accent1_800, context.theme)
                    colors.put("onPrimary", String.format("#%06X", (0xFFFFFF and onPrimaryColor)))

                    val primaryContainerColor = resources.getColor(android.R.color.system_accent1_700, context.theme)
                    colors.put("primaryContainer", String.format("#%06X", (0xFFFFFF and primaryContainerColor)))

                    val onPrimaryContainerColor = resources.getColor(android.R.color.system_accent1_100, context.theme)
                    colors.put("onPrimaryContainer", String.format("#%06X", (0xFFFFFF and onPrimaryContainerColor)))

                    // Secondary colors
                    val secondaryColor = resources.getColor(android.R.color.system_accent2_200, context.theme)
                    colors.put("secondary", String.format("#%06X", (0xFFFFFF and secondaryColor)))

                    val onSecondaryColor = resources.getColor(android.R.color.system_accent2_800, context.theme)
                    colors.put("onSecondary", String.format("#%06X", (0xFFFFFF and onSecondaryColor)))

                    val secondaryContainerColor = resources.getColor(android.R.color.system_accent2_700, context.theme)
                    colors.put("secondaryContainer", String.format("#%06X", (0xFFFFFF and secondaryContainerColor)))

                    val onSecondaryContainerColor = resources.getColor(android.R.color.system_accent2_100, context.theme)
                    colors.put("onSecondaryContainer", String.format("#%06X", (0xFFFFFF and onSecondaryContainerColor)))

                    // Tertiary colors
                    val tertiaryColor = resources.getColor(android.R.color.system_accent3_200, context.theme)
                    colors.put("tertiary", String.format("#%06X", (0xFFFFFF and tertiaryColor)))

                    val onTertiaryColor = resources.getColor(android.R.color.system_accent3_800, context.theme)
                    colors.put("onTertiary", String.format("#%06X", (0xFFFFFF and onTertiaryColor)))

                    val tertiaryContainerColor = resources.getColor(android.R.color.system_accent3_700, context.theme)
                    colors.put("tertiaryContainer", String.format("#%06X", (0xFFFFFF and tertiaryContainerColor)))

                    val onTertiaryContainerColor = resources.getColor(android.R.color.system_accent3_100, context.theme)
                    colors.put("onTertiaryContainer", String.format("#%06X", (0xFFFFFF and onTertiaryContainerColor)))

                    // Surface colors - dark theme
                    val surfaceColor = resources.getColor(android.R.color.system_neutral1_900, context.theme)
                    colors.put("surface", String.format("#%06X", (0xFFFFFF and surfaceColor)))

                    val onSurfaceColor = resources.getColor(android.R.color.system_neutral1_100, context.theme)
                    colors.put("onSurface", String.format("#%06X", (0xFFFFFF and onSurfaceColor)))

                    val surfaceVariantColor = resources.getColor(android.R.color.system_neutral2_800, context.theme)
                    colors.put("surfaceVariant", String.format("#%06X", (0xFFFFFF and surfaceVariantColor)))

                    val onSurfaceVariantColor = resources.getColor(android.R.color.system_neutral2_200, context.theme)
                    colors.put("onSurfaceVariant", String.format("#%06X", (0xFFFFFF and onSurfaceVariantColor)))

                    // Background colors - dark theme
                    val backgroundColor = resources.getColor(android.R.color.system_neutral1_900, context.theme)
                    colors.put("background", String.format("#%06X", (0xFFFFFF and backgroundColor)))

                    val onBackgroundColor = resources.getColor(android.R.color.system_neutral1_100, context.theme)
                    colors.put("onBackground", String.format("#%06X", (0xFFFFFF and onBackgroundColor)))

                    // Outline colors - dark theme
                    val outlineColor = resources.getColor(android.R.color.system_neutral2_500, context.theme)
                    colors.put("outline", String.format("#%06X", (0xFFFFFF and outlineColor)))

                    val outlineVariantColor = resources.getColor(android.R.color.system_neutral2_700, context.theme)
                    colors.put("outlineVariant", String.format("#%06X", (0xFFFFFF and outlineVariantColor)))

                } else {
                    // Light theme colors - using only guaranteed available colors
                    // Primary colors
                    val primaryColor = resources.getColor(android.R.color.system_accent1_600, context.theme)
                    colors.put("primary", String.format("#%06X", (0xFFFFFF and primaryColor)))

                    val onPrimaryColor = resources.getColor(android.R.color.system_accent1_0, context.theme)
                    colors.put("onPrimary", String.format("#%06X", (0xFFFFFF and onPrimaryColor)))

                    val primaryContainerColor = resources.getColor(android.R.color.system_accent1_100, context.theme)
                    colors.put("primaryContainer", String.format("#%06X", (0xFFFFFF and primaryContainerColor)))

                    val onPrimaryContainerColor = resources.getColor(android.R.color.system_accent1_900, context.theme)
                    colors.put("onPrimaryContainer", String.format("#%06X", (0xFFFFFF and onPrimaryContainerColor)))

                    // Secondary colors
                    val secondaryColor = resources.getColor(android.R.color.system_accent2_600, context.theme)
                    colors.put("secondary", String.format("#%06X", (0xFFFFFF and secondaryColor)))

                    val onSecondaryColor = resources.getColor(android.R.color.system_accent2_0, context.theme)
                    colors.put("onSecondary", String.format("#%06X", (0xFFFFFF and onSecondaryColor)))

                    val secondaryContainerColor = resources.getColor(android.R.color.system_accent2_100, context.theme)
                    colors.put("secondaryContainer", String.format("#%06X", (0xFFFFFF and secondaryContainerColor)))

                    val onSecondaryContainerColor = resources.getColor(android.R.color.system_accent2_900, context.theme)
                    colors.put("onSecondaryContainer", String.format("#%06X", (0xFFFFFF and onSecondaryContainerColor)))

                    // Tertiary colors
                    val tertiaryColor = resources.getColor(android.R.color.system_accent3_600, context.theme)
                    colors.put("tertiary", String.format("#%06X", (0xFFFFFF and tertiaryColor)))

                    val onTertiaryColor = resources.getColor(android.R.color.system_accent3_0, context.theme)
                    colors.put("onTertiary", String.format("#%06X", (0xFFFFFF and onTertiaryColor)))

                    val tertiaryContainerColor = resources.getColor(android.R.color.system_accent3_100, context.theme)
                    colors.put("tertiaryContainer", String.format("#%06X", (0xFFFFFF and tertiaryContainerColor)))

                    val onTertiaryContainerColor = resources.getColor(android.R.color.system_accent3_900, context.theme)
                    colors.put("onTertiaryContainer", String.format("#%06X", (0xFFFFFF and onTertiaryContainerColor)))

                    // Surface colors - light theme
                    val surfaceColor = resources.getColor(android.R.color.system_neutral1_50, context.theme)
                    colors.put("surface", String.format("#%06X", (0xFFFFFF and surfaceColor)))

                    val onSurfaceColor = resources.getColor(android.R.color.system_neutral1_900, context.theme)
                    colors.put("onSurface", String.format("#%06X", (0xFFFFFF and onSurfaceColor)))

                    val surfaceVariantColor = resources.getColor(android.R.color.system_neutral2_100, context.theme)
                    colors.put("surfaceVariant", String.format("#%06X", (0xFFFFFF and surfaceVariantColor)))

                    val onSurfaceVariantColor = resources.getColor(android.R.color.system_neutral2_700, context.theme)
                    colors.put("onSurfaceVariant", String.format("#%06X", (0xFFFFFF and onSurfaceVariantColor)))

                    // Background colors - light theme
                    val backgroundColor = resources.getColor(android.R.color.system_neutral1_50, context.theme)
                    colors.put("background", String.format("#%06X", (0xFFFFFF and backgroundColor)))

                    val onBackgroundColor = resources.getColor(android.R.color.system_neutral1_900, context.theme)
                    colors.put("onBackground", String.format("#%06X", (0xFFFFFF and onBackgroundColor)))

                    // Outline colors - light theme
                    val outlineColor = resources.getColor(android.R.color.system_neutral2_500, context.theme)
                    colors.put("outline", String.format("#%06X", (0xFFFFFF and outlineColor)))

                    val outlineVariantColor = resources.getColor(android.R.color.system_neutral2_300, context.theme)
                    colors.put("outlineVariant", String.format("#%06X", (0xFFFFFF and outlineVariantColor)))
                }

                val result = JSObject()
                result.put("colors", colors)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Failed to get system colors: ${e.message}")
            }
        } else {
            val result = JSObject()
            result.put("colors", null)
            call.resolve(result)
        }
    }

    @PluginMethod
    fun isSupported(call: PluginCall) {
        val result = JSObject()
        result.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        call.resolve(result)
    }
}
