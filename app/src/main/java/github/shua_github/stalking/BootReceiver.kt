package github.shua_github.stalking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
            if (prefs.getBoolean("enabled", false)) {
                val serviceIntent = Intent(context, MonitorService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
