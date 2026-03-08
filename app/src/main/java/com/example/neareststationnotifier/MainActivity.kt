package com.example.neareststationnotifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 許可/不許可どちらでも、ボタンをもう一度押してもらう前提（ここでは自動起動しない）
        }

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 同上
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashLogger.install(applicationContext)

        setContent {
            val crashTextState = remember { mutableStateOf(CrashLogger.read(this)) }
            val scrollState = rememberScrollState()

            // 保存済みのデバッグ設定を読み込み（デフォルトは true = デバッグ表示あり）
            val prefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
            val debugState = remember {
                mutableStateOf(prefs.getBoolean(KEY_OVERLAY_DEBUG, true))
            }

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
                                onClick = { startOverlayServiceSafely() },
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                Text("オーバーレイ開始")
                            }

                            Button(
                                onClick = { stopOverlayService() },
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
        // 1) Android 13+ 通知権限（無ければリクエストして終了）
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

        // 2) 位置情報権限（無ければリクエストして終了）
        if (!hasLocationPermission()) {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        // 3) ここまで揃って初めてサービス起動
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

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
    }
}
