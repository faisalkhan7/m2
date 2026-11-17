package com.monotrader.app

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("monotrader_v7", Context.MODE_PRIVATE)

    /** Native grayscale on the MT5 window */
    var monoEnabled: Boolean
        get() = sp.getBoolean("mono", true)
        set(v) = sp.edit().putBoolean("mono", v).apply()

    /** Extra CSS grayscale inside the page (fallback) */
    var cssFallback: Boolean
        get() = sp.getBoolean("css_fallback", false)
        set(v) = sp.edit().putBoolean("css_fallback", v).apply()

    var curtainEnabled: Boolean
        get() = sp.getBoolean("curtain", true)
        set(v) = sp.edit().putBoolean("curtain", v).apply()

    /** Seconds between two P&L peeks (1..10) */
    var intervalSec: Int
        get() = sp.getInt("interval_sec", 3)
        set(v) = sp.edit().putInt("interval_sec", v.coerceIn(1, 10)).apply()

    /** How long the P&L stays visible during a peek (100..1000 ms) */
    var peekMs: Int
        get() = sp.getInt("peek_ms", 400)
        set(v) = sp.edit().putInt("peek_ms", v.coerceIn(100, 1000)).apply()

    /** Curtain height in percent of the window (10..50) */
    var curtainPct: Int
        get() = sp.getInt("curtain_pct", 22)
        set(v) = sp.edit().putInt("curtain_pct", v.coerceIn(10, 50)).apply()

    var homeUrl: String
        get() = sp.getString("home_url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString("home_url", v).apply()

    companion object {
        const val DEFAULT_URL = "https://web.metatrader.app/terminal"
    }
}
