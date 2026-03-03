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
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null
    private var btn: Button? = null

    private val fused by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    // 位置更新は2秒
    private val intervalMs = 2000L

    private val locationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private var updateCount = 0
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    // 駅名表示用（駅APIは10秒に1回だけ叩く）
    @Volatile private var lastStationName: String = "--"
    @Volatile private var lastStationUpdatedAtMs: Long = 0L
    private val stationUpdateIntervalMs = 10_000L
    private val http = OkHttpClient()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation
            updateCount += 1
            val nowStr = timeFmt.format(Date())

            if (loc == null) {
                btn?.text = "cnt:$updateCount $nowStr\nloc:null\nstation:$lastStationName"
                return
            }

            val lat = loc.latitude
            val lon = loc.longitude

            // 表示（lat/lonは小数点5桁）
            val latStr = String.format(Locale.US, "%.5f", lat)
            val lonStr = String.format(Locale.US, "%.5f", lon)
            btn?.text = "cnt:$updateCount $nowStr\nlat:$latStr lon:$lonStr\nstation:$lastStationName"

            // 駅名更新（10秒に1回）
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastStationUpdatedAtMs >= stationUpdateIntervalMs) {
                lastStationUpdatedAtMs = nowMs
                fetchNearestStationAsync(lat, lon)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        startForeground(1, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_button, null)
        btn = overlayView?.findViewById(R.id.btnOverlay)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 200
        }

        windowManager.addView(overlayView, params)

        if (!hasLocationPermission()) {
            btn?.text = "位置情報権限なし"
            return
        }

        btn?.text = "loc: waiting...\nstation:$lastStationName"

        // ★重要：タップで表示が変わる処理は入れない（前の挙動に戻す）
        // btn?.setOnClickListener { ... } ← これを置かない

        fused.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun fetchNearestStationAsync(lat: Double, lon: Double) {
        thread(start = true) {
            try {
                // 例：HeartRails Express（最寄り駅）
                val url =
                    "https://express.heartrails.com/api/json?method=getStations&x=$lon&y=$lat"

                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastStationName = "api:${resp.code}"
                        return@thread
                    }
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)

                    // response.station[0].name を読む想定
                    val stationArr = json
                        .optJSONObject("response")
                        ?.optJSONArray("station")

                    val name = stationArr
                        ?.optJSONObject(0)
                        ?.optString("name", "--")
                        ?: "--"

                    lastStationName = name
                }
            } catch (e: Exception) {
                lastStationName = "api:err"
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
        fused.removeLocationUpdates(locationCallback)
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        btn = null
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
