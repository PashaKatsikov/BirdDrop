package com.birddrop.birddropgame

import android.app.Application
import com.birddrop.birddropgame.audio.SoundManager
import com.birddrop.birddropgame.data.GamePrefs

class BirdDropApp : Application() {
    lateinit var prefs: GamePrefs
        private set
    lateinit var soundManager: SoundManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = GamePrefs(this)
        soundManager = SoundManager(this)
    }

    companion object {
        lateinit var instance: BirdDropApp
            private set
    }
}
