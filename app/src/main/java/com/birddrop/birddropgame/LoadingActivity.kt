package com.birddrop.birddropgame

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class LoadingActivity : AppCompatActivity() {

    private lateinit var loadingBackground: ImageView
    private lateinit var loadingLogo: ImageView
    private lateinit var loadingText: TextView
    private lateinit var loadingProgress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var dots: Runnable? = null
    private var crawl: ValueAnimator? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        setContentView(R.layout.activity_loading)

        loadingBackground = findViewById(R.id.loadingBackground)
        loadingLogo = findViewById(R.id.loadingLogo)
        loadingText = findViewById(R.id.loadingText)
        loadingProgress = findViewById(R.id.loadingProgress)

        applyBackground(resources.configuration.orientation)
        animateLogo()
        startDots()
        startWarmup()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBackground(newConfig.orientation)
    }

    private fun applyBackground(orientation: Int) {
        loadingBackground.setImageResource(
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                R.drawable.loading_horizontal
            } else {
                R.drawable.loading_vertical
            }
        )
    }

    private fun animateLogo() {
        loadingLogo.alpha = 0f
        loadingLogo.scaleX = 0.82f
        loadingLogo.scaleY = 0.82f
        loadingLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(650)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun startDots() {
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                loadingText.text = getString(R.string.loading) + ".".repeat(step % 4)
                step++
                handler.postDelayed(this, 380)
            }
        }
        dots = runnable
        handler.post(runnable)
    }

    /**
     * The bar creeps towards 92% while assets warm up and only snaps to a full bar
     * in the instant before the menu opens.
     */
    private fun startWarmup() {
        val started = System.currentTimeMillis()
        val minDuration = 2400L

        crawl = ValueAnimator.ofInt(0, 920).apply {
            duration = minDuration
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!finished) loadingProgress.progress = it.animatedValue as Int
            }
            start()
        }

        Thread {
            runCatching {
                listOf(
                    "loading_horizontal", "game_logo", "bg_green_valley", "cannon",
                    "bird_red", "bird_blue", "bird_yellow", "bird_green",
                    "foe_grunt", "wood_1", "wood_2", "device_booster"
                ).forEach { name ->
                    val id = resources.getIdentifier(name, "drawable", packageName)
                    if (id != 0) BitmapFactory.decodeResource(resources, id)?.recycle()
                }
                BirdDropApp.instance.soundManager
            }
            val remaining = minDuration - (System.currentTimeMillis() - started)
            if (remaining > 0) runCatching { Thread.sleep(remaining) }
            runOnUiThread { complete() }
        }.start()
    }

    private fun complete() {
        if (finished || isFinishing) return
        finished = true
        crawl?.cancel()

        ValueAnimator.ofInt(loadingProgress.progress, 1000).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { loadingProgress.progress = it.animatedValue as Int }
            start()
        }
        loadingText.text = getString(R.string.loading) + "..."

        handler.postDelayed({
            if (isFinishing) return@postDelayed
            startActivity(Intent(this, MainMenuActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 300)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        dots?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        crawl?.cancel()
        super.onDestroy()
    }
}
