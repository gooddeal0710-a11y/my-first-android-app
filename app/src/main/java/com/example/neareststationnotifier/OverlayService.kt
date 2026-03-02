import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null

    override fun onCreate() {
        super.onCreate()

        startForeground(1, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_button, null)

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

        overlayView?.findViewById<Button>(R.id.btnOverlay)?.setOnClickListener {
            // 次のStepで「最寄り駅表示オーバーレイ」に差し替える
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
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
            .setContentText("オーバーレイ表示中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
