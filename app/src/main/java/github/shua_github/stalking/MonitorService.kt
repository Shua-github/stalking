package github.shua_github.stalking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.os.IBinder
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val location = getLocation() ?: mapOf("lat" to null, "lng" to null)
        val isOnline = isNetworkConnected()
        val bootTime = System.currentTimeMillis() / 1000
        val time = System.currentTimeMillis() / 1000
        return mapOf(
            "type" to "device_info",
            "data" to mapOf(
                "time" to time,
                "lat" to location["lat"],
                "lng" to location["lng"],
                "isOnline" to isOnline,
                "bootTime" to bootTime
            )
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun getLocation(): Map<String, Any?>? {
        return try {
            val task = fusedLocationClient.lastLocation
            val loc = suspendCancellableCoroutine<Location?> { cont ->
                task.addOnSuccessListener { cont.resume(it, null) }
                task.addOnFailureListener { cont.resume(null, null) }
            }
            if (loc != null) mapOf("lat" to loc.latitude, "lng" to loc.longitude) else mapOf("lat" to null, "lng" to null)
        } catch (_: Exception) {
            mapOf("lat" to null, "lng" to null)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        return net != null && net.isConnected
    }

    private fun getBootTime(): String {
        val bootMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(bootMillis))
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
