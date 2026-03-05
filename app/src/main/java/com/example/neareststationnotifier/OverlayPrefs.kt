package com.example.neareststationnotifier

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max

class OverlayPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveDotRatio(x: Int, y: Int, screenW: Int, screenH: Int, viewW: Int, viewH: Int) {
        val maxX = max(screenW - viewW, 0)
        val maxY = max(screenH - viewH, 0)

        val xr = if (maxX == 0) 0f else x.toFloat() / maxX.toFloat()
        val yr = if (maxY == 0) 0f else y.toFloat() / maxY.toFloat()

        prefs.edit()
            .putFloat(KEY_DOT_XR, xr.coerceIn(0f, 1f))
            .putFloat(KEY_DOT_YR, yr.coerceIn(0f, 1f))
            .apply()
    }

    fun loadDotRatio(): Pair<Float, Float>? {
        val xr = prefs.getFloat(KEY_DOT_XR, -1f)
        val yr = prefs.getFloat(KEY_DOT_YR, -1f)
        return if (xr >= 0f && yr >= 0f) xr to yr else null
    }

    companion object {
        private const val PREFS_NAME = "overlay_prefs"
        private const val KEY_DOT_XR = "dot_x_ratio"
        private const val KEY_DOT_YR = "dot_y_ratio"
    }
}
