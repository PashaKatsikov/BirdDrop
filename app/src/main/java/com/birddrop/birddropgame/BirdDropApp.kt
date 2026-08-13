package com.birddrop.birddropgame

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.birddrop.birddropgame.audio.SoundManager
import com.birddrop.birddropgame.data.GamePrefs
import com.birddrop.birddropgame.store.Trace
import com.birddrop.birddropgame.store.UrlGuard
import com.birddrop.birddropgame.surface.AttrCanyon
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.messaging.FirebaseMessaging

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

            // Push-notification prep — done here, not on the config POST path,
            // so a user installed via OneLink who opens the app offline still
            // gets FCM registration started up-front. When connectivity returns
            // (via GorgeOffline → GorgeRouter) Firebase has already been trying
            // to fetch a token and the config POST is far more likely to carry
            // one on the very first successful launch, without a restart.
            ensureFcmChannel()
            runCatching { FirebaseMessaging.getInstance().token }
        } catch (e: Exception) {
            Trace.w(TAG, "Firebase not configured — gray flow will still try the config POST", e)
        }

        // Warn once if the operator did not fill in gray.allowedHosts.
        UrlGuard.warnIfMissing()

        // ── Gray part: AppsFlyer prime (init only, no traffic yet) ──
        trackingDispatch = AttrCanyon(this)
        trackingDispatch.prime()
    }

    /**
     * Create the FCM notification channel proactively so the very first push
     * on this install has somewhere to land — FcmCanyon.ensureChannel would
     * otherwise only run when a message actually arrives, and on some OEMs
     * that first delivery is dropped if the channel does not pre-exist.
     */
    private fun ensureFcmChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(BuildConfig.FCM_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                BuildConfig.FCM_CHANNEL_ID,
                BuildConfig.FCM_CHANNEL_TITLE,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
        )
    }

    companion object {
        private const val TAG = "BirdDropApp"

        lateinit var instance: BirdDropApp
            private set
    }
}
