package com.birddrop.birddropgame.boot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.birddrop.birddropgame.R
import com.birddrop.birddropgame.env.PrefStore

/**
 * Push-notification permission screen, shown before the WebView.
 *
 * Skip postpones it for three days. Accept hands the user the system dialog and
 * retires this screen for good, whichever way that dialog is answered — see
 * [PrefStore.notifPromoClosed] and pitfalls #36.
 *
 * Buttons: ACCEPT  /  SKIP
 * Assets: portrait/landscape branded PNG backgrounds.
 */
class GorgeAlert : AppCompatActivity() {

    private lateinit var vault: PrefStore
    private var pendingUrl: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = PrefStore(applicationContext)
        pendingUrl = intent.getStringExtra(EXTRA_TARGET_URL)

        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bgRes = if (isLandscape) R.drawable.cn_notif_landscape
                    else R.drawable.cn_notif_portrait

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Background image.
        val bg = ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(bg)

        // Buttons pinned to the bottom. Portrait stacks them (ACCEPT on top) and
        // makes them larger; landscape keeps them side by side.
        val portrait = !isLandscape
        val btnRow = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            lp.bottomMargin = dpToPx(if (portrait) 56 else 40)
            layoutParams = lp
        }

        val btnW = if (portrait) 260 else 173
        val btnH = if (portrait) 64 else 54
        val acceptBtn = buildButton("ACCEPT", accent = true, btnW, btnH)
        val skipBtn   = buildButton("SKIP",   accent = false, btnW, btnH)

        acceptBtn.setOnClickListener { onAccept() }
        skipBtn.setOnClickListener   { onSkip()   }

        btnRow.addView(acceptBtn)
        btnRow.addView(gap(vertical = portrait, 16))
        btnRow.addView(skipBtn)
        root.addView(btnRow)

        setContentView(root)
        com.birddrop.birddropgame.FullScreen.apply(this)
    }

    private fun onAccept() {
        // Written before the dialog opens rather than once it answers: the answer
        // does not change what happens to this screen, and the app can be swiped
        // away while the dialog is up — which would leave the promo due again,
        // over a permission the user has already been asked for.
        vault.notifPromoClosed = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            proceed()
            return
        }
        val already = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (already) proceed() else permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun onSkip() {
        vault.snoozeNotifPrompt()
        proceed()
    }

    /**
     * Straight to the shell, and deliberately not back through the launcher: the
     * splash has had its one showing for this launch, and bringing it back after the
     * permission dialog reads as the app restarting.
     */
    private fun proceed() {
        val next = Intent(this, CanyonShell::class.java).apply {
            pendingUrl?.let { putExtra(CanyonShell.EXTRA_STREAM_URL, it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(next)
        finish()
    }

    private fun buildButton(label: String, accent: Boolean, wDp: Int, hDp: Int): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = if (accent) 18f else 17f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dpToPx(28), dpToPx(12), dpToPx(28), dpToPx(12))
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        tv.letterSpacing = 0.06f
        if (accent) {
            tv.setBackgroundResource(R.drawable.cn_btn_accept)
            tv.setTextColor(Color.parseColor("#1A0A00"))
            tv.setShadowLayer(2f, 0f, 1f, Color.parseColor("#55FFFFFF"))
        } else {
            tv.setBackgroundResource(R.drawable.cn_btn_skip)
            tv.setTextColor(Color.parseColor("#FFD700"))
            tv.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        tv.layoutParams = LinearLayout.LayoutParams(dpToPx(wDp), dpToPx(hDp))
        return tv
    }

    private fun gap(vertical: Boolean, dp: Int): View = View(this).apply {
        layoutParams = if (vertical) LinearLayout.LayoutParams(1, dpToPx(dp))
                       else LinearLayout.LayoutParams(dpToPx(dp), 1)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    companion object {
        const val EXTRA_TARGET_URL = "alert_target_url"
    }
}
