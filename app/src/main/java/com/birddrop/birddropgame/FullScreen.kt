package com.birddrop.birddropgame

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Immersive, edge-to-edge fullscreen helper shared by both activities. */
object FullScreen {
    fun apply(activity: Activity) {
        val window = activity.window
        // Referencing decorView here also guarantees it exists before we touch the controller.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Draw into the display cutout on both edges. Without this the system
        // pillarboxes the window away from a landscape notch, which shifts the
        // whole screen sideways — visible on the loading screen as an offset.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
