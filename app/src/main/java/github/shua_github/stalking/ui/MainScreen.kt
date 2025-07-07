package github.shua_github.stalking.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import github.shua_github.stalking.MonitorService

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(false) }
    var serverUrl by remember {
        mutableStateOf(TextFieldValue(prefs.getString("serverUrl", "") ?: ""))
    }
    var interval by remember {
        mutableStateOf(TextFieldValue(prefs.getLong("interval", 60L).toString()))
    }
    var disableCache by remember { mutableStateOf(prefs.getBoolean("disableCache", false)) }
    var disableNotification by remember {
        mutableStateOf(prefs.getBoolean("disableNotification", false))
    }
    var saving by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }

    val activity = context as? Activity
    val fineLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    val backgroundLocationPermission = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    val textColor = MaterialTheme.colorScheme.onPrimary
    val outlineColor = MaterialTheme.colorScheme.outline
    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = textColor,
        unfocusedTextColor = textColor,
        focusedLabelColor = outlineColor,
        unfocusedLabelColor = outlineColor.copy(alpha = 0.8f),
        focusedPlaceholderColor = textColor.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = textColor.copy(alpha = 0.6f)
    )

    // 使用WindowInsets获取状态栏高度
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    fun handleSwitchChange(newValue: Boolean) {
        enabled = newValue
        // 不再立即启动/停止服务，保存时再处理
    }

    LaunchedEffect(Unit) {
        enabled = prefs.getBoolean("enabled", false)
    }

    DisposableEffect(enabled, page) {
        var receiver: BroadcastReceiver? = null
        var stopReceiver: BroadcastReceiver? = null
        if (enabled && page == 1) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent != null) {
                        @Suppress("UNCHECKED_CAST")
                        val statusMap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getSerializableExtra("statusMap", HashMap::class.java) as? HashMap<String, Any?>
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getSerializableExtra("statusMap") as? HashMap<String, Any?>
                        }
                        if (statusMap != null) {
                            status = statusMap
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter("action_monitor_status_update"), ContextCompat.RECEIVER_NOT_EXPORTED)

            stopReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    status = mapOf(
                        "uploadCount" to "-",
                        "aliveSeconds" to "-",
                        "disableNotification" to "-",
                        "disableCache" to "-",
                        "lat" to "-",
                        "lng" to "-"
                    )
                }
            }
            ContextCompat.registerReceiver(context, stopReceiver, IntentFilter("action_monitor_service_stopped"), ContextCompat.RECEIVER_NOT_EXPORTED)

            status = mapOf(
                "uploadCount" to prefs.getInt("uploadCount", 0),
                "aliveSeconds" to prefs.getLong("aliveSeconds", 0L),
                "disableNotification" to prefs.getBoolean("disableNotification", false),
                "disableCache" to prefs.getBoolean("disableCache", false),
                "lat" to null,
                "lng" to null
            )
        } else if (!enabled && page == 1) {
            status = mapOf(
                "uploadCount" to "-",
                "aliveSeconds" to "-",
                "disableNotification" to "-",
                "disableCache" to "-",
                "lat" to "-",
                "lng" to "-"
            )
        }
        onDispose {
            if (receiver != null) context.unregisterReceiver(receiver)
            if (stopReceiver != null) context.unregisterReceiver(stopReceiver)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarHeight + 1.dp)
    ) {
        Column(Modifier.padding(24.dp).weight(1f)) {
            if (page == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("采集开关", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { handleSwitchChange(it) })
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it },
                    label = { Text("上传间隔(秒)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("禁用缓存", Modifier.weight(1f))
                    Switch(checked = disableCache, onCheckedChange = { disableCache = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("禁用通知", Modifier.weight(1f))
                    Switch(checked = disableNotification, onCheckedChange = { disableNotification = it })
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (activity != null && activity.checkSelfPermission(fineLocationPermission) != PackageManager.PERMISSION_GRANTED) {
                            activity.requestPermissions(arrayOf(fineLocationPermission), 100)
                            return@Button
                        }
                        if (activity != null && activity.checkSelfPermission(backgroundLocationPermission) != PackageManager.PERMISSION_GRANTED) {
                            activity.requestPermissions(arrayOf(backgroundLocationPermission), 101)
                            return@Button
                        }
                        saving = true
                        prefs.edit {
                            putString("serverUrl", serverUrl.text)
                            putLong("interval", interval.text.toLongOrNull() ?: 60L)
                            putBoolean("disableCache", disableCache)
                            putBoolean("disableNotification", disableNotification)
                            putBoolean("enabled", enabled)
                        }
                        // 保存后再根据enabled状态启动或停止服务
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
            } else {
                DisposableEffect(page) {
                    if (page == 1) {
                        if (status.isEmpty() || status["uploadCount"] == null) {
                            status = mapOf(
                                "uploadCount" to prefs.getInt("uploadCount", 0),
                                "aliveSeconds" to prefs.getLong("aliveSeconds", 0L),
                                "disableNotification" to prefs.getBoolean("disableNotification", false),
                                "disableCache" to prefs.getBoolean("disableCache", false),
                                "lat" to null,
                                "lng" to null
                            )
                        }
                    }
                    onDispose { }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(20.dp)
                    ) {
                        Column {
                            Text("上报次数: ${status["uploadCount"] ?: "-"}")
                            Text("存活时间: ${status["aliveSeconds"] ?: "-"} 秒")
                            Text("禁用通知: ${if (status["disableNotification"] == "-" || status["disableNotification"] == null) "-" else if (status["disableNotification"] == true) "是" else "否"}")
                            Text("禁用缓存: ${if (status["disableCache"] == "-" || status["disableCache"] == null) "-" else if (status["disableCache"] == true) "是" else "否"}")
                        }
                    }
                }

            }
            Spacer(Modifier.weight(1f))
        }
        TabRow(selectedTabIndex = page, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = page == 0,
                onClick = { page = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("配置")
                    }
                }
            )
            Tab(
                selected = page == 1,
                onClick = { page = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("状态")
                    }
                }
            )
        }
    }
}

