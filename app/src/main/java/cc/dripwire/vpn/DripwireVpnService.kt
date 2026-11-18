package cc.dripwire.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Brings up the tunnel:
 *
 *   TUN (this service) --fd--> hev-socks5-tunnel --SOCKS5 127.0.0.1--> hysteria client
 *   process --Hysteria2/QUIC--> dripwire server
 *
 * Both native helpers are bundled as fake "lib*.so" files (see
 * app/build.gradle.kts packaging block and the CI workflow) purely so the
 * Android installer extracts them somewhere this app is allowed to execute
 * a binary from; libhysteria.so is run as a child process, while
 * libhev-socks5-tunnel.so is a real JNI library loaded in-process.
 */
class DripwireVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var hysteriaProcess: Process? = null
    private var tunnelRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val rawConfig = intent?.getStringExtra(EXTRA_CONFIG_JSON)
                if (rawConfig == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(DripwireApp.NOTIF_ID, buildNotification())
                Thread { connect(rawConfig) }.start()
            }
        }
        return START_STICKY
    }

    private fun connect(rawConfig: String) {
        try {
            val cfg = ConfigTranslator.parse(rawConfig)
            val mtu = 1500

            val hysteriaYaml = ConfigTranslator.writeHysteriaClientConfig(
                cfg, File(filesDir, "hysteria-client.yaml"),
            )
            val tunnelYaml = ConfigTranslator.writeTunnelConfig(
                cfg, mtu, File(filesDir, "tproxy.yaml"),
            )

            launchHysteria(hysteriaYaml)
            waitForPort("127.0.0.1", cfg.socksListenPort, timeoutMs = 8000)

            val pfd = Builder()
                .setSession(cfg.remarks)
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(mtu)
                .apply {
                    // Without this, the hysteria subprocess's own outbound
                    // connection would be captured by the 0.0.0.0/0 route
                    // above and loop back into the tunnel it is feeding.
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

            broadcastStatus(Status.Connected(cfg.remarks))
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            broadcastStatus(Status.Error(e.message ?: e.toString()))
            teardown()
            stopSelf()
        }
    }

    private fun launchHysteria(configFile: File) {
        val binary = File(applicationInfo.nativeLibraryDir, "libhysteria.so")
        if (!binary.exists()) {
            throw IllegalStateException(
                "libhysteria.so not found in ${applicationInfo.nativeLibraryDir} — " +
                    "this APK was not built by the project's CI workflow",
            )
        }
        val pb = ProcessBuilder(binary.absolutePath, "client", "-c", configFile.absolutePath)
        pb.redirectErrorStream(true)
        pb.directory(filesDir)
        val proc = pb.start()
        hysteriaProcess = proc
        Thread {
            proc.inputStream.bufferedReader().forEachLine { Log.d("hysteria", it) }
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
            "hysteria client never opened its local SOCKS5 port ($host:$port): $lastError",
        )
    }

    private fun teardown() {
        if (tunnelRunning) {
            try { TProxyService.TProxyStopService() } catch (e: Exception) { Log.w(TAG, e) }
            tunnelRunning = false
        }
        hysteriaProcess?.destroy()
        hysteriaProcess = null
        try { tunFd?.close() } catch (e: Exception) { Log.w(TAG, e) }
        tunFd = null
        broadcastStatus(Status.Idle)
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
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus(status: Status) {
        statusListener?.invoke(status)
    }

    sealed class Status {
        data object Idle : Status()
        data class Connected(val remarks: String) : Status()
        data class Error(val message: String) : Status()
    }

    companion object {
        private const val TAG = "DripwireVpnService"
        const val ACTION_DISCONNECT = "cc.dripwire.vpn.action.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "cc.dripwire.vpn.extra.CONFIG_JSON"

        /** Same-process status callback — this app has no separate :vpn process, so a plain
         * in-memory listener is enough; no need for LocalBroadcastManager / cross-process IPC. */
        @Volatile
        var statusListener: ((Status) -> Unit)? = null
    }
}
