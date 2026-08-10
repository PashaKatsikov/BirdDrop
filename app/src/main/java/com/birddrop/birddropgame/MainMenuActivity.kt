package com.birddrop.birddropgame

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainMenuActivity : AppCompatActivity() {

    private lateinit var crystalCount: TextView
    private lateinit var eggCount: TextView
    private lateinit var logo: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        setContentView(R.layout.activity_main_menu)

        crystalCount = findViewById(R.id.crystalCount)
        eggCount = findViewById(R.id.eggCount)
        logo = findViewById(R.id.menuLogo)

        val play = findViewById<MaterialButton>(R.id.btnPlay)
        play.setOnClickListener {
            click(it)
            startActivity(Intent(this, LevelSelectActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            click(it)
            showSettings()
        }
        findViewById<TextView>(R.id.linkPrivacy).setOnClickListener {
            click(it)
            openWeb(getString(R.string.privacy_policy), getString(R.string.privacy_url))
        }
        findViewById<TextView>(R.id.linkSupport).setOnClickListener {
            click(it)
            openWeb(getString(R.string.support), getString(R.string.support_url))
        }

        introAnimation(play)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        refreshCurrency()
    }

    private fun refreshCurrency() {
        val prefs = BirdDropApp.instance.prefs
        crystalCount.text = prefs.crystals.toString()
        eggCount.text = prefs.eggs.toString()
    }

    private fun introAnimation(play: View) {
        logo.alpha = 0f
        logo.translationY = -40f
        logo.animate().alpha(1f).translationY(0f).setDuration(520)
            .setInterpolator(DecelerateInterpolator()).start()

        play.alpha = 0f
        play.scaleX = 0.85f
        play.scaleY = 0.85f
        play.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(180).setDuration(420)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun showSettings() {
        val prefs = BirdDropApp.instance.prefs
        BirdDropApp.instance.soundManager.play(R.raw.menu_open)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val sound = dialog.findViewById<SwitchMaterial>(R.id.switchSound)
        val vibration = dialog.findViewById<SwitchMaterial>(R.id.switchVibration)
        sound.isChecked = prefs.soundEnabled
        vibration.isChecked = prefs.vibrationEnabled
        sound.setOnCheckedChangeListener { _, checked -> prefs.soundEnabled = checked }
        vibration.setOnCheckedChangeListener { _, checked -> prefs.vibrationEnabled = checked }

        dialog.findViewById<TextView>(R.id.settingsPrivacy).setOnClickListener {
            openWeb(getString(R.string.privacy_policy), getString(R.string.privacy_url))
        }
        dialog.findViewById<TextView>(R.id.settingsSupport).setOnClickListener {
            openWeb(getString(R.string.support), getString(R.string.support_url))
        }
        dialog.findViewById<MaterialButton>(R.id.btnCloseSettings).setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.menu_close)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openWeb(title: String, url: String) {
        startActivity(
            Intent(this, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_TITLE, title)
                .putExtra(WebViewActivity.EXTRA_URL, url)
        )
    }

    private fun click(view: View) {
        BirdDropApp.instance.soundManager.play(R.raw.button_click)
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        }.start()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
