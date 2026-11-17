package com.monotrader.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * MonoTrader v8 — dedicated MT5 browser.
 * - Real grayscale on this window only (TradingView in split-screen stays in color).
 * - Controlled refresh: the MT5 screen is shown as a still image that is
 *   updated only every N seconds. The page keeps running underneath and
 *   touches go straight through to it in real time; the picture is updated
 *   right after each touch so you see what you pressed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var frozenView: ImageView
    private lateinit var settingsBtn: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Two buffers: we always copy into the one that is NOT on screen
    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private var shownIsA = false

    private var capturing = false

    /** Regular screen update every N seconds */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!prefs.freezeEnabled) return
            captureAndFreeze()
            handler.postDelayed(this, prefs.intervalSec * 1000L)
        }
    }

    /** One-off screen update (after a touch, after a resize...) */
    private val refreezeRunnable = Runnable {
        if (prefs.freezeEnabled) captureAndFreeze()
    }
    private val refreezeRunnable2 = Runnable {
        if (prefs.freezeEnabled) captureAndFreeze()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webContainer = FrameLayout(this)
        webView = WebView(this)

        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            webContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // ---- frozen picture of the screen ----
        frozenView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            visibility = View.INVISIBLE
        }
        root.addView(
            frozenView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            )
        )

        // ---- settings button ----
        settingsBtn = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC222222"))
            }
            setOnClickListener { showSettings() }
        }
        root.addView(
            settingsBtn,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(4)
            }
        )

        setContentView(root)

        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                root.post {
                    updateFreezeHeight()
                    // size changed (split-screen resized): old picture is wrong
                    goLive()
                    handler.removeCallbacks(refreezeRunnable)
                    handler.postDelayed(refreezeRunnable, 800L)
                }
            }
        }

        setupWebView()
        applyMono()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(prefs.homeUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // MT5 remembers the account
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)   // pop-ups / new tabs open in the same view
            // Look like normal Chrome: many login pages refuse the "; wv" WebView signature.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false // stay inside the app
                // intent://, mailto:, market:// ... -> hand over to Android
                return try {
                    val intent = if (scheme == "intent")
                        Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectCss()
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // ---------------- grayscale ----------------

    private fun applyMono() {
        if (prefs.monoEnabled) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            webContainer.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            webContainer.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        injectCss()
    }

    private fun injectCss() {
        val css = if (prefs.monoEnabled && prefs.cssFallback)
            "html{filter:grayscale(100%) !important;-webkit-filter:grayscale(100%) !important;}"
        else ""
        val js = "(function(){var s=document.getElementById('mt-mono');" +
            "if(!s){s=document.createElement('style');s.id='mt-mono';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='" + css + "';})();"
        webView.evaluateJavascript(js, null)
    }

    // ---------------- controlled refresh ----------------

    /** Keyboard open (typing a login...) -> always show the live page */
    private fun keyboardVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(root) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    private fun updateFreezeHeight() {
        val h = root.height * prefs.freezePct / 100
        if (h <= 0) return
        val lp = frozenView.layoutParams
        if (lp.height != h) {
            lp.height = h
            frozenView.layoutParams = lp
        }
    }

    private fun goLive() {
        frozenView.visibility = View.INVISIBLE
    }

    /** Show the live page for a moment, copy it, then show the copy. */
    private fun captureAndFreeze() {
        if (capturing || !prefs.freezeEnabled) return
        if (keyboardVisible()) {
            goLive()
            return
        }
        val w = frozenView.width
        val h = frozenView.height
        if (w <= 0 || h <= 0) return
        capturing = true
        frozenView.visibility = View.INVISIBLE      // the live page is drawn for this frame
        handler.postDelayed({ copyScreen(w, h) }, 70L)
    }

    private fun copyScreen(w: Int, h: Int) {
        if (!prefs.freezeEnabled || isFinishing) {
            capturing = false
            return
        }
        // pick the buffer that is not currently displayed
        val existing: Bitmap? = if (shownIsA) bufB else bufA
        val target: Bitmap =
            if (existing != null && existing.width == w && existing.height == h) existing
            else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                if (shownIsA) bufB = it else bufA = it
            }
        val loc = IntArray(2)
        frozenView.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        val bmp = target
        try {
            PixelCopy.request(window, rect, bmp, { result ->
                capturing = false
                if (result == PixelCopy.SUCCESS && prefs.freezeEnabled && !keyboardVisible()) {
                    frozenView.setImageBitmap(bmp)
                    shownIsA = (bmp === bufA)
                    frozenView.visibility = View.VISIBLE
                }
            }, handler)
        } catch (e: Exception) {
            capturing = false
        }
    }

    private fun applyFreeze() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(refreezeRunnable)
        handler.removeCallbacks(refreezeRunnable2)
        if (prefs.freezeEnabled) {
            updateFreezeHeight()
            handler.postDelayed(refreezeRunnable, 1000L)
            handler.postDelayed(tickRunnable, prefs.intervalSec * 1000L)
        } else {
            goLive()
        }
    }

    /**
     * Touches always go straight through to the live page (real time).
     * The frozen picture is only an appearance: after you lift your finger,
     * it is updated quickly so you see the result of what you pressed.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (prefs.freezeEnabled &&
            (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            handler.removeCallbacks(refreezeRunnable)
            handler.removeCallbacks(refreezeRunnable2)
            handler.postDelayed(refreezeRunnable, 350L)    // quick update
            handler.postDelayed(refreezeRunnable2, 1200L)  // after dialogs / animations
        }
        return handled
    }

    // ---------------- settings ----------------

    private fun showSettings() {
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        box.addView(switchRow("Noir & blanc", prefs.monoEnabled) {
            prefs.monoEnabled = it
            applyMono()
        })
        box.addView(switchRow("Rafraîchissement contrôlé", prefs.freezeEnabled) {
            prefs.freezeEnabled = it
            applyFreeze()
        })

        box.addView(sliderRow(
            { v -> "Mise à jour de l'écran toutes les $v s" }, 1, 30, 1, prefs.intervalSec
        ) { prefs.intervalSec = it; applyFreeze() })

        box.addView(sliderRow(
            { v -> if (v >= 100) "Zone figée : toute la fenêtre" else "Zone figée : $v % du bas" },
            10, 100, 5, prefs.freezePct
        ) { prefs.freezePct = it; updateFreezeHeight(); goLive(); applyFreeze() })

        box.addView(switchRow("Filtre de secours (si des couleurs restent)", prefs.cssFallback) {
            prefs.cssFallback = it
            injectCss()
        })

        val urlLabel = TextView(this).apply {
            text = "Adresse du terminal"
            setPadding(0, dp(16), 0, 0)
        }
        val urlInput = EditText(this).apply {
            setText(prefs.homeUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        box.addView(urlLabel)
        box.addView(urlInput)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val reloadBtn = Button(this).apply {
            text = "Recharger"
            setOnClickListener { webView.reload() }
        }
        val homeBtn = Button(this).apply {
            text = "Ouvrir l'adresse"
            setOnClickListener {
                val u = urlInput.text.toString().trim()
                if (u.isNotEmpty()) {
                    val full = if (u.startsWith("http")) u else "https://$u"
                    prefs.homeUrl = full
                    webView.loadUrl(full)
                }
            }
        }
        btnRow.addView(reloadBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(homeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(btnRow)

        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val avaBtn = Button(this).apply {
            text = "Site AvaTrade"
            setOnClickListener {
                prefs.homeUrl = AVA_URL
                urlInput.setText(AVA_URL)
                webView.loadUrl(AVA_URL)
            }
        }
        val mt5Btn = Button(this).apply {
            text = "MT5 Web"
            setOnClickListener {
                prefs.homeUrl = PrefsManager.DEFAULT_URL
                urlInput.setText(PrefsManager.DEFAULT_URL)
                webView.loadUrl(PrefsManager.DEFAULT_URL)
            }
        }
        quickRow.addView(avaBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickRow.addView(mt5Btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(quickRow)

        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("MonoTrader")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        return SwitchCompat(this).apply {
            text = label
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }
    }

    private fun sliderRow(
        label: (Int) -> String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        onChange: (Int) -> Unit
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val tv = TextView(this).apply { text = label(current) }
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = ((current - min) / step).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    tv.text = label(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addView(tv)
        col.addView(bar)
        return col
    }

    // ---------------- lifecycle ----------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // onStart/onStop (not onResume/onPause): the terminal keeps running
    // while you are touching TradingView in split-screen.
    override fun onStart() {
        super.onStart()
        webView.onResume()
        applyFreeze()
    }

    override fun onStop() {
        webView.onPause()
        CookieManager.getInstance().flush()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(refreezeRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val AVA_URL = "https://app.avatrade.com"
    }
}
