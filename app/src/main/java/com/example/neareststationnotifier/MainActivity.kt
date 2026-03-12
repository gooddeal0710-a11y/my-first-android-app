package com.example.neareststationnotifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var showDebug by remember { mutableStateOf(DebugPrefs.getShowDebug(this)) }

            Column(modifier = Modifier.padding(24.dp)) {
                Text("NearestStationNotifier")

                Spacer(Modifier.height(24.dp))

                Text("デバッグ表示: " + if (showDebug) "ON（詳細）" else "OFF")

                Switch(
                    checked = showDebug,
                    onCheckedChange = { checked ->
                        showDebug = checked
                        DebugPrefs.setShowDebug(this@MainActivity, checked) // ★保存
                    }
                )

                Spacer(Modifier.height(24.dp))

                Button(onClick = { /* オーバーレイ開始 */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("オーバーレイ開始")
                }

                Spacer(Modifier.height(12.dp))

                Button(onClick = { /* オーバーレイ停止 */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("オーバーレイ停止")
                }
            }
        }
    }
}
