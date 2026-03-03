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

    private val intervalMs = 2000L

    private val locationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private var updateCount = 0
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    // 駅名表示用
    @Volatile private var lastStationName: String = "--"
    @Volatile private var lastStationUpdatedAtMs: Long = 0L
    private val stationUpdateIntervalMs = 10_000L // ★駅APIは10秒に1回
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
            val lon
