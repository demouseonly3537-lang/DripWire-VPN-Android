package cc.dripwire.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DripwireApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIF_CHANNEL_ID = "dripwire_vpn"
        const val NOTIF_ID = 1

        // In-memory only (not persisted) — the Telegram/WhatsApp channel-join gate is shown
        // once per app process, not on every MainActivity recreation (e.g. a screen rotation),
        // but it comes back after the app is fully closed and reopened, matching "when the app
        // opens" from the spec without being naggy on every configuration change.
        @Volatile
        var channelGatePassed: Boolean = false
    }
}
