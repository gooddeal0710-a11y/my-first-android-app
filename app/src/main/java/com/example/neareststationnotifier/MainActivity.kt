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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // 起動直後の「連続誘導」を1回だけやるためのフラグ
    private var didInitialPermissionFlow = false

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 位置情報の結果が返ってきたら、続けてオーバーレイへ
            if (hasLocationPermission() && !canDrawOverlays()) {
                openOverlayPermissionSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var showDebug by remember { mutableStateOf(DebugPrefs.getShowDebug(this)) }

                // 初回表示時に「位置情報→オーバーレイ」を連続で誘導
                LaunchedEffect(Unit) {
                    if (!didInitialPermissionFlow) {
                        didInitialPermissionFlow = true
                        startInitialPermissionFlow()
                    }
                }

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

    private fun startInitialPermissionFlow() {
        // 1) 位置情報が無ければまず要求（結果コールバックでオーバーレイへ続く）
        if (!hasLocationPermission()) {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        // 2) 位置情報が既にOKなら、オーバーレイへ
        if (!canDrawOverlays()) {
            openOverlayPermissionSettings()
        }
    }

    private fun startOverlayWithPermissions() {
        if (!hasLocationPermission()) {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        if (!canDrawOverlays()) {
            openOverlayPermissionSettings()
            return
        }

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
