package github.shua_github.stalking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.provider.Settings
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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

class MonitorService : Service() {
    private val channelId = "monitor_service_channel"
    private val notificationId = 1
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val client = OkHttpClient()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val interval = prefs.getLong("interval", 60L)
        val enabled = prefs.getBoolean("enabled", false)
        if (!enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(notificationId, buildNotification())
        job?.cancel()
        job = scope.launch @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE) {
            while (true) {
                collectAndUpload(serverUrl)
                delay(interval * 1000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("监控服务运行中")
            .setContentText("正在收集设备信息...")
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
        val location = getLocation()
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
                "deviceId" to deviceId
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
    private suspend fun getLocation(): Map<String, Any?> {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        return try {
            val location = suspendCancellableCoroutine<Location?> { cont ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { cont.resume(it, null) }
                    .addOnFailureListener { cont.resume(null, null) }
            }

            if (location != null) {
                mapOf("lat" to location.latitude, "lng" to location.longitude)
            } else {
                // fallback: 主动请求一次最新定位
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
        } catch (e: Exception) {
            e.printStackTrace()
            mapOf("lat" to null, "lng" to null)
        }
    }


    private fun getBootTime(): Long {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime() / 1000
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
        } catch (e: Exception) {
            Log.e("MonitorService", "Upload failed: ${e.message}", e)
        }
    }

}
