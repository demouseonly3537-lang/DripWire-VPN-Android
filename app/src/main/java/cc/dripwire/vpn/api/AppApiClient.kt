package cc.dripwire.vpn.api

import cc.dripwire.vpn.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the RakshakX v2 backend's new app-facing endpoints (messages, support chat, buy-key/
 * UPI, channel links) — everything added on top of the original /api/v1/activate + /api/v1/
 * heartbeat contract that [cc.dripwire.vpn.license.LicenseClient] already handles. Same
 * deliberately minimal style: HttpURLConnection + org.json, no extra HTTP dependency.
 *
 * Every call returns an [ApiResult] so the caller always has to handle "couldn't reach the
 * server" separately from "server answered ok:false" — the end user only ever sees a plain-
 * English sentence either way, never a raw error code or stack trace.
 */
object AppApiClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    // Image uploads are bigger (base64 of up to ~5MB of photo) and may simply take longer on a
    // slow mobile connection — give this one call more rope before calling it a network error.
    private const val UPLOAD_CONNECT_TIMEOUT_MS = 15_000
    private const val UPLOAD_READ_TIMEOUT_MS = 45_000

    sealed class ApiResult {
        data class Success(val json: JSONObject) : ApiResult()

        /** Could not reach the server at all (no internet, DNS failure, timeout, ...). */
        data class NetworkError(val message: String) : ApiResult()
    }

    // ---------- 2. Admin messages ----------

    fun getMessages(key: String, deviceId: String): ApiResult =
        safeGet("/api/v1/messages?" + query("key" to key, "deviceId" to deviceId))

    fun markMessageRead(id: Long, key: String, deviceId: String): ApiResult =
        safePost(
            "/api/v1/messages/$id/read",
            JSONObject().apply {
                put("key", key)
                put("deviceId", deviceId)
            },
        )

    // ---------- 3. Support chat ----------

    fun uploadSupportImage(
        key: String,
        deviceId: String,
        dataBase64: String,
        mime: String,
        filename: String,
    ): ApiResult = safePost(
        "/api/v1/support/upload",
        JSONObject().apply {
            put("key", key)
            put("deviceId", deviceId)
            put("dataBase64", dataBase64)
            put("mime", mime)
            put("filename", filename)
        },
        connectTimeoutMs = UPLOAD_CONNECT_TIMEOUT_MS,
        readTimeoutMs = UPLOAD_READ_TIMEOUT_MS,
    )

    fun sendSupportMessage(
        key: String,
        deviceId: String,
        deviceName: String,
        body: String?,
        imageId: Long?,
    ): ApiResult = safePost(
        "/api/v1/support/message",
        JSONObject().apply {
            put("key", key)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            if (!body.isNullOrBlank()) put("body", body)
            if (imageId != null) put("imageId", imageId)
        },
    )

    fun getSupportMessages(key: String, deviceId: String, sinceId: Long): ApiResult =
        safeGet(
            "/api/v1/support/messages?" + query(
                "key" to key,
                "deviceId" to deviceId,
                "sinceId" to sinceId.toString(),
            ),
        )

    // ---------- 4. Buy key (UPI) ----------

    fun getPlans(): ApiResult = safeGet("/api/v1/plans")

    fun createOrder(planId: Long, deviceId: String, deviceName: String): ApiResult =
        safePost(
            "/api/v1/orders",
            JSONObject().apply {
                put("planId", planId)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
            },
        )

    fun submitUtr(orderId: Long, deviceId: String, utr: String): ApiResult =
        safePost(
            "/api/v1/orders/$orderId/submit-utr",
            JSONObject().apply {
                put("deviceId", deviceId)
                put("utr", utr)
            },
        )

    fun getOrderStatus(orderId: Long, deviceId: String): ApiResult =
        safeGet("/api/v1/orders/$orderId?" + query("deviceId" to deviceId))

    // ---------- 5. Misc ----------

    fun getChannelLinks(): ApiResult = safeGet("/api/v1/channel-links")

    // ---------- plumbing ----------

    private fun query(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun safeGet(path: String): ApiResult = try {
        ApiResult.Success(request("GET", path, null, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS))
    } catch (e: IOException) {
        ApiResult.NetworkError(e.message ?: "network error")
    } catch (e: Exception) {
        ApiResult.NetworkError(e.message ?: "unexpected error")
    }

    private fun safePost(
        path: String,
        body: JSONObject,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): ApiResult = try {
        ApiResult.Success(request("POST", path, body, connectTimeoutMs, readTimeoutMs))
    } catch (e: IOException) {
        ApiResult.NetworkError(e.message ?: "network error")
    } catch (e: Exception) {
        ApiResult.NetworkError(e.message ?: "unexpected error")
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): JSONObject {
        val url = URL(BuildConfig.BACKEND_BASE_URL.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = conn.responseCode
            // The backend always answers with a JSON body, even for its own 4xx/5xx errors —
            // read whichever stream actually has it.
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (text.isNullOrBlank()) throw IOException("empty response (HTTP $status)")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
