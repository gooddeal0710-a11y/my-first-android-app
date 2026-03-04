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
import android.widget.Button
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
    private var overlayView: View? = null

    private var btnDot: Button? = null
    private var txtPanel: TextView? = null

    private lateinit var params: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())

    // 長押しで固定表示
    @Volatile private var pinned: Boolean = false

    // 5秒後に自動で隠す（pinned=falseの時だけ）
    private val autoHideRunnable = Runnable {
        if (!pinned) hidePanel()
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // 位置更新：5秒
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

            // パネル表示中だけ更新（普段は小玉だけ）
            if (isPanelVisible()) {
                mainHandler.post { txtPanel?.text = lastDisplayText }
            }

            // 駅名更新（10秒に1回）
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
        overlayView = inflater.inflate(R.layout.overlay_button, null)

        btnDot = overlayView?.findViewById(R.id.btnDot)
        txtPanel = overlayView?.findViewById(R.id.txtPanel)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 400
        }

        windowManager.addView(overlayView, params)

        // 短押し：5秒だけ表示→自動で戻る
        btnDot?.setOnClickListener {
            pinned = false
            showPanelFor(5000L)
        }

        // 長押し：固定表示（ずっと）
        btnDot?.setOnLongClickListener {
            pinned = true
            showPanelPinned()
            true
        }

        // パネルをタップ：ボタンに戻る（固定解除）
        txtPanel?.setOnClickListener {
            pinned = false
            hidePanel()
        }

        // ドラッグ移動（小玉を掴んで動かす）
        btnDot?.setOnTouchListener(DragTouchListener())

        if (!hasLocationPermission()) {
            lastDisplayText = "位置情報権限なし"
            return
        }

        fused.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun isPanelVisible(): Boolean {
        return txtPanel?.visibility == View.VISIBLE
    }

    private fun showPanelFor(ms: Long) {
        mainHandler.removeCallbacks(autoHideRunnable)
        txtPanel?.text = lastDisplayText
        txtPanel?.visibility = View.VISIBLE
        mainHandler.postDelayed(autoHideRunnable, ms)
    }

    private fun showPanelPinned() {
        mainHandler.removeCallbacks(autoHideRunnable)
        txtPanel?.text = lastDisplayText
        txtPanel?.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        mainHandler.removeCallbacks(autoHideRunnable)
        txtPanel?.visibility = View.GONE
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
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return false // クリック/長押しを生かす
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true

                    params.x = startX - dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(overlayView, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // moved=falseならクリック/長押しに任せる
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

                // HeartRails Express: x=経度, y=緯度
                val url =
                    "https://express.heartrails.com/api/json?method=getStations&x=$lon&y=$lat"

                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastApiStatus = "api:http${resp.code}"
                        return@thread
                    }

                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)

                    val stationArr = json
                        .optJSONObject("response")
                        ?.optJSONArray("station")

                    val name = stationArr
                        ?.optJSONObject(0)
                        ?.optString("name", "--")
                        ?: "--"

                    lastStationName = name
                    lastApiStatus = "api:ok"

                    // パネル表示中なら即反映
                    if (isPanelVisible()) {
                        mainHandler.post { txtPanel?.text = lastDisplayText }
                    }
                }
            } catch (e: Exception) {
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
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        btnDot = null
        txtPanel = null
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
