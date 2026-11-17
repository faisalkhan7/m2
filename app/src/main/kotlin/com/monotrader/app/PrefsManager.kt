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

    /** Controlled refresh: the screen is frozen and only updated every N seconds */
    var freezeEnabled: Boolean
        get() = sp.getBoolean("freeze", true)
        set(v) = sp.edit().putBoolean("freeze", v).apply()

    /** Seconds between two screen updates (1..30) */
    var intervalSec: Int
        get() = sp.getInt("refresh_sec", 2)
        set(v) = sp.edit().putInt("refresh_sec", v.coerceIn(1, 30)).apply()

    /** Part of the window that is frozen, from the bottom (10..100 %) */
    var freezePct: Int
        get() = sp.getInt("freeze_pct", 100)
        set(v) = sp.edit().putInt("freeze_pct", v.coerceIn(10, 100)).apply()

    /** Seconds the screen stays live after the last touch (1..10) */
    var liveSec: Int
        get() = sp.getInt("live_sec", 3)
        set(v) = sp.edit().putInt("live_sec", v.coerceIn(1, 10)).apply()

    var homeUrl: String
        get() = sp.getString("home_url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString("home_url", v).apply()

    companion object {
        const val DEFAULT_URL = "https://web.metatrader.app/terminal"
    }
}
