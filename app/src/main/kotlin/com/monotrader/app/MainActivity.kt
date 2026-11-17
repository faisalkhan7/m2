package com.monotrader.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * MonoTrader v7 — dedicated MT5 browser.
 * The MT5 web terminal is drawn by this app, so a real grayscale filter
 * can be applied to this window only (TradingView in split-screen stays in color).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var curtain: TextView
    private lateinit var settingsBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var revealUntil = 0L

    /** Periodic peek: hide the curtain briefly every N seconds */
    private val peekRunnable = object : Runnable {
        override fun run() {
            if (!prefs.curtainEnabled) return
            if (SystemClock.uptimeMillis() >= revealUntil) {
                curtain.visibility = View.INVISIBLE
                handler.postDelayed(coverRunnable, prefs.peekMs.toLong())
            }
            handler.postDelayed(this, prefs.intervalSec * 1000L)
        }
    }

    /** Put the curtain back */
    private val coverRunnable = Runnable {
        if (prefs.curtainEnabled && SystemClock.uptimeMillis() >= revealUntil) {
            curtain.visibility = View.VISIBLE
        }
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

        // ---- P&L curtain ----
        curtain = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            setTextColor(Color.parseColor("#555555"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            text = "P&L masqué · touche pour voir 5 s"
            isClickable = true
            setOnClickListener { revealFor(5000L) }
        }
        root.addView(
            curtain,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.BOTTOM)
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
            if (bottom - top != oldBottom - oldTop) root.post { updateCurtainHeight() }
        }

        setupWebView()
        applyMono()
        applyCurtain()

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
            // Look like normal Chrome: many login pages (AvaTrade, captcha)
            // refuse the "; wv" WebView signature.
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

    // ---------------- P&L curtain ----------------

    private fun updateCurtainHeight() {
        val h = root.height * prefs.curtainPct / 100
        if (h <= 0) return
        val lp = curtain.layoutParams
        if (lp.height != h) {
            lp.height = h
            curtain.layoutParams = lp
        }
    }

    private fun applyCurtain() {
        handler.removeCallbacks(peekRunnable)
        handler.removeCallbacks(coverRunnable)
        if (prefs.curtainEnabled) {
            curtain.visibility = View.VISIBLE
            updateCurtainHeight()
            handler.postDelayed(peekRunnable, prefs.intervalSec * 1000L)
        } else {
            curtain.visibility = View.GONE
        }
    }

    private fun revealFor(ms: Long) {
        revealUntil = SystemClock.uptimeMillis() + ms
        curtain.visibility = View.INVISIBLE
        handler.postDelayed(coverRunnable, ms + 20L)
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
        box.addView(switchRow("Rideau P&L", prefs.curtainEnabled) {
            prefs.curtainEnabled = it
            applyCurtain()
        })

        box.addView(sliderRow(
            { v -> "Aperçu du P&L toutes les $v s" }, 1, 10, 1, prefs.intervalSec
        ) { prefs.intervalSec = it; applyCurtain() })

        box.addView(sliderRow(
            { v -> "Durée de l'aperçu : $v ms" }, 100, 1000, 50, prefs.peekMs
        ) { prefs.peekMs = it })

        box.addView(sliderRow(
            { v -> "Hauteur du rideau : $v %" }, 10, 50, 1, prefs.curtainPct
        ) { prefs.curtainPct = it; updateCurtainHeight() })

        box.addView(switchRow("Filtre de secours (si des couleurs restent)", prefs.cssFallback) {
            prefs.cssFallback = it
            injectCss()
        })

        val urlLabel = TextView(this).apply {
            text = "Adresse du terminal MT5"
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
        applyCurtain()
    }

    override fun onStop() {
        webView.onPause()
        CookieManager.getInstance().flush()
        handler.removeCallbacks(peekRunnable)
        handler.removeCallbacks(coverRunnable)
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
