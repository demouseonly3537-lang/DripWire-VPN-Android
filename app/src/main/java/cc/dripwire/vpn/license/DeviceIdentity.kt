package cc.dripwire.vpn.license

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * This device's own stable identity for the licensing backend — a random UUID generated once
 * and kept in SharedPreferences (not tied to IMEI/Android ID/etc., so it carries no other
 * meaning and can't be used to fingerprint the device for anything else). The backend uses it
 * to tell "how many distinct devices are using this key" apart from "the same device
 * reconnecting".
 */
object DeviceIdentity {
    private const val PREFS_NAME = "rakshakx_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    /** A short, human-friendly label for this device — shown to the user and sent to the
     * admin panel's "devices" view so an admin can tell devices apart at a glance. */
    fun displayName(): String {
        val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: ""
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
        return if (model.startsWith(manufacturer, ignoreCase = true) || manufacturer.isBlank()) {
            model
        } else {
            "$manufacturer $model"
        }
    }
}
