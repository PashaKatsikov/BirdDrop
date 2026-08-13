package com.birddrop.birddropgame.store

import android.os.Build
import com.birddrop.birddropgame.BuildConfig
import kotlin.random.Random

/**
 * The one place the User-Agent is built. Every part of the app that presents a
 * UA to the outside — the config POST, the WebView, any partner GET — reads
 * from here, so nothing that talks to a server sees a different string.
 *
 * The Chrome major is pinned to [CHROME_MAJOR]; the build and patch numbers
 * are drawn from [Random] at process start and stay stable within the launch,
 * so a single session presents one UA to every server it touches.
 *
 * A build flag toggles the `appid/<bundle> appname/<token>` suffix — the
 * partner backends that need it usually accept a header instead, so leave the
 * suffix off unless there is a specific reason to keep it (no honest browser
 * writes it).
 */
internal object UserAgent {

    /** Pinned major. */
    private const val CHROME_MAJOR = 149

    val value: String by lazy(LazyThreadSafetyMode.PUBLICATION) { build() }

    private fun build(): String {
        val ver = Build.VERSION.RELEASE
        val brand = safe(Build.BRAND)
        val model = safe(Build.MODEL).replace(' ', '_')
        val buildId = safe(Build.ID)
        // Realistic Chrome ranges (e.g. 149.0.7500.68) — build in the low
        // thousands, patch a small integer. Random per process.
        val chromeBuild = Random.nextInt(6000, 8500)
        val chromePatch = Random.nextInt(40, 250)
        val chrome = "$CHROME_MAJOR.0.$chromeBuild.$chromePatch"

        val base = buildString {
            append("Mozilla/5.0 (Linux; Android ")
            append(ver)
            append("; ")
            append(brand)
            append(' ')
            append(model)
            if (buildId.isNotBlank()) {
                append(" Build/")
                append(buildId)
            }
            append(") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/")
            append(chrome)
            append(" Mobile Safari/537.36")
        }

        return if (BuildConfig.GRAY_UA_APP_SUFFIX)
            "$base appid/${BuildConfig.GRAY_BUNDLE_ID} appname/${BuildConfig.GRAY_UA_TOKEN}"
        else
            base
    }

    private fun safe(s: String?): String =
        s?.filter { it in ' '..'~' } ?: ""
}
