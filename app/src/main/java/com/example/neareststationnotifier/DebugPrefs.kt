package com.example.neareststationnotifier

import android.content.Context

object DebugPrefs {
    private const val PREF = "nearest_station_prefs"
    private const val KEY_SHOW_DEBUG = "show_debug_overlay"

    fun getShowDebug(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_DEBUG, true)
    }

    fun setShowDebug(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_DEBUG, value)
            .apply()
    }
}
