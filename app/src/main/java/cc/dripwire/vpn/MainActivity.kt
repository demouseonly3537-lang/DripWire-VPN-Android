package cc.dripwire.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cc.dripwire.vpn.databinding.ActivityMainBinding
import cc.dripwire.vpn.license.DeviceIdentity
import cc.dripwire.vpn.license.LicenseClient
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The only screen in the app. End users never see a raw config, protocol name, or server
 * address anywhere here — they paste a key, and from then on only ever see: whether they're
 * connected, this device's name, how much time is left on their key, and the live ping. Any
 * failure (bad key, blocked, expired, too many devices, no internet, ...) is shown as one
 * plain-English sentence in the error banner, never a raw error code or stack trace.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var connected = false

    // Filled in by a successful activate() call and consumed once the VPN permission flow
    // (which may briefly leave the activity) returns and we actually start the service.
    private var pendingConfig: String? = null
    private var pendingExpiresAt: Long? = null
    private var pendingServerTime: Long = System.currentTimeMillis()
    private var pendingDeviceLabel: String = ""
    private var pendingKey: String = ""

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startTunnel()
            } else {
                binding.connectButton.isEnabled = true
                binding.statusText.setText(R.string.status_idle)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("dripwire", MODE_PRIVATE)
        binding.keyInput.setText(prefs.getString(PREF_LAST_KEY, ""))

        DripwireVpnService.statusListener = { status -> runOnUiThread { render(status) } }

        binding.connectButton.setOnClickListener {
            if (connected) disconnect() else validateKeyAndActivate()
        }
    }

    override fun onDestroy() {
        DripwireVpnService.statusListener = null
        super.onDestroy()
    }

    private fun validateKeyAndActivate() {
        val key = binding.keyInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) {
            showError(getString(R.string.err_empty_key))
            return
        }
        hideError()
        binding.connectButton.isEnabled = false
        binding.statusText.setText(R.string.status_checking_key)

        val deviceId = DeviceIdentity.get(this)
        val deviceName = DeviceIdentity.displayName()

        thread {
            when (val result = LicenseClient.activate(key, deviceId, deviceName)) {
                is LicenseClient.ActivateResult.Success -> {
                    prefs.edit().putString(PREF_LAST_KEY, key).apply()
                    pendingConfig = result.config
                    pendingExpiresAt = result.expiresAt
                    pendingServerTime = result.serverTime
                    pendingDeviceLabel = result.deviceLabel
                    pendingKey = key
                    runOnUiThread { requestPermissionAndConnect() }
                }
                is LicenseClient.ActivateResult.Failure -> runOnUiThread {
                    binding.connectButton.isEnabled = true
                    binding.statusText.setText(R.string.status_idle)
                    showError(errorMessageFor(result.error))
                }
                is LicenseClient.ActivateResult.NetworkError -> runOnUiThread {
                    binding.connectButton.isEnabled = true
                    binding.statusText.setText(R.string.status_idle)
                    showError(getString(R.string.err_network))
                }
            }
        }
    }

    private fun requestPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startTunnel()
        }
    }

    private fun startTunnel() {
        val config = pendingConfig ?: return
        binding.statusText.setText(R.string.status_starting)
        binding.connectButton.isEnabled = false
        val svcIntent = Intent(this, DripwireVpnService::class.java)
            .putExtra(DripwireVpnService.EXTRA_CONFIG_JSON, config)
            .putExtra(DripwireVpnService.EXTRA_KEY, pendingKey)
            .putExtra(DripwireVpnService.EXTRA_DEVICE_ID, DeviceIdentity.get(this))
            .putExtra(DripwireVpnService.EXTRA_DEVICE_LABEL, pendingDeviceLabel)
            .putExtra(DripwireVpnService.EXTRA_EXPIRES_AT, pendingExpiresAt ?: -1L)
            .putExtra(DripwireVpnService.EXTRA_SERVER_TIME, pendingServerTime)
        startService(svcIntent)
    }

    private fun disconnect() {
        startService(
            Intent(this, DripwireVpnService::class.java).setAction(DripwireVpnService.ACTION_DISCONNECT),
        )
    }

    private fun render(status: DripwireVpnService.Status) {
        binding.connectButton.isEnabled = true
        when (status) {
            is DripwireVpnService.Status.Idle -> {
                connected = false
                hideError()
                binding.statusText.setText(R.string.status_idle)
                binding.connectButton.setText(R.string.btn_connect)
                showKeyEntry()
            }
            is DripwireVpnService.Status.Connected -> {
                connected = true
                hideError()
                binding.statusText.text = ""
                binding.connectButton.setText(R.string.btn_disconnect)
                showConnectedCard(status)
            }
            is DripwireVpnService.Status.Error -> {
                connected = false
                binding.statusText.setText(R.string.status_idle)
                binding.connectButton.setText(R.string.btn_connect)
                showKeyEntry()
                showError(status.message)
            }
        }
    }

    private fun showKeyEntry() {
        binding.keyInputLayout.visibility = View.VISIBLE
        binding.keyFormatHint.visibility = View.VISIBLE
        binding.connectedCard.visibility = View.GONE
    }

    private fun showConnectedCard(status: DripwireVpnService.Status.Connected) {
        binding.keyInputLayout.visibility = View.GONE
        binding.keyFormatHint.visibility = View.GONE
        binding.connectedCard.visibility = View.VISIBLE
        binding.deviceValueText.text = status.deviceLabel.ifBlank { DeviceIdentity.displayName() }
        binding.timeRemainingValueText.text = formatTimeRemaining(status.expiresAt)
        binding.pingValueText.text = if (status.pingMs == null) {
            getString(R.string.ping_measuring)
        } else {
            getString(R.string.ping_value_ms, status.pingMs)
        }
    }

    private fun showError(message: String) {
        binding.errorBanner.text = message
        binding.errorBanner.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorBanner.visibility = View.GONE
    }

    private fun formatTimeRemaining(expiresAt: Long?): String {
        if (expiresAt == null) return "—"
        val remainingMs = expiresAt - System.currentTimeMillis()
        if (remainingMs <= 0) return getString(R.string.time_expired)
        val totalMinutes = remainingMs / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> String.format(Locale.US, "%dd %dh", days, hours)
            hours > 0 -> String.format(Locale.US, "%dh %dm", hours, minutes)
            else -> String.format(Locale.US, "%dm", minutes)
        }
    }

    private fun errorMessageFor(code: String): String = when (code) {
        "invalid_key" -> getString(R.string.err_key_invalid)
        "blocked" -> getString(R.string.err_key_blocked)
        "expired" -> getString(R.string.err_key_expired)
        "device_limit" -> getString(R.string.err_device_limit)
        "device_removed" -> getString(R.string.err_device_removed)
        "config_missing" -> getString(R.string.err_config_missing)
        "rate_limited" -> getString(R.string.err_rate_limited)
        "missing_device_id" -> getString(R.string.err_missing_device_id)
        else -> getString(R.string.err_unknown, code)
    }

    companion object {
        private const val PREF_LAST_KEY = "last_license_key"
    }
}
