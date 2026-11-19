package cc.dripwire.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import cc.dripwire.vpn.databinding.ActivityMainBinding
import cc.dripwire.vpn.proxy.ConfigParseException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var connected = false

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startTunnel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("dripwire", MODE_PRIVATE)
        binding.configInput.setText(prefs.getString(PREF_LAST_CONFIG, ""))

        DripwireVpnService.statusListener = { status -> runOnUiThread { render(status) } }

        binding.connectButton.setOnClickListener {
            if (connected) disconnect() else requestPermissionAndConnect()
        }
    }

    override fun onDestroy() {
        DripwireVpnService.statusListener = null
        super.onDestroy()
    }

    private fun requestPermissionAndConnect() {
        val raw = binding.configInput.text?.toString().orEmpty()
        if (raw.isBlank()) return
        try {
            ConfigTranslator.parse(raw) // validate before asking for VPN permission
        } catch (e: ConfigParseException) {
            binding.statusText.text = getString(R.string.error_bad_json, e.message ?: "")
            return
        }
        prefs.edit().putString(PREF_LAST_CONFIG, raw).apply()

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startTunnel()
        }
    }

    private fun startTunnel() {
        val raw = prefs.getString(PREF_LAST_CONFIG, null) ?: return
        binding.statusText.setText(R.string.status_starting)
        binding.connectButton.isEnabled = false
        val svcIntent = Intent(this, DripwireVpnService::class.java)
            .putExtra(DripwireVpnService.EXTRA_CONFIG_JSON, raw)
        startService(svcIntent)
    }

    private fun disconnect() {
        startService(Intent(this, DripwireVpnService::class.java).setAction(DripwireVpnService.ACTION_DISCONNECT))
    }

    private fun render(status: DripwireVpnService.Status) {
        binding.connectButton.isEnabled = true
        when (status) {
            is DripwireVpnService.Status.Idle -> {
                connected = false
                binding.statusText.setText(R.string.status_idle)
                binding.connectButton.setText(R.string.btn_connect)
            }
            is DripwireVpnService.Status.Connected -> {
                connected = true
                binding.statusText.text = getString(R.string.status_connected, status.remarks)
                binding.connectButton.setText(R.string.btn_disconnect)
            }
            is DripwireVpnService.Status.Error -> {
                connected = false
                binding.statusText.text = getString(R.string.status_error, status.message)
                binding.connectButton.setText(R.string.btn_connect)
            }
        }
    }

    companion object {
        private const val PREF_LAST_CONFIG = "last_config_json"
    }
}
