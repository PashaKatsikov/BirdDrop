package com.birddrop.birddropgame

import android.app.Application
import com.birddrop.birddropgame.audio.SoundManager
import com.birddrop.birddropgame.data.GamePrefs
import com.birddrop.birddropgame.store.Trace
import com.birddrop.birddropgame.store.UrlGuard
import com.birddrop.birddropgame.surface.AttrCanyon
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Single Application for the app: it owns both the game singletons (prefs,
 * sound) and the gray-part launch bootstrap (Firebase + AppsFlyer prime).
 *
 * The gray flow needs `init`/`prime` in Application.onCreate — moving it out is
 * one of the documented ways to lose attribution (kotlin_launch_flow.mdc §1,
 * pitfalls #19). The router reads [trackingDispatch] off this Application.
 */
class BirdDropApp : Application() {
    lateinit var prefs: GamePrefs
        private set
    lateinit var soundManager: SoundManager
        private set

    lateinit var trackingDispatch: AttrCanyon
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Game singletons ──
        prefs = GamePrefs(this)
        soundManager = SoundManager(this)

        // ── Gray part: Firebase + AppCheck (best-effort) ──
        try {
            FirebaseApp.initializeApp(this)
            val fac = if (BuildConfig.DEBUG)
                DebugAppCheckProviderFactory.getInstance()
            else
                PlayIntegrityAppCheckProviderFactory.getInstance()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(fac)
        } catch (e: Exception) {
            Trace.w(TAG, "Firebase not configured — gray flow will still try the config POST", e)
        }

        // Warn once if the operator did not fill in gray.allowedHosts.
        UrlGuard.warnIfMissing()

        // ── Gray part: AppsFlyer prime (init only, no traffic yet) ──
        trackingDispatch = AttrCanyon(this)
        trackingDispatch.prime()
    }

    companion object {
        private const val TAG = "BirdDropApp"

        lateinit var instance: BirdDropApp
            private set
    }
}
