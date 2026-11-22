package cc.dripwire.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
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
 *
 * There are three UI states:
 *  - idle: the key-entry pill is shown (no key activated yet, or disconnected/expired).
 *  - activatedPending: the key was accepted by the backend but the VPN tunnel hasn't been
 *    started yet — the details card shows device + key expiry (no ping yet), with a "Connect"
 *    button, matching the "Key Activated!" screen the user reviews before actually connecting.
 *  - connected: the tunnel is actually up (DripwireVpnService posted Status.Connected) — same
 *    card, now also showing live ping, with a "Disconnect" button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var connected = false
    private var activatedPending = false
    private var keyVisible = true

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
                showError(getString(R.string.err_vpn_permission_denied))
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
            when {
                connected -> disconnect()
                activatedPending -> requestPermissionAndConnect()
                else -> validateKeyAndActivate()
            }
        }

        binding.keyVisibilityToggle.setOnClickListener { toggleKeyVisibility() }

        val comingSoon = View.OnClickListener {
            Toast.makeText(this, R.string.toast_coming_soon, Toast.LENGTH_SHORT).show()
        }
        binding.menuButton.setOnClickListener(comingSoon)
        binding.bellButton.setOnClickListener(comingSoon)
        binding.gearButton.setOnClickListener(comingSoon)
        binding.navStats.setOnClickListener(comingSoon)
        binding.navSupport.setOnClickListener(comingSoon)
    }

    override fun onDestroy() {
        DripwireVpnService.statusListener = null
        super.onDestroy()
    }

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        val input = binding.keyInput
        val cursor = input.selectionStart.coerceAtLeast(0)
        if (keyVisible) {
            input.transformationMethod = HideReturnsTransformationMethod.getInstance()
            binding.keyVisibilityToggle.setText(R.string.glyph_eye)
        } else {
            input.transformationMethod = PasswordTransformationMethod.getInstance()
            binding.keyVisibilityToggle.setText(R.string.glyph_eye_off)
        }
        input.setSelection(cursor.coerceAtMost(input.text?.length ?: 0))
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
                    runOnUiThread { showActivatedState() }
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
        binding.connectButton.isEnabled = false
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

    /** Called right after a successful activate() — key is valid, but the tunnel isn't up yet. */
    private fun showActivatedState() {
        activatedPending = true
        connected = false
        hideError()
        binding.statusText.text = ""
        binding.connectButton.isEnabled = true
        binding.connectButton.setText(R.string.btn_connect)
        binding.connectButton.setBackgroundResource(R.drawable.bg_button_connect)
        showConnectedCard(
            deviceLabel = pendingDeviceLabel,
            expiresAt = pendingExpiresAt,
            pingMs = null,
            isLiveConnected = false,
        )
    }

    private fun render(status: DripwireVpnService.Status) {
        binding.connectButton.isEnabled = true
        when (status) {
            is DripwireVpnService.Status.Idle -> {
                connected = false
                activatedPending = false
                hideError()
                binding.statusText.setText(R.string.status_idle)
                binding.connectButton.setText(R.string.btn_submit_key)
                binding.connectButton.setBackgroundResource(R.drawable.bg_button_connect)
                showKeyEntry()
            }
            is DripwireVpnService.Status.Connected -> {
                connected = true
                activatedPending = false
                hideError()
                binding.statusText.text = ""
                binding.connectButton.setText(R.string.btn_disconnect)
                binding.connectButton.setBackgroundResource(R.drawable.bg_button_disconnect)
                showConnectedCard(
                    deviceLabel = status.deviceLabel.ifBlank { pendingDeviceLabel.ifBlank { DeviceIdentity.displayName() } },
                    expiresAt = status.expiresAt,
                    pingMs = status.pingMs,
                    isLiveConnected = true,
                )
            }
            is DripwireVpnService.Status.Error -> {
                connected = false
                activatedPending = false
                binding.statusText.text = ""
                binding.connectButton.setText(R.string.btn_submit_key)
                binding.connectButton.setBackgroundResource(R.drawable.bg_button_connect)
                showKeyEntry()
                showError(status.message)
            }
        }
    }

    private fun showKeyEntry() {
        binding.enterKeyPromptText.visibility = View.VISIBLE
        binding.keyInputLayout.visibility = View.VISIBLE
        binding.keyFormatHint.visibility = View.VISIBLE
        binding.connectedCard.visibility = View.GONE
    }

    private fun showConnectedCard(
        deviceLabel: String,
        expiresAt: Long?,
        pingMs: Int?,
        isLiveConnected: Boolean,
    ) {
        binding.enterKeyPromptText.visibility = View.GONE
        binding.keyInputLayout.visibility = View.GONE
        binding.keyFormatHint.visibility = View.GONE
        binding.connectedCard.visibility = View.VISIBLE

        binding.deviceValueText.text = deviceLabel.ifBlank { DeviceIdentity.displayName() }
        binding.timeRemainingValueText.text = formatTimeRemaining(expiresAt)

        if (isLiveConnected) {
            binding.activatedHeadlineText.setText(R.string.status_connected_title)
            binding.activatedSubtitleText.setText(R.string.status_connected_subtitle)
            binding.pingRow.visibility = View.VISIBLE
            binding.pingValueText.text = if (pingMs == null) {
                getString(R.string.ping_measuring)
            } else {
                getString(R.string.ping_value_ms, pingMs)
            }
            binding.statusBadgeText.setText(R.string.status_pill_connected)
        } else {
            binding.activatedHeadlineText.setText(R.string.status_activated_title)
            binding.activatedSubtitleText.setText(R.string.status_activated_subtitle)
            binding.pingRow.visibility = View.GONE
            binding.statusBadgeText.setText(R.string.status_pill_activated)
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
