package com.example.neareststationnotifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 結果はここでは使わない（ボタン押下時に再チェックして起動する）
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初回起動で位置情報権限を出したいならここで要求
        ensureLocationPermission()

        setContent {
            MaterialTheme {
                var showDebug by remember { mutableStateOf(DebugPrefs.getShowDebug(this)) }

                Column(modifier = Modifier.padding(24.dp)) {
                    Text("NearestStationNotifier")

                    Spacer(Modifier.height(24.dp))

                    Text("デバッグ表示: " + if (showDebug) "ON（詳細）" else "OFF")

                    Switch(
                        checked = showDebug,
                        onCheckedChange = { checked ->
                            showDebug = checked
                            DebugPrefs.setShowDebug(this@MainActivity, checked)
                        }
                    )

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { startOverlayWithPermissions() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("オーバーレイ開始")
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { stopOverlay() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("オーバーレイ停止")
                    }
                }
            }
        }
    }

    private fun startOverlayWithPermissions() {
        // 1) 位置情報権限
        if (!hasLocationPermission()) {
            ensureLocationPermission()
            return
        }

        // 2) オーバーレイ権限
        if (!canDrawOverlays()) {
            openOverlayPermissionSettings()
            return
        }

        // 3) Service起動
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) return

        requestLocationPerms.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
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
}
