package cc.dripwire.vpn

import org.json.JSONObject
import java.io.File

/**
 * Parses the JSON config pasted by the user (the dripwire panel export: an
 * `inbounds` / `outbounds` / `routing` document with a "hysteria" outbound)
 * and turns it into the two small config files the bundled native tools
 * actually understand:
 *
 *  - hysteria's own client YAML  (see https://v2.hysteria.network/docs/advanced/Full-Client-Config/)
 *  - hev-socks5-tunnel's YAML    (see https://github.com/heiher/hev-socks5-tunnel)
 *
 * v1 note: every route in the pasted config's `routing.rules` is honoured as
 * "this app proxies all device traffic through the dripwire server" — the
 * fine-grained per-port split (only ports 39000-39999 TCP / 10000-10999 UDP
 * via the proxy, everything else direct) described in that section is not
 * replicated yet. See README "Known limitations".
 */
data class ParsedDripwireConfig(
    val remarks: String,
    val socksListenPort: Int,
    val httpListenPort: Int?,
    val serverAddress: String,
    val serverPort: Int,
    val auth: String,
    val sni: String?,
    val alpn: List<String>,
)

class ConfigParseException(message: String) : Exception(message)

object ConfigTranslator {

    fun parse(rawJson: String): ParsedDripwireConfig {
        val root: JSONObject
        try {
            root = JSONObject(rawJson)
        } catch (e: Exception) {
            throw ConfigParseException("Not valid JSON: ${e.message}")
        }

        val remarks = root.optString("remarks", "dripwire")

        val inbounds = root.optJSONArray("inbounds")
            ?: throw ConfigParseException("Missing \"inbounds\" array")
        var socksPort: Int? = null
        var httpPort: Int? = null
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.getJSONObject(i)
            when (ib.optString("protocol")) {
                "socks" -> socksPort = ib.optInt("port")
                "http" -> httpPort = ib.optInt("port")
            }
        }
        val resolvedSocksPort = socksPort
            ?: throw ConfigParseException("No \"socks\" inbound found")

        val outbounds = root.optJSONArray("outbounds")
            ?: throw ConfigParseException("Missing \"outbounds\" array")
        var hysteria: JSONObject? = null
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            if (ob.optString("protocol") == "hysteria") {
                hysteria = ob
                break
            }
        }
        val ob = hysteria ?: throw ConfigParseException("No \"hysteria\" outbound found")

        val settings = ob.optJSONObject("settings")
            ?: throw ConfigParseException("hysteria outbound has no \"settings\"")
        val address = settings.optString("address").ifBlank {
            throw ConfigParseException("hysteria outbound has no server address")
        }
        val port = settings.optInt("port", 443)

        val stream = ob.optJSONObject("streamSettings")
        val hysteriaSettings = stream?.optJSONObject("hysteriaSettings")
        val auth = hysteriaSettings?.optString("auth")?.takeIf { it.isNotBlank() }
            ?: throw ConfigParseException("hysteria outbound has no auth token")

        val tls = stream?.optJSONObject("tlsSettings")
        val sni = tls?.optString("serverName")?.takeIf { it.isNotBlank() }
        val alpnArray = tls?.optJSONArray("alpn")
        val alpn = mutableListOf<String>()
        if (alpnArray != null) {
            for (i in 0 until alpnArray.length()) alpn.add(alpnArray.getString(i))
        }

        return ParsedDripwireConfig(
            remarks = remarks,
            socksListenPort = resolvedSocksPort,
            httpListenPort = httpPort,
            serverAddress = address,
            serverPort = port,
            auth = auth,
            sni = sni,
            alpn = alpn,
        )
    }

    /** Writes hysteria's client YAML to [outFile] and returns it. */
    fun writeHysteriaClientConfig(cfg: ParsedDripwireConfig, outFile: File): File {
        val sb = StringBuilder()
        sb.appendLine("server: \"${cfg.serverAddress}:${cfg.serverPort}\"")
        sb.appendLine("auth: \"${escapeYaml(cfg.auth)}\"")
        sb.appendLine("tls:")
        sb.appendLine("  sni: \"${escapeYaml(cfg.sni ?: cfg.serverAddress)}\"")
        sb.appendLine("  insecure: false")
        sb.appendLine("socks5:")
        sb.appendLine("  listen: \"127.0.0.1:${cfg.socksListenPort}\"")
        if (cfg.httpListenPort != null) {
            sb.appendLine("http:")
            sb.appendLine("  listen: \"127.0.0.1:${cfg.httpListenPort}\"")
        }
        outFile.writeText(sb.toString())
        return outFile
    }

    /** Writes hev-socks5-tunnel's YAML to [outFile] and returns it. */
    fun writeTunnelConfig(cfg: ParsedDripwireConfig, mtu: Int, outFile: File): File {
        val sb = StringBuilder()
        sb.appendLine("tunnel:")
        sb.appendLine("  mtu: $mtu")
        sb.appendLine("  ipv4: 198.18.0.1")
        sb.appendLine("socks5:")
        sb.appendLine("  address: '127.0.0.1'")
        sb.appendLine("  port: ${cfg.socksListenPort}")
        sb.appendLine("  udp: 'udp'")
        outFile.writeText(sb.toString())
        return outFile
    }

    private fun escapeYaml(s: String) = s.replace("\"", "\\\"")
}
