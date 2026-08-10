package com.birddrop.birddropgame

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.birddrop.birddropgame.game.GameView
import com.birddrop.birddropgame.game.model.BirdType
import com.birddrop.birddropgame.game.model.DeviceType
import com.birddrop.birddropgame.game.model.GamePhase
import com.birddrop.birddropgame.game.model.LevelCatalog
import com.google.android.material.button.MaterialButton

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var levelTitle: TextView
    private lateinit var levelCaption: TextView
    private lateinit var hintText: TextView
    private lateinit var introBanner: TextView
    private lateinit var deviceCount: TextView
    private lateinit var birdTokens: LinearLayout
    private lateinit var birdSelector: LinearLayout
    private lateinit var deviceSelector: LinearLayout
    private lateinit var bottomBar: View
    private lateinit var resultOverlay: View
    private lateinit var resultPanel: View
    private lateinit var resultTitle: TextView
    private lateinit var resultScore: TextView
    private lateinit var resultReward: TextView
    private lateinit var btnGo: MaterialButton
    private lateinit var btnPrimary: MaterialButton
    private lateinit var stars: List<ImageView>

    private var levelId = 1
    private var rewarded = false
    private var pauseDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        setContentView(R.layout.activity_game)

        levelId = intent.getIntExtra(EXTRA_LEVEL_ID, 1)
            .coerceIn(1, LevelCatalog.TOTAL_LEVELS)
        val level = LevelCatalog.get(levelId)

        bindViews()

        gameView = GameView(this)
        gameView.setup(level)
        findViewById<FrameLayout>(R.id.gameContainer).addView(gameView)
        gameView.onPlanChanged = { runOnUiThread { refreshHud() } }
        wireWorldEvents()

        levelTitle.text = level.name
        levelCaption.text = getString(R.string.level_caption, level.chapter, levelId)
        hintText.text = getString(R.string.hint_plan)

        buildSelectors()
        refreshHud()
        showIntro(level.chapter, level.name)

        btnGo.setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.cannon_loading)
            gameView.launch()
            refreshHud()
        }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.button_click)
            gameView.clearDevices()
            refreshHud()
        }
        findViewById<ImageButton>(R.id.btnPause).setOnClickListener { showPause() }

        findViewById<MaterialButton>(R.id.btnResultMenu).setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.button_click)
            finish()
        }
        findViewById<MaterialButton>(R.id.btnResultRestart).setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.button_click)
            restartLevel()
        }
        btnPrimary.setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.button_click)
            when (gameView.world.phase) {
                GamePhase.VICTORY -> goToNextLevel()
                else -> restartLevel()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (resultOverlay.visibility == View.VISIBLE) finish() else showPause()
            }
        })

        BirdDropApp.instance.soundManager.play(R.raw.level_start)
    }

    private fun bindViews() {
        levelTitle = findViewById(R.id.levelTitle)
        levelCaption = findViewById(R.id.levelCaption)
        hintText = findViewById(R.id.hintText)
        introBanner = findViewById(R.id.introBanner)
        deviceCount = findViewById(R.id.deviceCount)
        birdTokens = findViewById(R.id.birdTokens)
        birdSelector = findViewById(R.id.birdSelector)
        deviceSelector = findViewById(R.id.deviceSelector)
        bottomBar = findViewById(R.id.bottomBar)
        resultOverlay = findViewById(R.id.resultOverlay)
        resultPanel = findViewById(R.id.resultPanel)
        resultTitle = findViewById(R.id.resultTitle)
        resultScore = findViewById(R.id.resultScore)
        resultReward = findViewById(R.id.resultReward)
        btnGo = findViewById(R.id.btnGo)
        btnPrimary = findViewById(R.id.btnResultPrimary)
        stars = listOf(
            findViewById(R.id.star1),
            findViewById(R.id.star2),
            findViewById(R.id.star3)
        )
    }

    private fun wireWorldEvents() {
        val sounds = BirdDropApp.instance.soundManager
        val world = gameView.world
        world.onLaunch = {
            sounds.play(R.raw.bird_launch)
            runOnUiThread { refreshHud() }
        }
        world.onDeviceActivated = { sounds.play(R.raw.device_activation) }
        world.onImpact = {
            sounds.play(R.raw.bird_impact)
            vibrate(18)
        }
        world.onFoeHit = { sounds.play(R.raw.foe_hit) }
        world.onBuildingDestroyed = {
            sounds.play(R.raw.building_destruction)
            vibrate(28)
        }
        world.onAbility = { sounds.play(R.raw.bird_flying) }
        world.onPhaseChanged = { phase -> runOnUiThread { onPhase(phase) } }
    }

    private fun onPhase(phase: GamePhase) {
        when (phase) {
            GamePhase.PLANNING -> {
                bottomBar.visibility = View.VISIBLE
                hintText.text = getString(R.string.hint_plan)
                refreshHud()
                buildSelectors()
            }

            GamePhase.FLYING -> {
                bottomBar.visibility = View.INVISIBLE
                hintText.text = getString(
                    R.string.hint_ability,
                    gameView.world.selectedBird.ability
                )
            }

            GamePhase.VICTORY -> showResult(true)
            GamePhase.DEFEAT -> showResult(false)
        }
    }

    private fun buildSelectors() {
        val level = gameView.world.level
        birdSelector.removeAllViews()
        level.birds.forEach { bird -> birdSelector.addView(birdChip(bird)) }
        deviceSelector.removeAllViews()
        level.devices.forEach { device -> deviceSelector.addView(deviceChip(device)) }
    }

    private fun birdChip(bird: BirdType): View {
        val chip = LayoutInflater.from(this).inflate(R.layout.view_chip, birdSelector, false)
        val icon = chip.findViewById<ImageView>(R.id.chipIcon)
        val label = chip.findViewById<TextView>(R.id.chipLabel)
        icon.setImageResource(drawableId(bird.drawableName))
        label.text = bird.displayName
        chip.setBackgroundResource(
            if (gameView.world.selectedBird == bird) R.drawable.bg_chip_selected
            else R.drawable.bg_chip
        )
        chip.setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.button_click)
            gameView.selectBird(bird)
            buildSelectors()
            refreshHud()
        }
        return chip
    }

    private fun deviceChip(device: DeviceType): View {
        val chip = LayoutInflater.from(this).inflate(R.layout.view_chip, deviceSelector, false)
        val icon = chip.findViewById<ImageView>(R.id.chipIcon)
        val label = chip.findViewById<TextView>(R.id.chipLabel)
        icon.setImageResource(drawableId(device.drawableName))
        label.text = device.displayName
        chip.setBackgroundResource(
            if (gameView.world.selectedDevice == device) R.drawable.bg_chip_selected
            else R.drawable.bg_chip
        )
        chip.setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.button_click)
            gameView.selectDevice(device)
            buildSelectors()
        }
        return chip
    }

    private fun refreshHud() {
        val world = gameView.world
        deviceCount.text = "${world.devicesLeft}/${world.level.maxDevices}"

        birdTokens.removeAllViews()
        val iconId = drawableId(world.selectedBird.drawableName)
        val size = resources.displayMetrics.density * 22f
        repeat(world.birdsLeft.coerceAtMost(8)) {
            val token = ImageView(this)
            token.setImageResource(iconId)
            token.layoutParams = LinearLayout.LayoutParams(size.toInt(), size.toInt()).apply {
                marginEnd = (size * 0.12f).toInt()
            }
            birdTokens.addView(token)
        }
        btnGo.isEnabled = world.birdsLeft > 0 && world.phase == GamePhase.PLANNING
    }

    private fun showIntro(chapter: String, name: String) {
        introBanner.text = "$chapter\n$name"
        introBanner.alpha = 0f
        introBanner.scaleX = 0.8f
        introBanner.scaleY = 0.8f
        introBanner.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360)
            .setInterpolator(OvershootInterpolator()).withEndAction {
                introBanner.animate().alpha(0f).setStartDelay(900).setDuration(320).start()
            }.start()
    }

    private fun showResult(victory: Boolean) {
        val world = gameView.world
        val earned = if (victory) world.starsEarned() else 0
        if (victory && !rewarded) {
            rewarded = true
            val prefs = BirdDropApp.instance.prefs
            prefs.setStars(levelId, earned)
            if (levelId >= prefs.maxUnlockedLevel && levelId < LevelCatalog.TOTAL_LEVELS) {
                prefs.maxUnlockedLevel = levelId + 1
            }
            prefs.crystals += earned * 30
        }

        resultTitle.text = getString(if (victory) R.string.victory else R.string.failed)
        resultScore.text = getString(R.string.score_value, world.score)
        resultReward.text = getString(R.string.reward_value, earned * 30)
        resultReward.visibility = if (victory) View.VISIBLE else View.GONE
        btnPrimary.text = getString(if (victory) R.string.next else R.string.retry)
        btnPrimary.isEnabled = !victory || levelId < LevelCatalog.TOTAL_LEVELS

        stars.forEach {
            it.setColorFilter(ContextCompat.getColor(this, R.color.star_empty))
            it.scaleX = 1f
            it.scaleY = 1f
        }
        resultOverlay.visibility = View.VISIBLE
        resultPanel.alpha = 0f
        resultPanel.scaleX = 0.85f
        resultPanel.scaleY = 0.85f
        resultPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(DecelerateInterpolator()).start()

        stars.take(earned).forEachIndexed { index, star ->
            star.postDelayed({
                star.setColorFilter(ContextCompat.getColor(this, R.color.accent_gold))
                star.scaleX = 0.2f
                star.scaleY = 0.2f
                star.animate().scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(OvershootInterpolator()).start()
                BirdDropApp.instance.soundManager.play(R.raw.reward_collect)
            }, 320L + index * 220L)
        }

        BirdDropApp.instance.soundManager.play(if (victory) R.raw.victory else R.raw.defeat)
    }

    private fun restartLevel() {
        resultOverlay.visibility = View.GONE
        rewarded = false
        gameView.restartLevel()
        bottomBar.visibility = View.VISIBLE
        buildSelectors()
        refreshHud()
        hintText.text = getString(R.string.hint_plan)
    }

    private fun goToNextLevel() {
        if (levelId < LevelCatalog.TOTAL_LEVELS) {
            startActivity(
                Intent(this, GameActivity::class.java)
                    .putExtra(EXTRA_LEVEL_ID, levelId + 1)
            )
        }
        finish()
    }

    private fun showPause() {
        BirdDropApp.instance.soundManager.play(R.raw.menu_open)
        gameView.setPaused(true)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pause)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            pauseDialog = null
            gameView.setPaused(false)
        }
        pauseDialog = dialog

        dialog.findViewById<MaterialButton>(R.id.btnResume).setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.menu_close)
            dialog.dismiss()
        }
        dialog.findViewById<MaterialButton>(R.id.btnRestart).setOnClickListener {
            dialog.dismiss()
            restartLevel()
        }
        dialog.findViewById<MaterialButton>(R.id.btnQuit).setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.show()
    }

    private fun drawableId(name: String): Int =
        resources.getIdentifier(name, "drawable", packageName)

    private fun vibrate(ms: Long) {
        if (!BirdDropApp.instance.prefs.vibrationEnabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        gameView.setPaused(pauseDialog?.isShowing == true)
    }

    override fun onPause() {
        super.onPause()
        gameView.setPaused(true)
    }

    override fun onDestroy() {
        gameView.release()
        super.onDestroy()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_LEVEL_ID = "level_id"
    }
}
