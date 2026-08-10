package com.birddrop.birddropgame.data

import android.content.Context
import android.content.SharedPreferences

class GamePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bird_drop_prefs", Context.MODE_PRIVATE)

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION, value).apply()

    var crystals: Int
        get() = prefs.getInt(KEY_CRYSTALS, 120)
        set(value) = prefs.edit().putInt(KEY_CRYSTALS, value).apply()

    var eggs: Int
        get() = prefs.getInt(KEY_EGGS, 3)
        set(value) = prefs.edit().putInt(KEY_EGGS, value).apply()

    var maxUnlockedLevel: Int
        get() = prefs.getInt(KEY_UNLOCKED, 1)
        set(value) = prefs.edit().putInt(KEY_UNLOCKED, value).apply()

    fun getStars(level: Int): Int = prefs.getInt(KEY_STARS + level, 0)

    fun setStars(level: Int, stars: Int) {
        val current = getStars(level)
        if (stars > current) {
            prefs.edit().putInt(KEY_STARS + level, stars).apply()
        }
    }

    companion object {
        private const val KEY_SOUND = "sound"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_CRYSTALS = "crystals"
        private const val KEY_EGGS = "eggs"
        private const val KEY_UNLOCKED = "unlocked"
        private const val KEY_STARS = "stars_"
        const val TOTAL_LEVELS = 12
    }
}
