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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager

    // ドット（移動する）
    private var dotView: View? = null
    private var txtDot: TextView? = null
    private lateinit var dotParams: WindowManager.LayoutParams

    // 上センターパネル（固定位置）
    private var panelView: View? = null
    private var txtTopPanel: TextView? = null
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
                mainHandler.post { txtTopPanel?.text = lastDisplayText }
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

        // ドット
        dotView = inflater.inflate(R.layout.overlay_dot, null)
        txtDot = dotView?.findViewById(R.id.txtDot)

        dotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 900
        }

        windowManager.addView(dotView, dotParams)

        // パネル（最初は表示しないので addView しない）
        panelView = inflater.inflate(R.layout.overlay_top_panel, null)
        txtTopPanel = panelView?.findViewById(R.id.txtTopPanel)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80
        }

        // 短押し：5秒表示
        txtDot?.setOnClickListener {
            pinned = false
            showPanelFor(5000L)
        }

        // 長押し：固定表示
        txtDot?.setOnLongClickListener {
            pinned = true
            showPanelPinned()
            true
        }

        // パネルタップ：消す
        panelView?.setOnClickListener {
            pinned = false
            hidePanel()
        }

        // ドラッグ移動
        txtDot?.setOnTouchListener(DragTouchListener())

        if (!hasLocationPermission()) {
            lastDisplayText = "位置情報権限なし"
            return
        }

        fused.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun isPanelShowing(): Boolean = panelView?.parent != null

    private fun showPanelFor(ms: Long) {
        mainHandler.removeCallbacks(autoHideRunnable)
        txtTopPanel?.text = lastDisplayText
        if (!isPanelShowing()) windowManager.addView(panelView, panelParams)
        mainHandler.postDelayed(autoHideRunnable, ms)
    }

    private fun showPanelPinned() {
        mainHandler.removeCallbacks(autoHideRunnable)
        txtTopPanel?.text = lastDisplayText
        if (!isPanelShowing()) windowManager.addView(panelView, panelParams)
    }

    private fun hidePanel() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (isPanelShowing()) windowManager.removeView(panelView)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    startX = dotParams.x
                    startY = dotParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return false // クリック/長押しを生かす
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true

                    dotParams.x = startX + dx
                    dotParams.y = startY + dy
                    windowManager.updateViewLayout(dotView, dotParams)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    return moved
                }
            }
            return false
        }
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
                        mainHandler.post { txtTopPanel?.text = lastDisplayText }
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(autoHideRunnable)
        fused.removeLocationUpdates(locationCallback)

        try { if (dotView?.parent != null) windowManager.removeView(dotView) } catch (_: Exception) {}
        try { if (panelView?.parent != null) windowManager.removeView(panelView) } catch (_: Exception) {}

        dotView = null
        panelView = null
        txtDot = null
        txtTopPanel = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
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
