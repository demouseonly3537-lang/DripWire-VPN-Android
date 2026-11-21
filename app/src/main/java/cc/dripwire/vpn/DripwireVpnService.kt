package cc.dripwire.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import cc.dripwire.vpn.license.LicenseClient
import cc.dripwire.vpn.proxy.TunnelPlan
import hev.htproxy.TProxyService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Brings up the tunnel:
 *
 *   TUN (this service) --fd--> hev-socks5-tunnel --SOCKS5 127.0.0.1--> one of two engines:
 *       - the hysteria client binary (protocol: hysteria / hysteria2), or
 *       - the Xray-core binary (protocol: vmess / vless / trojan / shadowsocks / socks)
 *   --> the real proxy server
 *
 * Which engine runs is decided once per connection by ConfigTranslator, based on whatever
 * format/protocol the backend handed back for the user's key (see TunnelPlan). Both native
 * binaries are bundled as fake "lib*.so" files (see app/build.gradle.kts packaging block and
 * the CI workflow) purely so the Android installer extracts them somewhere this app is
 * allowed to execute a binary from; each is then run as a child process. hev-socks5-tunnel is
 * the one real JNI library here, loaded in-process.
 *
 * While connected, this service also runs a background "monitor loop": it periodically
 * measures the TCP round-trip to the real proxy server (for the ping the UI shows) and calls
 * the licensing backend's /api/v1/heartbeat so an admin-side block, device-kick, or key
 * expiry takes effect on this device within one heartbeat interval, not only on next launch.
 */
class DripwireVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var engineProcess: Process? = null
    private var tunnelRunning = false

    private var monitorThread: Thread? = null
    @Volatile private var monitorRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val rawConfig = intent?.getStringExtra(EXTRA_CONFIG_JSON)
                val key = intent?.getStringExtra(EXTRA_KEY)
                val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
                val deviceLabel = intent?.getStringExtra(EXTRA_DEVICE_LABEL)?.takeIf { it.isNotBlank() }
                    ?: deviceId.orEmpty()
                val expiresAt = (intent?.getLongExtra(EXTRA_EXPIRES_AT, -1L) ?: -1L).takeIf { it >= 0 }
                val serverTime = intent?.getLongExtra(EXTRA_SERVER_TIME, System.currentTimeMillis())
                    ?: System.currentTimeMillis()

                if (rawConfig == null || key == null || deviceId == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(DripwireApp.NOTIF_ID, buildNotification())
                Thread { connect(rawConfig, key, deviceId, deviceLabel, expiresAt, serverTime) }.start()
            }
        }
        return START_STICKY
    }

    private fun connect(
        rawConfig: String,
        key: String,
        deviceId: String,
        deviceLabel: String,
        expiresAt: Long?,
        serverTime: Long,
    ) {
        try {
            val plan = ConfigTranslator.parse(rawConfig)
            val mtu = 1500

            when (plan) {
                is TunnelPlan.ViaHysteria -> launchHysteria(
                    ConfigTranslator.writeHysteriaClientConfig(
                        plan.outbound, plan.socksPort, File(filesDir, "hysteria-client.yaml"),
                    ),
                )
                is TunnelPlan.ViaXray -> launchXray(plan.configJson)
            }
            waitForPort("127.0.0.1", plan.socksPort, timeoutMs = 8000)

            val tunnelYaml = ConfigTranslator.writeTunnelConfig(
                plan.socksPort, mtu, File(filesDir, "tproxy.yaml"),
            )

            val pfd = Builder()
                .setSession(plan.remarks)
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(mtu)
                .apply {
                    // Without this, the engine process's own outbound connection would be
                    // captured by the 0.0.0.0/0 route above and loop back into the tunnel
                    // it is feeding.
                    try {
                        addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "could not self-exclude from VPN routes", e)
                    }
                }
                .establish() ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
            tunFd = pfd

            val started = TProxyService.TProxyStartService(tunnelYaml.absolutePath, pfd.fd)
            if (!started) throw IllegalStateException("hev-socks5-tunnel refused to start")
            tunnelRunning = true

            broadcastStatus(Status.Connected(plan.remarks, deviceLabel, expiresAt, pingMs = null))
            startMonitorLoop(plan, key, deviceId, deviceLabel, expiresAt, serverTime)
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            broadcastStatus(Status.Error(getString(R.string.err_tunnel_failed, e.message ?: e.toString())))
            // broadcastIdle = false: teardown() must not immediately overwrite the Error status
            // we just sent with Idle — the user needs to actually see why it failed.
            teardown(broadcastIdle = false)
            stopSelf()
        }
    }

    /**
     * Runs for as long as the tunnel is up: every [HEARTBEAT_INTERVAL_MS], measures a live
     * ping to the real proxy server and reports it (plus this device's key) to the backend's
     * /api/v1/heartbeat. Any server-confirmed "this key/device is no longer valid" answer
     * (expired, blocked, kicked, ...) tears the tunnel down immediately with a clear reason.
     * A heartbeat that simply can't reach the server (no internet, DNS hiccup, timeout) is
     * treated as transient and retried next cycle — it must never disconnect the user just
     * because the licensing backend was briefly unreachable while the proxy itself is fine.
     * A locally-tracked expiry (adjusted for clock skew against the server's own clock) is
     * still enforced as a safety net even if heartbeats keep failing to reach the server.
     */
    private fun startMonitorLoop(
        plan: TunnelPlan,
        key: String,
        deviceId: String,
        deviceLabel: String,
        initialExpiresAt: Long?,
        initialServerTime: Long,
    ) {
        monitorRunning = true
        monitorThread = Thread {
            var expiresAt = initialExpiresAt
            var clockOffsetMs = initialServerTime - System.currentTimeMillis()
            // Keeps showing the last successful ping if one probe times out transiently,
            // instead of flickering back to "measuring…" — null only until the very first
            // successful measurement.
            var lastKnownPingMs: Int? = null

            while (monitorRunning) {
                measurePingMs(plan.remoteHost, plan.remotePort)?.let { lastKnownPingMs = it }
                broadcastStatus(Status.Connected(plan.remarks, deviceLabel, expiresAt, lastKnownPingMs))

                val adjustedNow = System.currentTimeMillis() + clockOffsetMs
                if (expiresAt != null && adjustedNow >= expiresAt) {
                    Log.i(TAG, "key expired (local safety-net check) — disconnecting")
                    broadcastStatus(Status.Error(getString(R.string.err_key_expired)))
                    monitorRunning = false
                    teardown(broadcastIdle = false)
                    stopSelf()
                    break
                }

                when (val result = LicenseClient.heartbeat(key, deviceId, lastKnownPingMs)) {
                    is LicenseClient.HeartbeatResult.Success -> {
                        expiresAt = result.expiresAt ?: expiresAt
                        clockOffsetMs = result.serverTime - System.currentTimeMillis()
                    }
                    is LicenseClient.HeartbeatResult.Failure -> {
                        Log.i(TAG, "heartbeat rejected by server: ${result.error} — disconnecting")
                        broadcastStatus(Status.Error(errorMessageFor(result.error)))
                        monitorRunning = false
                        teardown(broadcastIdle = false)
                        stopSelf()
                    }
                    is LicenseClient.HeartbeatResult.NetworkError -> {
                        Log.w(TAG, "heartbeat network error (treated as transient): ${result.message}")
                    }
                }

                if (!monitorRunning) break
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopMonitorLoop() {
        monitorRunning = false
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun measurePingMs(host: String, port: Int): Int? {
        return try {
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS) }
            ((System.nanoTime() - start) / 1_000_000L).toInt()
        } catch (e: Exception) {
            null
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

    private fun launchHysteria(configFile: File) {
        launchEngine("libhysteria.so", listOf("client", "-c", configFile.absolutePath), "hysteria")
    }

    private fun launchXray(configJson: String) {
        val configFile = File(filesDir, "xray-config.json").apply { writeText(configJson) }
        launchEngine("libxray.so", listOf("run", "-c", configFile.absolutePath), "xray")
    }

    private fun launchEngine(libraryFileName: String, args: List<String>, logTag: String) {
        val binary = File(applicationInfo.nativeLibraryDir, libraryFileName)
        if (!binary.exists()) {
            throw IllegalStateException(
                "$libraryFileName not found in ${applicationInfo.nativeLibraryDir} — " +
                    "this APK was not built by the project's CI workflow",
            )
        }
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args)
        pb.redirectErrorStream(true)
        pb.directory(filesDir)
        val proc = pb.start()
        engineProcess = proc
        Thread {
            proc.inputStream.bufferedReader().forEachLine { Log.d(logTag, it) }
        }.apply { isDaemon = true }.start()
    }

    private fun waitForPort(host: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 500) }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(200)
            }
        }
        throw IllegalStateException(
            "The proxy engine never opened its local SOCKS5 port ($host:$port): $lastError",
        )
    }

    /** [broadcastIdle] is false when the caller already broadcast a Status.Error that should
     * remain the visible state — otherwise this would immediately overwrite it with Idle and
     * the user would never actually see why the connection failed. */
    private fun teardown(broadcastIdle: Boolean = true) {
        stopMonitorLoop()
        if (tunnelRunning) {
            try { TProxyService.TProxyStopService() } catch (e: Exception) { Log.w(TAG, e) }
            tunnelRunning = false
        }
        engineProcess?.destroy()
        engineProcess = null
        try { tunFd?.close() } catch (e: Exception) { Log.w(TAG, e) }
        tunFd = null
        if (broadcastIdle) broadcastStatus(Status.Idle)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DripwireApp.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus(status: Status) {
        statusListener?.invoke(status)
    }

    sealed class Status {
        data object Idle : Status()

        /** [pingMs] is null only until the first successful measurement after connecting (the
         * UI shows "measuring…" for that case) — once a value exists it keeps showing the last
         * known-good ping even if a later probe times out transiently. */
        data class Connected(
            val remarks: String,
            val deviceLabel: String,
            val expiresAt: Long?,
            val pingMs: Int?,
        ) : Status()

        /** [message] is already a plain-English, user-facing sentence — never a raw error
         * code or stack trace (see errorMessageFor above). */
        data class Error(val message: String) : Status()
    }

    companion object {
        private const val TAG = "DripwireVpnService"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val PING_TIMEOUT_MS = 3_000

        const val ACTION_DISCONNECT = "cc.dripwire.vpn.action.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "cc.dripwire.vpn.extra.CONFIG_JSON"
        const val EXTRA_KEY = "cc.dripwire.vpn.extra.KEY"
        const val EXTRA_DEVICE_ID = "cc.dripwire.vpn.extra.DEVICE_ID"
        const val EXTRA_DEVICE_LABEL = "cc.dripwire.vpn.extra.DEVICE_LABEL"
        const val EXTRA_EXPIRES_AT = "cc.dripwire.vpn.extra.EXPIRES_AT"
        const val EXTRA_SERVER_TIME = "cc.dripwire.vpn.extra.SERVER_TIME"

        /** Same-process status callback — this app has no separate :vpn process, so a plain
         * in-memory listener is enough; no need for LocalBroadcastManager / cross-process IPC. */
        @Volatile
        var statusListener: ((Status) -> Unit)? = null
    }
}
