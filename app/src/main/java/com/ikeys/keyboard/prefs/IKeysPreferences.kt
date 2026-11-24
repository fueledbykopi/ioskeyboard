package com.ikeys.keyboard.prefs

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode(val key: String, val displayName: String) {
    AUTO("auto", "Automatic"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class HapticStrength(val key: String, val displayName: String, val durationMs: Long, val amplitude: Int) {
    OFF("off", "Off", 0L, 0),
    LIGHT("light", "Light (Taptic)", 8L, 48),
    MEDIUM("medium", "Medium", 12L, 95),
    STRONG("strong", "Strong", 18L, 155);

    companion object {
        fun fromKey(key: String?): HapticStrength = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

enum class HeightPreset(val key: String, val displayName: String, val rowHeightDp: Float) {
    COMPACT("compact", "Compact", 40f),
    STANDARD("standard", "Standard (iOS)", 44f),
    TALL("tall", "Tall", 49f);

    companion object {
        fun fromKey(key: String?): HeightPreset = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/**
 * Lightweight, synchronous SharedPreferences wrapper for instant zero-lag IME reads.
 */
class IKeysPreferences(context: Context) {

    val sharedPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(sharedPrefs.getString(KEY_THEME, ThemeMode.AUTO.key))
        set(value) = sharedPrefs.edit().putString(KEY_THEME, value.key).apply()

    var soundEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var soundVolume: Float
        get() = sharedPrefs.getFloat(KEY_SOUND_VOLUME, 0.78f).coerceIn(0.1f, 1.0f)
        set(value) = sharedPrefs.edit().putFloat(KEY_SOUND_VOLUME, value.coerceIn(0.1f, 1.0f)).apply()

    var hapticStrength: HapticStrength
        get() = HapticStrength.fromKey(sharedPrefs.getString(KEY_HAPTIC, HapticStrength.LIGHT.key))
        set(value) = sharedPrefs.edit().putString(KEY_HAPTIC, value.key).apply()

    var keyPopupEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_POPUP_ENABLED, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_POPUP_ENABLED, value).apply()

    var predictionBarEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_PREDICTION_BAR, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_PREDICTION_BAR, value).apply()

    var autoCapitalize: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_CAP, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_AUTO_CAP, value).apply()

    var doubleSpacePeriod: Boolean
        get() = sharedPrefs.getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_DOUBLE_SPACE_PERIOD, value).apply()

    var autoCorrect: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_CORRECT, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_AUTO_CORRECT, value).apply()

    var bottomChinEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_BOTTOM_CHIN, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_BOTTOM_CHIN, value).apply()

    var heightPreset: HeightPreset
        get() = HeightPreset.fromKey(sharedPrefs.getString(KEY_HEIGHT_PRESET, HeightPreset.STANDARD.key))
        set(value) = sharedPrefs.edit().putString(KEY_HEIGHT_PRESET, value.key).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "ikeys_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_HAPTIC = "haptic_strength"
        private const val KEY_POPUP_ENABLED = "popup_enabled"
        private const val KEY_PREDICTION_BAR = "prediction_bar"
        private const val KEY_AUTO_CAP = "auto_capitalize"
        private const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"
        private const val KEY_AUTO_CORRECT = "auto_correct"
        private const val KEY_BOTTOM_CHIN = "bottom_chin"
        private const val KEY_HEIGHT_PRESET = "height_preset"
    }
}
