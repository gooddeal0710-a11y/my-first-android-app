package com.example.neareststationnotifier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager

    private var dotView: View? = null
    private var txtDot: TextView? = null
    private lateinit var dotParams: WindowManager.LayoutParams

    private var panelText: TextView? = null
    private lateinit var panelParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val intervalMs = 5000L
    private val locationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private var updateCount = 0
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    @Volatile private var lastStationsText: String = "--"
    @Volatile private var lastApiStatus: String = "api:idle"
    @Volatile private var lastStationUpdatedAtMs: Long = 0L
    private val stationUpdateIntervalMs = 10_000L

    @Volatile private var lastDisplayText: String = "loading..."

    private val stationApi by lazy { StationApi() }
    private val overlayPrefs by lazy { OverlayPrefs(this) }

    private var lastScreenW = 0
    private var lastScreenH = 0

    // ★追加：次駅推測用
    private val predictor = NextStationPredictor()
    private var predictorState = NextStationPredictor.State()
    private var prevFix: Pair<Double, Double>? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation
            updateCount += 1
            val nowStr = timeFmt.format(Date())

            lastDisplayText = if (loc == null) {
                "cnt:$updateCount $nowStr\nloc:null\n$lastApiStatus\nstations:\n$lastStationsText"
            } else {
                val latStr = String.format(Locale.US, "%.5f", loc.latitude)
                val lonStr = String.format(Locale.US, "%.5f", loc.longitude)
                "cnt:$updateCount $nowStr\nlat:$latStr lon:$lonStr\n$lastApiStatus\nstations:\n$lastStationsText"
            }

            if (isPanelShowing()) {
                mainHandler.post { panelText?.text = lastDisplayText }
            }

            val locNonNull = loc ?: return
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastStationUpdatedAtMs >= stationUpdateIntervalMs) {
                lastStationUpdatedAtMs = nowMs
                fetchNearestStationsAsync(locNonNull.latitude, locNonNull.longitude)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // ★最優先：とにかく最速でForeground化（時間切れクラッシュ回避）
        try {
            startAsLocationFgs()
        } catch (_: Exception) {
            stopSelf()
            return
        }

        if (!canDrawOverlays()) {
            openOverlayPermissionSettings()
            stopSelf()
            return
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)

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

        try {
            if (dotView?.parent == null) windowManager.addView(dotView, dotParams)
        } catch (_: Exception) {
            stopSelf()
            return
        }

        dotView?.post { clampDotInsideScreen() }

        panelText = TextView(this).apply {
            text = "loading..."
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 14
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START
            includeFontPadding = false
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.bg_overlay_panel)
            maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
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

        try {
            if (panelText?.parent == null) windowManager.addView(panelText, panelParams)
        } catch (_: Exception) {
            stopSelf()
            return
        }

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
                panelText?.maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            }
        }

        txtDot?.setOnTouchListener(TapDragToggleTouchListener())

        fused.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startAsLocationFgs() {
        val n = createNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }

    private fun fetchNearestStationsAsync(lat: Double, lon: Double) {
        thread(start = true) {
            try {
                lastApiStatus = "api:fetching"
                val list = stationApi.getNearestStations(lat, lon)

                // ★追加：次駅推測（表示用）
                val cur = Pair(lat, lon)
                val r = predictor.predict(
                    prevLatLon = prevFix,
                    curLatLon = cur,
                    candidates = list.take(5),
                    state = predictorState
                )
                predictorState = r.state
                prevFix = cur

                val currentLine = r.currentName?.let { "現在: $it" } ?: "現在: --"
                val nextLine = r.nextName?.let { "次: $it" } ?: "次: --"
                lastStationsText = currentLine + "\n" + nextLine + "\n" + StationFormatter.formatTop3WithNextPrev(list)

                lastApiStatus = "api:ok"

                if (isPanelShowing()) {
                    mainHandler.post { panelText?.text = lastDisplayText }
                }
            } catch (_: Exception) {
                lastApiStatus = "api:err"
            }
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    }

    private fun isPanelShowing(): Boolean =
        panelText?.visibility == View.VISIBLE && (panelText?.alpha ?: 0f) > 0f

    private fun togglePanel() {
        if (isPanelShowing()) animatePanelOut() else animatePanelIn()
    }

    private fun animatePanelIn() {
        val v = panelText ?: return
        v.text = lastDisplayText
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
        private val touchSlop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop

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
        val m = resources.displayMetrics
        lastScreenW = m.widthPixels
        lastScreenH = m.heightPixels
    }

    private fun screenSizeChanged(): Boolean {
        val m = resources.displayMetrics
        return (m.widthPixels != lastScreenW || m.heightPixels != lastScreenH)
    }

    private fun restoreDotPositionFromRatioOrDefault() {
        val m = resources.displayMetrics
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
        val m = resources.displayMetrics
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
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val fallback = dp(44)
        val w = dotView?.width?.takeIf { it > 0 } ?: fallback
        val h = dotView?.height?.takeIf { it > 0 } ?: fallback

        dotParams.x = min(max(dotParams.x, 0), max(screenW - w, 0))
        dotParams.y = min(max(dotParams.y, 0), max(screenH - h, 0))

        windowManager.updateViewLayout(dotView, dotParams)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // ★FGSを確実に降ろして通知も消す（タスクキル後に残りにくくする）
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}

        super.onDestroy()

        fused.removeLocationUpdates(locationCallback)

        try { saveDotPositionAsRatio() } catch (_: Exception) {}

        try { if (dotView?.parent != null) windowManager.removeView(dotView) } catch (_: Exception) {}
        try { if (panelText?.parent != null) windowManager.removeView(panelText) } catch (_: Exception) {}

        dotView = null
        panelText = null
        txtDot = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Overlay running")
            .setContentText("位置情報を表示中（${intervalMs}ms / HIGH）")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
