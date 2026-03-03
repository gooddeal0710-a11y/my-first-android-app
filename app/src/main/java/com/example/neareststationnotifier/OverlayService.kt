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
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import java.util.Locale

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null
    private var btn: Button? = null

    private val handler = Handler(Looper.getMainLooper())
    private val intervalMs = 3000L // 3秒ごと

    private val fused by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateLocationOnce()
            handler.postDelayed(this, intervalMs)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 200
        }

        windowManager.addView(overlayView, params)

        btn?.text = "loc: --"

        // 位置情報の権限が無いと取得できないので表示だけして止める
        if (!hasLocationPermission()) {
            btn?.text = "位置情報権限なし"
            return
        }

        // 定期更新開始
        handler.post(updateRunnable)

        // タップで即時更新（デバッグ用）
        btn?.setOnClickListener { updateLocationOnce() }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun updateLocationOnce() {
        if (!hasLocationPermission()) {
            btn?.text = "位置情報権限なし"
            return
        }

        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    btn?.text = "loc: null"
                    return@addOnSuccessListener
                }
                val lat = String.format(Locale.US, "%.5f", loc.latitude)
                val lon = String.format(Locale.US, "%.5f", loc.longitude)
                btn?.text = "lat:$lat\nlon:$lon"
            }
            .addOnFailureListener { e ->
                btn?.text = "loc err"
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
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
            .setContentText("位置情報を表示中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
