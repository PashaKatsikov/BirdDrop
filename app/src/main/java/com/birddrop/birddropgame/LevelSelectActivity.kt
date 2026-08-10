package com.birddrop.birddropgame

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.birddrop.birddropgame.game.model.LevelCatalog

class LevelSelectActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        setContentView(R.layout.activity_level_select)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            BirdDropApp.instance.soundManager.play(R.raw.menu_close)
            finish()
        }

        grid = findViewById(R.id.levelGrid)
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = LevelAdapter()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        grid.adapter?.notifyDataSetChanged()
    }

    private inner class LevelAdapter : RecyclerView.Adapter<LevelHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LevelHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_level, parent, false)
            return LevelHolder(view)
        }

        override fun getItemCount(): Int = LevelCatalog.TOTAL_LEVELS

        override fun onBindViewHolder(holder: LevelHolder, position: Int) {
            holder.bind(position + 1)
        }
    }

    private inner class LevelHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val number: TextView = view.findViewById(R.id.levelNumber)
        private val chapter: TextView = view.findViewById(R.id.levelChapter)
        private val stars = listOf<ImageView>(
            view.findViewById(R.id.levelStar1),
            view.findViewById(R.id.levelStar2),
            view.findViewById(R.id.levelStar3)
        )

        fun bind(levelId: Int) {
            val prefs = BirdDropApp.instance.prefs
            val unlocked = levelId <= prefs.maxUnlockedLevel
            val earned = prefs.getStars(levelId)
            val definition = LevelCatalog.get(levelId)

            number.text = levelId.toString()
            chapter.text = definition.chapter
            itemView.setBackgroundResource(
                if (unlocked) R.drawable.bg_level_unlocked else R.drawable.bg_level_locked
            )
            itemView.alpha = if (unlocked) 1f else 0.65f

            stars.forEachIndexed { index, star ->
                val color = if (unlocked && index < earned) R.color.accent_gold else R.color.star_empty
                star.setColorFilter(ContextCompat.getColor(this@LevelSelectActivity, color))
            }

            itemView.setOnClickListener {
                if (!unlocked) {
                    BirdDropApp.instance.soundManager.play(R.raw.menu_close)
                    return@setOnClickListener
                }
                BirdDropApp.instance.soundManager.play(R.raw.button_click)
                startActivity(
                    Intent(this@LevelSelectActivity, GameActivity::class.java)
                        .putExtra(GameActivity.EXTRA_LEVEL_ID, levelId)
                )
            }
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
