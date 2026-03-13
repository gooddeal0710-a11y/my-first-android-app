package com.example.neareststationnotifier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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

class OverlayService : Service() {

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

    // ★NearestStationsWorker が (context, stationApi, ...) になったので合わせる
    private val stationsWorker by lazy {
        NearestStationsWorker(
            context = this,
            stationApi = stationApi
        )
    }

    private val ui by lazy {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        OverlayUiController(this, wm)
    }

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

            if (ui.isPanelShowing()) {
                mainHandler.post { ui.setPanelText(lastDisplayText) }
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

        try {
            ui.attach()
        } catch (_: Exception) {
            stopSelf()
            return
        }

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
                lastStationsText = stationsWorker.fetchStationsText(lat, lon)
                lastApiStatus = "api:ok"

                if (ui.isPanelShowing()) {
                    mainHandler.post { ui.setPanelText(lastDisplayText) }
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

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
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
        try { ui.detach() } catch (_: Exception) {}
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
