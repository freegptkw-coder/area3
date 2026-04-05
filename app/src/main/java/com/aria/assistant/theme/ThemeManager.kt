package com.aria.assistant.theme

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.aria.assistant.R

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AUTO // Update #14: auto-switch dark/light based on time
}

enum class ThemePalette {
    AURORA,
    SAKURA,
    OCEAN,
    LAVENDER,
    MIDNIGHT,
    SUNSET
}

data class ThemeConfig(
    val mode: ThemeMode,
    val palette: ThemePalette
)

object ThemeManager {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_THEME_MODE = "app_theme_mode"
    private const val KEY_THEME_PALETTE = "app_theme_palette"

    fun load(context: Context): ThemeConfig {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val mode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name).orEmpty())
        }.getOrDefault(ThemeMode.DARK)

        val palette = runCatching {
            ThemePalette.valueOf(prefs.getString(KEY_THEME_PALETTE, ThemePalette.AURORA.name).orEmpty())
        }.getOrDefault(ThemePalette.AURORA)

        return ThemeConfig(mode = mode, palette = palette)
    }

    fun save(context: Context, config: ThemeConfig) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, config.mode.name)
            .putString(KEY_THEME_PALETTE, config.palette.name)
            .apply()
    }

    fun applySavedTheme(context: Context) {
        applyTheme(load(context).mode)
    }

    fun applyTheme(mode: ThemeMode) {
        val appMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.AUTO -> {
                // Auto-switch: dark 7PM-7AM, light 7AM-7PM
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (hour >= 19 || hour < 7) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            }
        }
        AppCompatDelegate.setDefaultNightMode(appMode)
    }

    fun paletteLabels(): Array<String> = arrayOf(
        "🌌 Aurora Glow",
        "🌸 Sakura Pink",
        "🌊 Ocean Cyan",
        "💜 Lavender Dream",
        "🌙 Midnight Blue",
        "🌅 Sunset Ember"
    )

    fun modeLabels(): Array<String> = arrayOf(
        "System",
        "Light",
        "Dark",
        "Auto (7PM-7AM)"
    )

    fun modeValues(): Array<ThemeMode> = arrayOf(
        ThemeMode.SYSTEM,
        ThemeMode.LIGHT,
        ThemeMode.DARK,
        ThemeMode.AUTO
    )

    fun paletteValues(): Array<ThemePalette> = arrayOf(
        ThemePalette.AURORA,
        ThemePalette.SAKURA,
        ThemePalette.OCEAN,
        ThemePalette.LAVENDER,
        ThemePalette.MIDNIGHT,
        ThemePalette.SUNSET
    )

    fun resolveAccentColor(context: Context): Int {
        val config = load(context)
        return when (config.palette) {
            ThemePalette.AURORA -> Color.parseColor("#82A2FF")
            ThemePalette.SAKURA -> Color.parseColor("#F58CCB")
            ThemePalette.OCEAN -> Color.parseColor("#5DD6E8")
            ThemePalette.LAVENDER -> Color.parseColor("#B69CFF")
            ThemePalette.MIDNIGHT -> Color.parseColor("#6E8FFF")
            ThemePalette.SUNSET -> Color.parseColor("#FF6B4A")
        }
    }

    fun resolveMainGradient(context: Context): IntArray {
        val config = load(context)

        return when (config.palette) {
            ThemePalette.AURORA -> intArrayOf(Color.parseColor("#161B2D"), Color.parseColor("#27375F"))
            ThemePalette.SAKURA -> intArrayOf(Color.parseColor("#2C1A2F"), Color.parseColor("#5C2F5D"))
            ThemePalette.OCEAN -> intArrayOf(Color.parseColor("#122831"), Color.parseColor("#1E5066"))
            ThemePalette.LAVENDER -> intArrayOf(Color.parseColor("#201A30"), Color.parseColor("#3E2F5E"))
            ThemePalette.MIDNIGHT -> intArrayOf(Color.parseColor("#10152A"), Color.parseColor("#0E1B3F"))
            ThemePalette.SUNSET -> intArrayOf(Color.parseColor("#1A0A1E"), Color.parseColor("#3D0F2E"))
        }
    }

    fun isDarkMode(context: Context): Boolean {
        return when (load(context).mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO -> {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                hour >= 19 || hour < 7
            }
            ThemeMode.SYSTEM -> {
                val nightModeFlags = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun resolveMascotDrawable(context: Context): Int {
        return if (isDarkMode(context)) R.drawable.ic_mascot_avatar_new else R.drawable.ic_mascot_avatar_light
    }
}
