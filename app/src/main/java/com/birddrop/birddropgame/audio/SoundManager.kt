package com.birddrop.birddropgame.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.birddrop.birddropgame.BirdDropApp
import com.birddrop.birddropgame.R

class SoundManager(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val sounds = mutableMapOf<Int, Int>()

    init {
        val ids = listOf(
            R.raw.button_click,
            R.raw.menu_open,
            R.raw.menu_close,
            R.raw.bird_launch,
            R.raw.bird_flying,
            R.raw.bird_impact,
            R.raw.building_destruction,
            R.raw.device_activation,
            R.raw.cannon_loading,
            R.raw.level_start,
            R.raw.foe_hit,
            R.raw.reward_collect,
            R.raw.upgrade,
            R.raw.victory,
            R.raw.defeat
        )
        ids.forEach { resId ->
            sounds[resId] = soundPool.load(context, resId, 1)
        }
    }

    fun play(resId: Int) {
        if (!BirdDropApp.instance.prefs.soundEnabled) return
        val soundId = sounds[resId] ?: return
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
