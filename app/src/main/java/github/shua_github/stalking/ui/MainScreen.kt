package github.shua_github.stalking.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import github.shua_github.stalking.MonitorService
import androidx.core.content.edit

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var serverUrl by remember { mutableStateOf(TextFieldValue(prefs.getString("serverUrl", "") ?: "")) }
    var interval by remember { mutableStateOf(TextFieldValue(prefs.getLong("interval", 60L).toString())) }
    var saving by remember { mutableStateOf(false) }
    val activity = context as? Activity
    val fineLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    val backgroundLocationPermission = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    Column(Modifier.padding(24.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("采集开关", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it },
            label = { Text("上传间隔(秒)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                // 检查前台定位权限
                if (activity != null && activity.checkSelfPermission(fineLocationPermission) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(fineLocationPermission), 100)
                    return@Button
                }
                // 检查后台定位权限（Android 10+，你已设置最低API为12，直接检查）
                if (activity != null && activity.checkSelfPermission(backgroundLocationPermission) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(backgroundLocationPermission), 101)
                    return@Button
                }
                saving = true
                prefs.edit {
                    putBoolean("enabled", enabled)
                        .putString("serverUrl", serverUrl.text)
                        .putLong("interval", interval.text.toLongOrNull() ?: 60L)
                }
                val intent = Intent(context, MonitorService::class.java)
                if (enabled) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
                saving = false
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
        }
    }
}
