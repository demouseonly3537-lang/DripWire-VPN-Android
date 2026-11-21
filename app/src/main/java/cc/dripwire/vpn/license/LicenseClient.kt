package cc.dripwire.vpn.license

import cc.dripwire.vpn.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the RakshakX licensing backend's app-facing endpoints (/api/v1/activate,
 * /api/v1/heartbeat — see backend/routes/client.js). Deliberately minimal: HttpURLConnection
 * + org.json (already used elsewhere in this app), no extra HTTP dependency needed.
 *
 * The end user never sees any of this directly — MainActivity turns these results into one
 * of a handful of plain-English states (connected / wrong key / expired / blocked / too many
 * devices / no internet), never a raw error code or stack trace.
 */
object LicenseClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    sealed class ActivateResult {
        /** [config] is the raw proxy config text — fed straight into the existing
         * ConfigTranslator.parse() pipeline, never shown to the user. */
        data class Success(
            val config: String,
            val expiresAt: Long?,
            val serverTime: Long,
            val deviceLabel: String,
        ) : ActivateResult()

        /** The server understood the request and said no — error is one of invalid_key,
         * blocked, expired, device_limit, config_missing, missing_device_id, rate_limited. */
        data class Failure(val error: String) : ActivateResult()

        /** Could not reach the server at all (no internet, DNS failure, timeout, ...). */
        data class NetworkError(val message: String) : ActivateResult()
    }

    sealed class HeartbeatResult {
        data class Success(val expiresAt: Long?, val serverTime: Long) : HeartbeatResult()

        /** error is one of invalid_key, blocked, expired, device_removed — all of these mean
         * "this device must stop using the tunnel now", per the user's auto-disconnect spec. */
        data class Failure(val error: String) : HeartbeatResult()

        data class NetworkError(val message: String) : HeartbeatResult()
    }

    fun activate(key: String, deviceId: String, deviceName: String): ActivateResult {
        val body = JSONObject().apply {
            put("key", key)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        }
        return try {
            val json = post("/api/v1/activate", body)
            if (json.optBoolean("ok")) {
                ActivateResult.Success(
                    config = json.getString("config"),
                    expiresAt = json.optLong("expiresAt", -1L).takeIf { it >= 0 },
                    serverTime = json.optLong("serverTime", System.currentTimeMillis()),
                    deviceLabel = json.optString("deviceLabel", deviceName),
                )
            } else {
                ActivateResult.Failure(json.optString("error", "unknown_error"))
            }
        } catch (e: IOException) {
            ActivateResult.NetworkError(e.message ?: "network error")
        } catch (e: Exception) {
            ActivateResult.NetworkError(e.message ?: "unexpected error")
        }
    }

    fun heartbeat(key: String, deviceId: String, pingMs: Int?): HeartbeatResult {
        val body = JSONObject().apply {
            put("key", key)
            put("deviceId", deviceId)
            if (pingMs != null) put("pingMs", pingMs)
        }
        return try {
            val json = post("/api/v1/heartbeat", body)
            if (json.optBoolean("ok")) {
                HeartbeatResult.Success(
                    expiresAt = json.optLong("expiresAt", -1L).takeIf { it >= 0 },
                    serverTime = json.optLong("serverTime", System.currentTimeMillis()),
                )
            } else {
                HeartbeatResult.Failure(json.optString("error", "unknown_error"))
            }
        } catch (e: IOException) {
            HeartbeatResult.NetworkError(e.message ?: "network error")
        } catch (e: Exception) {
            HeartbeatResult.NetworkError(e.message ?: "unexpected error")
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL(BuildConfig.BACKEND_BASE_URL.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val status = conn.responseCode
            // The backend always answers with a JSON body, even for its own 4xx/5xx errors
            // (see backend/server.js's catch-all handler) — read whichever stream has it.
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (text.isNullOrBlank()) throw IOException("empty response (HTTP $status)")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
