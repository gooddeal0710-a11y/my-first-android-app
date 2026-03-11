package com.example.neareststationnotifier

import android.content.Context
import android.graphics.PixelFormat
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayUiController(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val overlayPrefs = OverlayPrefs(context)

    private var dotView: View? = null
    private var txtDot: TextView? = null
    private var panelText: TextView? = null

    private lateinit var dotParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var lastScreenW = 0
    private var lastScreenH = 0

    fun attach() {
        val inflater = LayoutInflater.from(context)
        updateScreenSizeCache()

        dotView = inflater.inflate(R.layout.overlay_dot, null)
        txtDot = dotView?.findViewById(R.id.txtDot)

        dotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            val fallback = dp(44)
            val w = dotView?.width?.takeIf { it > 0 } ?: fallback
            val h = dotView?.height?.takeIf { it > 0 } ?: fallback

            val ratio = overlayPrefs.loadDotRatio()
            if (ratio != null) {
                val (xr, yr) = ratio
                val maxX = max(lastScreenW - w, 0)
                val maxY = max(lastScreenH - h, 0)
                x = (xr * maxX).toInt()
                y = (yr * maxY).toInt()
            } else {
                x = dp(24)
                y = dp(400)
            }
        }

        if (dotView?.parent == null) windowManager.addView(dotView, dotParams)
        dotView?.post { clampDotInsideScreen() }

        panelText = TextView(context).apply {
            text = "loading..."
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 14
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START
            includeFontPadding = false
            background = ContextCompat.getDrawable(context, R.drawable.bg_overlay_panel)
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        if (panelText?.parent == null) windowManager.addView(panelText, panelParams)

        panelText?.apply {
            alpha = 0f
            translationX = -dp(360).toFloat()
            visibility = View.GONE
        }

        dotView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (screenSizeChanged()) {
                updateScreenSizeCache()
                restoreDotPositionFromRatioOrDefault()
                clampDotInsideScreen()
                panelText?.maxWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            }
        }

        txtDot?.setOnTouchListener(TapDragToggleTouchListener())
    }

    fun detach() {
        try { saveDotPositionAsRatio() } catch (_: Exception) {}

        try { if (dotView?.parent != null) windowManager.removeView(dotView) } catch (_: Exception) {}
        try { if (panelText?.parent != null) windowManager.removeView(panelText) } catch (_: Exception) {}

        dotView = null
        panelText = null
        txtDot = null
    }

    fun isPanelShowing(): Boolean =
        panelText?.visibility == View.VISIBLE && (panelText?.alpha ?: 0f) > 0f

    fun setPanelText(text: String) {
        panelText?.text = text
    }

    fun togglePanel() {
        if (isPanelShowing()) animatePanelOut() else animatePanelIn()
    }

    private fun animatePanelIn() {
        val v = panelText ?: return
        if (v.visibility == View.VISIBLE && v.alpha >= 1f) return

        v.visibility = View.VISIBLE
        v.animate().cancel()
        v.translationX = -dp(360).toFloat()
        v.alpha = 0f

        v.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220L)
            .start()
    }

    private fun animatePanelOut() {
        val v = panelText ?: return
        if (v.visibility != View.VISIBLE) return

        v.animate().cancel()
        v.animate()
            .translationX(-dp(360).toFloat())
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { v.visibility = View.GONE }
            .start()
    }

    private inner class TapDragToggleTouchListener : View.OnTouchListener {

        private var dragging = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var downRawX = 0f
        private var downRawY = 0f

        private var downViewScreenX = 0
        private var downViewScreenY = 0

        private var downParamX = 0
        private var downParamY = 0

        private val tmpLoc = IntArray(2)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    downRawX = event.rawX
                    downRawY = event.rawY

                    v.getLocationOnScreen(tmpLoc)
                    downViewScreenX = tmpLoc[0]
                    downViewScreenY = tmpLoc[1]

                    downParamX = dotParams.x
                    downParamY = dotParams.y
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }

                    if (dragging) {
                        val newViewScreenX = downViewScreenX + dx
                        val newViewScreenY = downViewScreenY + dy

                        dotParams.x = (downParamX + (newViewScreenX - downViewScreenX)).toInt()
                        dotParams.y = (downParamY + (newViewScreenY - downViewScreenY)).toInt()

                        windowManager.updateViewLayout(dotView, dotParams)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        clampDotInsideScreen()
                        saveDotPositionAsRatio()
                        return true
                    }
                    togglePanel()
                    return true
                }
            }
            return false
        }
    }

    private fun updateScreenSizeCache() {
        val m = context.resources.displayMetrics
        lastScreenW = m.widthPixels
        lastScreenH = m.heightPixels
    }

    private fun screenSizeChanged(): Boolean {
        val m = context.resources.displayMetrics
        return (m.widthPixels != lastScreenW || m.heightPixels != lastScreenH)
    }

    private fun restoreDotPositionFromRatioOrDefault() {
        val m = context.resources.displayMetrics
        val screenW = m.widthPixels
        val screenH = m.heightPixels

        val fallback = dp(44)
        val w = dotView?.width?.takeIf { it > 0 } ?: fallback
        val h = dotView?.height?.takeIf { it > 0 } ?: fallback

        val ratio = overlayPrefs.loadDotRatio()
        if (ratio != null) {
            val (xr, yr) = ratio
            val maxX = max(screenW - w, 0)
            val maxY = max(screenH - h, 0)
            dotParams.x = (xr * maxX).toInt()
            dotParams.y = (yr * maxY).toInt()
            windowManager.updateViewLayout(dotView, dotParams)
        } else {
            dotParams.x = dp(24)
            dotParams.y = dp(400)
            windowManager.updateViewLayout(dotView, dotParams)
        }
    }

    private fun saveDotPositionAsRatio() {
        val m = context.resources.displayMetrics
        val screenW = m.widthPixels
        val screenH = m.heightPixels

        val fallback = dp(44)
        val w = dotView?.width?.takeIf { it > 0 } ?: fallback
        val h = dotView?.height?.takeIf { it > 0 } ?: fallback

        overlayPrefs.saveDotRatio(
            x = dotParams.x,
            y = dotParams.y,
            screenW = screenW,
            screenH = screenH,
            viewW = w,
            viewH = h
        )
    }

    private fun clampDotInsideScreen() {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val fallback = dp(44)
        val w = dotView?.width?.takeIf { it > 0 } ?: fallback
        val h = dotView?.height?.takeIf { it > 0 } ?: fallback

        dotParams.x = min(max(dotParams.x, 0), max(screenW - w, 0))
        dotParams.y = min(max(dotParams.y, 0), max(screenH - h, 0))

        windowManager.updateViewLayout(dotView, dotParams)
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
