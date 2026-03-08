package com.example.neareststationnotifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.neareststationnotifier.ui.theme.NearestStationNotifierTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "prefs"
        private const val KEY_OVERLAY_DEBUG = "pref_overlay_debug"
    }

    private var pendingStartOverlay = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)

            if (pendingStartOverlay && granted) {
                startOverlayServiceSafely()
            } else {
                pendingStartOverlay = false
            }
        }

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingStartOverlay && granted) {
                startOverlayServiceSafely()
            } else {
                pendingStartOverlay = false
            }
        }

    override fun onResume() {
        super.onResume()

        // 設定画面から戻った直後は権限反映が遅れる端末があるので少し待って再チェック
        if (pendingStartOverlay) {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                if (pendingStartOverlay) startOverlayServiceSafely()
            }, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashLogger.install(applicationContext)

        setContent {
            val crashTextState = remember { mutableStateOf(CrashLogger.read(this)) }
            val scrollState = rememberScrollState()

            val prefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
            val debugState = remember { mutableStateOf(prefs.getBoolean(KEY_OVERLAY_DEBUG, true)) }

            NearestStationNotifierTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = "NearestStationNotifier",
                            style = MaterialTheme.typography.titleLarge
                        )

                        if (!crashTextState.value.isNullOrBlank()) {
                            Text(
                                text = "\n直近のクラッシュログ（crash.txt）:\n",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(text = crashTextState.value!!)

                            Button(
                                onClick = {
                                    CrashLogger.clear(this@MainActivity)
                                    crashTextState.value = null
                                },
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                Text("クラッシュログを削除")
                            }
                        } else {
                            Text(text = "\nクラッシュログはありません。\n")

                            Text(
                                text = if (debugState.value) "デバッグ表示: ON（詳細）" else "デバッグ表示: OFF（最終1行）",
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Switch(
                                checked = debugState.value,
                                onCheckedChange = { checked ->
                                    debugState.value = checked
                                    prefs.edit().putBoolean(KEY_OVERLAY_DEBUG, checked).apply()
                                },
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            Button(
                                onClick = {
                                    pendingStartOverlay = true
                                    startOverlayServiceSafely()
                                },
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                Text("オーバーレイ開始")
                            }

                            Button(
                                onClick = {
                                    pendingStartOverlay = false
                                    stopOverlayService()
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("オーバーレイ停止")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startOverlayServiceSafely() {
        // 0) Overlay権限（無ければ設定へ）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            openOverlayPermissionScreen()
            return
        }

        // 1) 通知権限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 2) 位置情報権限
        if (!hasLocationPermission()) {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        // 3) 起動
        pendingStartOverlay = false
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlayPermissionScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
    }
}
