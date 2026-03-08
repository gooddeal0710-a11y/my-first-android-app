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
import androidx.compose.foundation.text.selection.SelectionContainer
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
        private const val KEY_FIRST_SETUP_DONE = "first_setup_done"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // 「開始ボタン」由来の自動再開だけ許可する
    private var pendingStartOverlay = false

    // 権限リクエストの連打防止（StackOverflow対策）
    private var permissionRequestInFlight = false

    // 初回セットアップ中かどうか（起動時に許可を整える用）
    private var firstSetupInProgress = false

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            permissionRequestInFlight = false
            // 初回セットアップ中なら次のステップへ
            if (firstSetupInProgress) {
                mainHandler.postDelayed({ ensurePermissionsOnlyStep() }, 200L)
            }
            // ボタン押下中なら起動再試行
            if (pendingStartOverlay) {
                mainHandler.postDelayed({ startOverlayServiceSafely() }, 200L)
            }
        }

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            permissionRequestInFlight = false
            if (firstSetupInProgress) {
                mainHandler.postDelayed({ ensurePermissionsOnlyStep() }, 200L)
            }
            if (pendingStartOverlay) {
                mainHandler.postDelayed({ startOverlayServiceSafely() }, 200L)
            }
        }

    override fun onResume() {
        super.onResume()

        // 設定画面から戻った直後の反映遅延対策：ただし「開始ボタン押下中」だけ
        if (pendingStartOverlay) {
            mainHandler.postDelayed({
                if (pendingStartOverlay) startOverlayServiceSafely()
            }, 300L)
        }

        // 初回セットアップ中なら、戻ってきたタイミングで次のステップへ
        if (firstSetupInProgress) {
            mainHandler.postDelayed({ ensurePermissionsOnlyStep() }, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashLogger.install(applicationContext)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 初回起動：許可だけ整える（サービスは起動しない）
        if (!prefs.getBoolean(KEY_FIRST_SETUP_DONE, false)) {
            firstSetupInProgress = true
            mainHandler.postDelayed({
                ensurePermissionsOnlyStep()
                prefs.edit().putBoolean(KEY_FIRST_SETUP_DONE, true).apply()
            }, 200L)
        }

        setContent {
            val crashTextState = remember { mutableStateOf(CrashLogger.read(this)) }
            val scrollState = rememberScrollState()

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
                            SelectionContainer {
                                Text(text = crashTextState.value!!)
                            }

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

    /**
     * 初回起動用：1ステップずつ許可を整える（サービスは起動しない）
     * 連打防止のため、inFlight中は何もしない
     */
    private fun ensurePermissionsOnlyStep() {
        if (permissionRequestInFlight) return

        // Overlay権限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            openOverlayPermissionScreen()
            return
        }

        // 通知権限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionRequestInFlight = true
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 位置情報権限
        if (!hasLocationPermission()) {
            permissionRequestInFlight = true
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        // 全部揃ったら初回セットアップ終了
        firstSetupInProgress = false
    }

    /**
     * ボタン押下用：毎回チェックして、揃っていればサービス起動
     */
    private fun startOverlayServiceSafely() {
        if (permissionRequestInFlight) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            openOverlayPermissionScreen()
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionRequestInFlight = true
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!hasLocationPermission()) {
            permissionRequestInFlight = true
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

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
