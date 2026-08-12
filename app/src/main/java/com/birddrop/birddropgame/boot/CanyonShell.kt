package com.birddrop.birddropgame.boot

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.birddrop.birddropgame.BuildConfig
import com.birddrop.birddropgame.FullScreen
import com.birddrop.birddropgame.relay.Env
import com.birddrop.birddropgame.store.Trace
import com.birddrop.birddropgame.store.UserAgent
import com.birddrop.birddropgame.netmon.PushBusCanyon
import com.birddrop.birddropgame.env.PrefStore
import com.birddrop.birddropgame.attr.LinkMon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell.
 *
 *  - Black background everywhere (no Android system flash on load / page exit).
 *  - Safe-area paddings: top inset in portrait, left+right insets in landscape
 *    (handles notch / cutout cameras).
 *  - Instant GorgeOffline navigation on connectivity loss — no DNS probe.
 *  - Keyboard handled by [CanyonPan] (the view slides, it never resizes) plus the
 *    safe-area CSS kill injection.
 *  - A loading cover over redirect hops and failed loads, so the user only ever sees
 *    a finished page — never an intermediate hop or the WebView's own error page.
 *  - Cold + warm push URL routing through Intent extras / onNewIntent.
 *  - User-Agent ends with "appid/<bundleId> appname/<AppName>".
 */
class CanyonShell : AppCompatActivity() {

    private lateinit var wv: WebView
    private lateinit var container: FrameLayout
    private lateinit var vault: PrefStore
    private lateinit var wire: LinkMon
    private lateinit var keyboard: CanyonPan
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Last main-frame URL that actually settled. What a renderer recovery reloads. */
    private var lastMainFrameUrl: String? = null

    /**
     * Deepest main-frame URL seen, settled or not. A redirect loop is resumed
     * from here rather than from the last settled page — restarting the chain
     * from its entry point only walks into the same loop again (pitfalls #30).
     */
    private var deepestHop: String? = null

    private var redirectRetries = 0
    /** One fallback to the configured entry point per settled page. */
    private var entryPointRetried = false
    private var rendererRecoveries = 0

    /** A failed load still reaches onPageFinished; without this it resets the budget. */
    private var loadFailed = false
    /** True once one page of this session has rendered — gates the cover. */
    private var firstPageSettled = false
    /** Keeps the cover raised across the reload a retry queues up. */
    private var retryPending = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fc = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        fc.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: arrayOf()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = PrefStore(applicationContext)
        wire  = LinkMon(applicationContext)
        PushBusCanyon.shellAlive = true

        // Background stays black at all times — windowBackground in the theme is black,
        // and we keep the root view black too.
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }
        setContentView(container)
        applyInsets()

        keyboard = CanyonPan(window.decorView, vault)
        keyboard.install()
        recreateWebView()

        hideSystemUi()
        enableNotchCutout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack()
            }
        })

        // Choose the initial URL: warm push > intent extra > saved.
        val warmPush = intent.takeIf { it.getBooleanExtra(EXTRA_PUSH_WARM, false) }
            ?.getStringExtra(EXTRA_PUSH_URL)
        val coldPush = vault.consumeColdPushUrl()
        val initial  = warmPush
            ?: coldPush
            ?: intent.getStringExtra(EXTRA_STREAM_URL)
            ?: vault.destinationUrl

        if (initial.isNullOrBlank()) {
            Trace.w(TAG, "No URL to load — finishing")
            finish(); return
        }
        Trace.i(TAG, "loading initial URL (warm=${warmPush != null}, cold=${coldPush != null})")
        wv.loadUrl(initial)

        // Connectivity monitoring — react instantly on OS callback.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (!online) {
                    Trace.i(TAG, "Connectivity lost (callback) → GorgeOffline")
                    goOffline()
                }
            }
        }

        // Heartbeat — covers the case where the page is already loaded and the user
        // turns off the internet: no WebView request fails, so we actively probe.
        scope.launch {
            while (true) {
                delay(Env.heartbeatMs)
                if (navigatedOffline) continue
                if (!wire.isConnected()) {
                    Trace.i(TAG, "Heartbeat: no network → GorgeOffline")
                    goOffline()
                }
            }
        }

        scope.launch {
            delay(Env.safeAreaDelayMs)
            injectSafeAreaKill()
        }
    }

    /** Builds the WebView, puts it in the container and hooks everything to it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun recreateWebView() {
        wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                userAgentString = buildUserAgent()
                // Performance + compatibility.
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = true
                blockNetworkImage = false
                // Popups stay in this view. Asking for real second windows is what
                // makes the WebView demand a host for them and throw when it cannot
                // get one ("Parent WebView cannot host its own popup window").
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
            }
            setBackgroundColor(Color.BLACK)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        container.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient   = buildClient()
        wv.webChromeClient = buildChromeClient()
        keyboard.bind(wv)
    }

    // ── Loading cover ───────────────────────────────────────────────────

    private var cover: View? = null
    private var coverJob: Job? = null
    private val snapHandler = Handler(Looper.getMainLooper())

    /**
     * The last page the user actually saw, captured while it was fully on screen.
     * Shown under the loading cover during the next navigation so the user stays
     * on the previous page until the new one has finished loading.
     */
    private var lastShownBitmap: Bitmap? = null

    /**
     * Covers the WebView while a navigation resolves, so the user never sees a
     * half-drawn or blank intermediate page — a tapped link keeps the previous
     * page on screen until the next one has fully loaded. It also spans every
     * hop of an affiliate redirect chain: the cover is raised the moment a new
     * page starts and only dropped once a page has fully finished, and a short
     * linger ([COVER_LINGER_MS]) keeps it up across the gap between one hop
     * finishing and the next starting.
     *
     * [shot] is [lastShownBitmap] — a pixel-accurate snapshot of the page being
     * left (see [refreshSnapshot]). The bitmap is owned by [lastShownBitmap],
     * not by the cover, so it is never recycled here. When no snapshot exists
     * yet (first page of the session) the cover falls back to an opaque backdrop.
     */
    private fun raiseCover(shot: Bitmap? = null) {
        coverJob?.cancel()
        coverJob = null
        val existing = cover
        if (existing != null) {
            // Mid-chain: keep the cover (and the previous-page snapshot) already up.
            existing.animate().cancel()
            existing.alpha = 1f
            return
        }
        val fresh = FrameLayout(this).apply {
            setBackgroundColor(COVER_BACKDROP)
            isClickable = true
            // Just the frozen snapshot of the page being left — no spinner or any
            // other loading indicator, by request. The new page loads behind it.
            if (shot != null && !shot.isRecycled) {
                addView(
                    ImageView(this@CanyonShell).apply {
                        setImageBitmap(shot)
                        scaleType = ImageView.ScaleType.FIT_XY
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
        cover = fresh
        container.addView(
            fresh,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // A page that never reports back must not hold the screen for good.
        scope.launch {
            delay(COVER_MAX_MS)
            if (cover === fresh) {
                Trace.w(TAG, "Loading cover timed out")
                dropCover(0L)
            }
        }
    }

    /**
     * Grabs the currently visible page into [lastShownBitmap] via [PixelCopy],
     * which reads the real rendered surface (hardware-accelerated content and
     * all) — unlike `WebView.draw` into a software canvas, which comes back
     * solid black. Only runs while the cover is down, so it captures the page
     * and never the cover on top of it.
     */
    private fun refreshSnapshot() {
        if (cover != null || isFinishing || isDestroyed) return
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) return
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        try {
            PixelCopy.request(window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS && cover == null) {
                    lastShownBitmap?.recycle()
                    lastShownBitmap = bmp
                } else {
                    bmp.recycle()
                }
            }, snapHandler)
        } catch (_: Throwable) {
            bmp.recycle()
        }
    }

    /**
     * @param after grace before the page is handed back. A redirect hop finishes and
     *   starts the next load within a frame or two, and this is what keeps the cover
     *   from blinking off and on between them.
     */
    private fun dropCover(after: Long = COVER_LINGER_MS) {
        val current = cover ?: return
        coverJob?.cancel()
        coverJob = scope.launch {
            delay(after)
            if (cover !== current) return@launch
            cover = null
            current.animate().alpha(0f).setDuration(150L).withEndAction {
                container.removeView(current)
            }.start()
        }
    }

    // ── WebView clients ────────────────────────────────────────────────

    private var pageStartMs = 0L

    private fun buildClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            val scheme = u.substringBefore(':').lowercase()
            return when {
                scheme in WEB_SCHEMES -> {
                    if (req.isForMainFrame) deepestHop = u
                    false  // load inside this WebView
                }
                scheme == "intent" -> { openIntentUri(u); true }
                // Everything else is an app link: banks, wallets, messengers, stores.
                // Handing it to the WebView would only produce ERR_UNKNOWN_URL_SCHEME,
                // and the list of schemes worth knowing about has no end.
                else -> { openExternally(u); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            pageStartMs = System.currentTimeMillis()
            loadFailed = false
            retryPending = false
            keyboard.forget()
            // shouldOverrideUrlLoading does not see every server-side 30x, so the
            // URL the engine actually committed to is the other half of the trail.
            if (url != BLANK) deepestHop = url
            // Every navigation is covered until it fully finishes, so the user
            // never sees an unloaded or half-drawn page. Mid-chain the cover is
            // already up (raiseCover keeps it); at the start of a fresh
            // navigation we grab the page being left so the cover shows it
            // rather than a bare loading screen.
            if (url != BLANK) raiseCover(if (cover == null) lastShownBitmap else null)
            Trace.i(TAG, "onPageStarted")
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            loadFailed = true
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.description.toString() else ""
            Trace.w(TAG, "main-frame error $code on ${req.url.host}")

            // A custom scheme reaching this point was already handed to the system;
            // the page behind it is still fine. Debounced (not instant) so a hop
            // that fires one mid-chain does not flash the intermediate page.
            if (code == ERROR_UNSUPPORTED_SCHEME) {
                dropCover()
                return
            }

            val isLoop = code == -9 || code == -1007 ||
                    desc.contains("too_many", ignoreCase = true)
            if (isLoop) {
                handleRedirectLoop(view, req.url.toString())
                return
            }

            val isNetErr = code in setOf(-2, -6, -7, -8, -11)
            if (isNetErr || !wire.isConnected()) {
                view.stopLoading()
                view.loadUrl(BLANK)
                goOffline()
                return
            }

            // Anything else: the page is what it is. Debounced so a transient
            // mid-chain failure does not surface the broken intermediate; if the
            // chain moves on, the next onPageStarted keeps the cover up.
            dropCover()
        }

        override fun onPageFinished(view: WebView, url: String) {
            Trace.i(TAG, "onPageFinished")
            if (loadFailed || url == BLANK) return
            redirectRetries = 0
            entryPointRetried = false
            retryPending = false
            firstPageSettled = true
            lastMainFrameUrl = url
            deepestHop = url
            injectSafeAreaKill()
            view.evaluateJavascript(keyboard.script, null)
            dropCover()
            // Once the cover is off and this page is actually on screen, capture
            // it so the next navigation can keep the user on it while it loads.
            scope.launch {
                delay(COVER_LINGER_MS + 250L)
                refreshSnapshot()
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail
        ): Boolean {
            Trace.w(TAG, "render process gone, crashed=${detail.didCrash()}")
            if (isFinishing || view !== wv) {
                runCatching { view.destroy() }
                return true
            }
            if (rendererRecoveries >= MAX_RENDERER_RECOVERIES) {
                Trace.w(TAG, "renderer recovery budget exhausted → GorgeOffline")
                goOffline()
                return true
            }
            rendererRecoveries++
            replaceWebView()
            return true
        }
    }

    /**
     * ERR_TOO_MANY_REDIRECTS. Chromium gives up after 20 hops and affiliate
     * chains are routinely longer, so this is an ordinary condition rather than
     * a failure — the chain has to be resumed, not restarted.
     *
     * Three things this gets right that the obvious version does not:
     *
     *  - It resumes from [deepestHop]. Reloading the entry point walks the same
     *    hops again and burns the budget on the identical loop. `lastMainFrameUrl`
     *    is the wrong field for this: `onPageFinished` overwrites it with the
     *    page that settled, so by error time it names the chain's start.
     *  - It posts the reload instead of calling `loadUrl` from inside the
     *    callback. The engine is still unwinding the failed navigation at that
     *    point and swallows or defers a re-entrant load — which is where the
     *    multi-second stalls between attempts came from.
     *  - When the budget is gone it does not leave the user under an overlay
     *    until the cover's own timeout. ERR_TOO_MANY_REDIRECTS is not in the
     *    network-error set, so before this the exhausted path did nothing at all.
     *
     * Nothing here raises the cover. A loop in an affiliate chain is dead time
     * mid-navigation, not a state worth putting a screen in front of the user
     * for — the retry is queued within 60 ms and the page underneath is
     * replaced before it has drawn.
     */
    private fun handleRedirectLoop(view: WebView, failedUrl: String) {
        if (redirectRetries < Env.redirectRetryMax) {
            redirectRetries++
            retryPending = true
            val resumeAt = deepestHop ?: failedUrl
            Trace.i(TAG, "redirect loop, resuming attempt $redirectRetries")
            postLoad(view, resumeAt)
            return
        }

        // Budget spent. The chain itself is stuck; the entry point the backend
        // named usually still resolves, and cookies picked up along the way are
        // often what the chain was missing.
        val entryPoint = vault.destinationUrl
        if (!entryPointRetried && !entryPoint.isNullOrBlank() && entryPoint != deepestHop) {
            entryPointRetried = true
            retryPending = true
            Trace.w(TAG, "redirect budget spent → retrying the configured entry point")
            postLoad(view, entryPoint)
            return
        }

        Trace.w(TAG, "redirect chain unresolvable — handing the page back")
        retryPending = false
        dropCover()
    }

    /**
     * A load queued out of a WebViewClient callback. The short pause is dead
     * time in the middle of a navigation, not a delay the user can feel.
     */
    private fun postLoad(view: WebView, url: String) {
        view.postDelayed({
            if (!isFinishing && !isDestroyed) view.loadUrl(url)
        }, RETRY_PAUSE_MS)
    }

    /**
     * Builds a fresh WebView after a renderer death and puts the last good page back.
     * The dead one cannot be reused for anything, including being asked what it was
     * showing, so [lastMainFrameUrl] is what there is to go on.
     */
    private fun replaceWebView() {
        val resumeAt = lastMainFrameUrl ?: vault.destinationUrl ?: return
        val dead = wv
        container.removeView(dead)
        runCatching { dead.destroy() }
        recreateWebView()
        wv.loadUrl(resumeAt)
    }

    private fun buildChromeClient() = object : WebChromeClient() {

        // Deliberately no onProgressChanged→dropCover: a redirect hop reaching
        // 100% is not the final page, and revealing it there is exactly how the
        // user ended up looking at an unfinished intermediate. The cover comes
        // off only from onPageFinished, debounced so the chain stays covered.

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(arrayOf())
            fileCallback = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (_: Exception) {
                fileCallback = null
                false
            }
        }
    }

    // ── Links the WebView cannot take ───────────────────────────────────

    private fun openExternally(url: String) {
        val intent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
        launchOrIgnore(intent)
    }

    /**
     * intent:// URIs name a target app and usually carry a browser_fallback_url, so
     * there are three things to try before the user is left looking at nothing.
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (launchOrIgnore(parsed)) return
        // The named app may be missing while some other app handles the scheme.
        parsed.`package` = null
        if (launchOrIgnore(parsed)) return
        if (!fallback.isNullOrBlank()) wv.loadUrl(fallback)
    }

    private fun launchOrIgnore(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    // ── Navigation ──────────────────────────────────────────────────────

    @Volatile private var navigatedOffline = false

    private fun goOffline() {
        if (navigatedOffline) return
        navigatedOffline = true
        val cur = lastMainFrameUrl ?: wv.url
        try { wv.stopLoading(); wv.loadUrl(BLANK) } catch (_: Exception) {}
        startActivity(Intent(this, GorgeOffline::class.java).apply {
            if (!cur.isNullOrBlank()) putExtra(GorgeOffline.EXTRA_RETURN_URL, cur)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigatedOffline = false

        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val url = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!url.isNullOrBlank() && com.birddrop.birddropgame.store.UrlGuard.accepts(url)) {
                Trace.i(TAG, "warm push → loading")
                wv.loadUrl(url)
                return
            }
        }

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val current = wv.url
        val target = streamUrl ?: vault.destinationUrl
        if (!target.isNullOrBlank() &&
            (current.isNullOrBlank() || current == BLANK || current != target)) {
            Trace.i(TAG, "onNewIntent → reloading target")
            wv.loadUrl(target)
        }
    }

    // ── Insets / safe area ──────────────────────────────────────────────

    /**
     * Apply orientation-aware padding so the WebView never sits under the camera
     * notch / cutout.
     *   portrait  → top inset only
     *   landscape → left + right insets (cutout on either side)
     */
    private fun applyInsets() {
        container.setOnApplyWindowInsetsListener { v, insets ->
            val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            // Pad ONLY for the display cutout (notch), never for the system bars.
            // In landscape the navigation bar appears and disappears when the
            // keyboard opens; if its inset drove the padding it would resize the
            // WebView and jerk it. The shell is immersive, so the bars overlay
            // the content and never need to inset it — the cutout is the one
            // thing that genuinely must be avoided.
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                insets.displayCutout else null
            val topPad   = if (!isLandscape) (cutout?.safeInsetTop   ?: 0) else 0
            val leftPad  = if (isLandscape)  (cutout?.safeInsetLeft  ?: 0) else 0
            val rightPad = if (isLandscape)  (cutout?.safeInsetRight ?: 0) else 0
            v.setPadding(leftPad, topPad, rightPad, 0)
            insets
        }
        container.requestApplyInsets()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        container.requestApplyInsets()
        keyboard.remeasure()
        FullScreen.apply(this)
    }

    private fun hideSystemUi() = FullScreen.apply(this)

    private fun enableNotchCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // ── JS injections ───────────────────────────────────────────────────

    /**
     * Safe-area CSS kill. The window already pads for the cutout, so a page that
     * also honours `env(safe-area-inset-*)` would leave a second empty band on
     * top of ours. Zeroing the variables removes that band.
     *
     * What it must not do is lay a finger on the page's own box model. An
     * earlier version zeroed `padding-left`, `padding-right` and `margin` on
     * `html, body, #__nuxt, #app, #root` — but sites build their gutters with
     * exactly those declarations, so the whole layout got squeezed flat against
     * both edges (pitfalls #10). Only `padding-top`, and only on the chrome
     * wrappers that are known to add a status-bar offset of their own.
     */
    private fun injectSafeAreaKill() {
        val sentinel = BuildConfig.JS_SAFE_AREA_SENTINEL
        val running  = sentinel + "R"
        wv.evaluateJavascript("""
            (function(){
              if(window.$running) return; window.$running = true;
              var CSS_ID = '$sentinel';
              var CSS_TEXT =
                ':root{' +
                  '--safe-area-inset-top:0px!important;' +
                  '--safe-area-inset-right:0px!important;' +
                  '--safe-area-inset-bottom:0px!important;' +
                  '--safe-area-inset-left:0px!important;' +
                  '--sat:0px!important;--sar:0px!important;' +
                  '--sab:0px!important;--sal:0px!important;' +
                  '--safe-top:0px!important;--safe-right:0px!important;' +
                  '--safe-bottom:0px!important;--safe-left:0px!important;' +
                '}' +
                '.gameview-mobile-header,.app-header{' +
                  'padding-top:0!important;' +
                '}';
              function apply(){
                var head = document.head || document.documentElement;
                if (!head) return;
                var m = document.querySelector('meta[name="viewport"]');
                if (m && !/viewport-fit\s*=\s*contain/i.test(m.getAttribute('content') || '')) {
                  var c = (m.getAttribute('content') || '')
                    .replace(/,?\s*viewport-fit\s*=\s*\w+/ig, '').trim();
                  m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
                }
                var s = document.getElementById(CSS_ID);
                if (!s) {
                  s = document.createElement('style');
                  s.id = CSS_ID;
                  head.appendChild(s);
                }
                if (s.textContent !== CSS_TEXT) s.textContent = CSS_TEXT;
                if (head.lastElementChild !== s) head.appendChild(s);
              }
              apply();
              ['pushState','replaceState'].forEach(function(fn){
                var orig = history[fn];
                history[fn] = function(){
                  var r = orig.apply(this, arguments);
                  setTimeout(apply, 80);
                  setTimeout(apply, 400);
                  return r;
                };
              });
              window.addEventListener('popstate', function(){ setTimeout(apply, 80); });
              setInterval(apply, 2500);
            })();
        """.trimIndent(), null)
    }

    // ── User agent ──────────────────────────────────────────────────────

    private fun buildUserAgent(): String = UserAgent.value

    override fun onStart() {
        super.onStart()
        navigatedOffline = false
        PushBusCanyon.onWarmUrl = { url ->
            runOnUiThread {
                Trace.i(TAG, "PushBusCanyon warm URL → loading")
                try { wv.loadUrl(url) } catch (_: Exception) {}
            }
        }
        PushBusCanyon.consume()?.let { url ->
            Trace.i(TAG, "queued push URL → loading")
            runCatching { wv.loadUrl(url) }
        }
    }

    override fun onStop() {
        if (PushBusCanyon.onWarmUrl != null) PushBusCanyon.onWarmUrl = null
        super.onStop()
    }

    override fun onDestroy() {
        if (PushBusCanyon.onWarmUrl != null) PushBusCanyon.onWarmUrl = null
        PushBusCanyon.shellAlive = false
        scope.cancel()
        snapHandler.removeCallbacksAndMessages(null)
        lastShownBitmap?.recycle()
        lastShownBitmap = null
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PUSH_URL   = "push_url"
        const val EXTRA_PUSH_WARM  = "push_warm"
        private const val TAG = "CanyonShell"

        /** Everything the WebView itself can take. Anything else belongs to an app. */
        private val WEB_SCHEMES =
            setOf("http", "https", "about", "data", "blob", "file", "javascript")

        private const val BLANK = "about:blank"

        /** After a page finishes, how long to wait for another navigation before
         *  revealing it. Bridges the gap between one redirect hop finishing and
         *  the next starting, so the whole chain resolves under one continuous
         *  cover and only the settled final page is ever shown. */
        private const val COVER_LINGER_MS = 600L

        /** No page may hold the screen longer than this, finished or not. */
        private const val COVER_MAX_MS = 20_000L

        /** Renderer recoveries per Activity — beyond this we go offline. */
        private const val MAX_RENDERER_RECOVERIES = 3

        /** Opaque backdrop behind the loading cover, shown when no snapshot of
         *  the previous page is available. Matches the shell's black theme. */
        private const val COVER_BACKDROP = 0xFF000000.toInt()

        /** Pause before a queued redirect-loop retry. Long enough to let the
         *  engine finish unwinding the failed navigation, short enough to be
         *  invisible. */
        private const val RETRY_PAUSE_MS = 60L
    }
}
