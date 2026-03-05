package com.example.neareststationnotifier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
    @Volatile private var pinned: Boolean = false
    private val autoHideRunnable = Runnable { if (!pinned) hidePanel() }

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

    @Volatile private var lastStationName: String = "--"
    @Volatile private var lastApiStatus: String = "api:idle"
    @Volatile private var lastStationUpdatedAtMs: Long = 0L
    private val stationUpdateIntervalMs = 10_000L

    private val http = OkHttpClient()
    @Volatile private var lastDisplayText: String = "loading..."

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation
            updateCount += 1
            val nowStr = timeFmt.format(Date())

            lastDisplayText = if (loc == null) {
                "cnt:$updateCount $nowStr\nloc:null\n$lastApiStatus\nstation:$lastStationName"
            } else {
                val latStr = String.format(Locale.US, "%.5f", loc.latitude)
                val lonStr = String.format(Locale.US, "%.5f", loc.longitude)
                "cnt:$updateCount $nowStr\nlat:$latStr lon:$lonStr\n$lastApiStatus\nstation:$lastStationName"
            }

            if (isPanelShowing()) {
                mainHandler.post { panelText?.text = lastDisplayText }
            }

            val locNonNull = loc ?: return
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastStationUpdatedAtMs >= stationUpdateIntervalMs) {
                lastStationUpdatedAtMs = nowMs
                fetchNearestStationAsync(locNonNull.latitude, locNonNull.longitude)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)

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
            x = dp(24)
            y = dp(400)
        }

        windowManager.addView(dotView, dotParams)

        // 初回レイアウト後に1回だけ画面内へ収める
        dotView?.post { clampDotInsideScreen() }

        panelText = TextView(this).apply {
            text = "loading..."
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setBackgroundColor(0x66000000)
            setOnClickListener {
                pinned = false
                hidePanel()
            }
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
        }

        txtDot?.setOnTouchListener(TapLongDragTouchListener())

        if (!hasLocationPermission()) {
            lastDisplayText = "位置情報権限なし"
            return
        }
        fused.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun isPanelShowing(): Boolean = panelText?.parent != null

    private fun showPanelFor(ms: Long) {
        mainHandler.removeCallbacks(autoHideRunnable)
        panelText?.text = lastDisplayText
        if (!isPanelShowing()) windowManager.addView(panelText, panelParams)
        mainHandler.postDelayed(autoHideRunnable, ms)
    }

    private fun showPanelPinned() {
        mainHandler.removeCallbacks(autoHideRunnable)
        panelText?.text = lastDisplayText
        if (!isPanelShowing()) windowManager.addView(panelText, panelParams)
    }

    private fun hidePanel() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (isPanelShowing()) windowManager.removeView(panelText)
    }

    private inner class TapLongDragTouchListener : View.OnTouchListener {

        private var dragging = false
        private var longPressed = false

        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val touchSlop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop

        // 指が玉のどこを掴んだか（View内座標）
        private var touchX = 0f
        private var touchY = 0f

        // DOWN時のraw（ドラッグ開始判定用）
        private var downRawX = 0f
        private var downRawY = 0f

        private val longPressRunnable = Runnable {
            if (!dragging) {
                longPressed = true
                pinned = true
                showPanelPinned()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    longPressed = false

                    // View内の掴み位置を保持（これが縦方向の初動詰まり対策）
                    touchX = event.x
                    touchY = event.y

                    downRawX = event.rawX
                    downRawY = event.rawY

                    mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    if (dragging) {
                        // raw座標 - 掴み位置 = 左上座標（params.x/y）
                        dotParams.x = (event.rawX - touchX).toInt()
                        dotParams.y = (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(dotView, dotParams)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (dragging) {
                        clampDotInsideScreen()
                        return true
                    }
                    if (longPressed) return true

                    pinned = false
                    showPanelFor(5000L)
                    return true
                }
            }
            return false
        }
    }

    private fun clampDotInsideScreen() {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val fallback = dp(48)
        val w = dotView?.width?.takeIf { it > 0 } ?: fallback
        val h = dotView?.height?.takeIf { it > 0 } ?: fallback

        dotParams.x = min(max(dotParams.x, 0), max(screenW - w, 0))
        dotParams.y = min(max(dotParams.y, 0), max(screenH - h, 0))

        windowManager.updateViewLayout(dotView, dotParams)
    }

    private fun fetchNearestStationAsync(lat: Double, lon: Double) {
        thread(start = true) {
            try {
                lastApiStatus = "api:fetching"
                val url = "https://express.heartrails.com/api/json?method=getStations&x=$lon&y=$lat"
                val req = Request.Builder().url(url).get().build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastApiStatus = "api:http${resp.code}"
                        return@thread
                    }

                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val stationArr = json.optJSONObject("response")?.optJSONArray("station")
                    val name = stationArr?.optJSONObject(0)?.optString("name", "--") ?: "--"

                    lastStationName = name
                    lastApiStatus = "api:ok"

                    if (isPanelShowing()) {
                        mainHandler.post { panelText?.text = lastDisplayText }
                    }
                }
            } catch (_: Exception) {
                lastApiStatus = "api:err"
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(autoHideRunnable)
        fused.removeLocationUpdates(locationCallback)

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
