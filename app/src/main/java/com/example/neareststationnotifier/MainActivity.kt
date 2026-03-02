package com.example.neareststationnotifier

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.neareststationnotifier.ui.theme.NearestStationNotifierTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // クラッシュログ保存を有効化（次回起動時に画面表示できる）
        CrashLogger.install(applicationContext)

        setContent {
            val crashTextState = remember { mutableStateOf(CrashLogger.read(this)) }
            val scrollState = rememberScrollState()

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

                            Text(
                                text = "\nこのログの「FATAL EXCEPTION」〜「Caused by」付近をここに貼ってください。",
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        } else {
                            Text(text = "\nクラッシュログはありません。\n")

                            Button(
                                onClick = { openOverlayPermissionScreen() },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("上に表示（オーバーレイ）許可画面を開く")
                            }

                            Button(
                                onClick = { startOverlayServiceSafely() },
                                modifier = Modifier.padding(top = 8.dp)
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

    private fun openOverlayPermissionScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun startOverlayServiceSafely() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
    }
}
