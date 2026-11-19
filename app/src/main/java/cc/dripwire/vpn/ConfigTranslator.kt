package cc.dripwire.vpn

import cc.dripwire.vpn.proxy.ClashYamlParser
import cc.dripwire.vpn.proxy.ConfigParseException
import cc.dripwire.vpn.proxy.HysteriaOutbound
import cc.dripwire.vpn.proxy.ShareLinkParser
import cc.dripwire.vpn.proxy.SingBoxStyleJsonParser
import cc.dripwire.vpn.proxy.TunnelPlan
import cc.dripwire.vpn.proxy.XrayStyleJsonParser
import org.json.JSONObject
import java.io.File

/**
 * Entry point for turning whatever the user pasted into a [TunnelPlan] DripwireVpnService can
 * act on. Recognises, in order:
 *
 *  1. A single share link: vmess:// / vless:// / trojan:// / ss:// / hysteria2:// (or hy2://).
 *     If several are pasted (one per line), only the first non-blank line is used.
 *  2. JSON with an "outbounds" array, in either of two shapes:
 *       - Xray/V2Ray-style: {"protocol": "...", "settings": {...}, "streamSettings": {...}}
 *         — the original dripwire-panel format this app started with, but any outbound
 *         protocol in it (not just "hysteria") is now honoured.
 *       - sing-box-style: {"type": "...", "server": ..., "server_port": ..., ...} (flat
 *         fields instead of nested settings/streamSettings).
 *  3. Clash-style YAML with a top-level "proxies:" list.
 *
 * Whichever format it came from, the result is one of two engines to run locally
 * (see TunnelPlan): the bundled hysteria binary, or the bundled Xray-core binary for
 * vmess/vless/trojan/shadowsocks/socks.
 */
object ConfigTranslator {

    /** Local SOCKS5 port used when the source config doesn't specify one of its own
     * (share links, sing-box JSON, and Clash YAML have no such concept). */
    const val DEFAULT_SOCKS_PORT = 10808

    private val SHARE_LINK_PREFIXES = listOf(
        "vmess://", "vless://", "trojan://", "ss://", "hysteria2://", "hy2://",
    )

    private val PASSTHROUGH_PROTOCOLS = setOf(
        "freedom", "direct", "blackhole", "block", "dns", "selector", "urltest",
    )

    fun parse(rawInput: String): TunnelPlan {
        val raw = rawInput.trim()
        if (raw.isEmpty()) throw ConfigParseException("Config is empty")

        val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: raw
        if (SHARE_LINK_PREFIXES.any { firstLine.startsWith(it, ignoreCase = true) }) {
            return ShareLinkParser.parse(firstLine)
        }

        if (raw.startsWith("{")) return parseJson(raw)

        if (Regex("(?m)^\\s*proxies\\s*:").containsMatchIn(raw)) return ClashYamlParser.parse(raw)

        throw ConfigParseException(
            "Could not recognise this config. Supported: a dripwire/V2Ray-style JSON, a " +
                "sing-box JSON, a Clash YAML (with a \"proxies:\" list), or a single " +
                "vmess:// / vless:// / trojan:// / ss:// / hysteria2:// link.",
        )
    }

    private fun parseJson(raw: String): TunnelPlan {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw ConfigParseException("Not valid JSON: ${e.message}")
        }
        val outbounds = root.optJSONArray("outbounds")
            ?: throw ConfigParseException("Missing \"outbounds\" array")
        if (outbounds.length() == 0) throw ConfigParseException("\"outbounds\" is empty")

        val remarks = root.optString("remarks").takeIf { it.isNotBlank() }
            ?: root.optString("tag").takeIf { it.isNotBlank() }
            ?: "dripwire"
        val socksPort = findInboundSocksPort(root) ?: DEFAULT_SOCKS_PORT

        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            val xrayProtocol = ob.optString("protocol").takeIf { it.isNotBlank() }
            if (xrayProtocol != null) {
                if (xrayProtocol in PASSTHROUGH_PROTOCOLS) continue
                return XrayStyleJsonParser.build(ob, xrayProtocol, remarks, socksPort)
            }
            val singBoxType = ob.optString("type").takeIf { it.isNotBlank() }
            if (singBoxType != null) {
                if (singBoxType in PASSTHROUGH_PROTOCOLS) continue
                return SingBoxStyleJsonParser.build(ob, singBoxType, remarks, socksPort)
            }
        }
        throw ConfigParseException(
            "No outbound with a supported protocol found (looked for hysteria, hysteria2, " +
                "vmess, vless, trojan, shadowsocks, socks).",
        )
    }

    private fun findInboundSocksPort(root: JSONObject): Int? {
        val inbounds = root.optJSONArray("inbounds") ?: return null
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.getJSONObject(i)
            val proto = ib.optString("protocol").takeIf { it.isNotBlank() } ?: ib.optString("type")
            if (proto == "socks") return ib.optInt("port", DEFAULT_SOCKS_PORT)
        }
        return null
    }

    /** Writes hysteria's client YAML — see https://v2.hysteria.network/docs/advanced/Full-Client-Config/ */
    fun writeHysteriaClientConfig(o: HysteriaOutbound, socksPort: Int, outFile: File): File {
        val sb = StringBuilder()
        sb.appendLine("server: \"${o.serverAddress}:${o.serverPort}\"")
        sb.appendLine("auth: \"${escapeYaml(o.auth)}\"")
        sb.appendLine("tls:")
        sb.appendLine("  sni: \"${escapeYaml(o.sni ?: o.serverAddress)}\"")
        sb.appendLine("  insecure: ${o.allowInsecure}")
        if (o.obfs != null) {
            sb.appendLine("obfs:")
            sb.appendLine("  type: \"${escapeYaml(o.obfs)}\"")
            sb.appendLine("  salamander:")
            sb.appendLine("    password: \"${escapeYaml(o.obfsPassword ?: "")}\"")
        }
        sb.appendLine("socks5:")
        sb.appendLine("  listen: \"127.0.0.1:$socksPort\"")
        outFile.writeText(sb.toString())
        return outFile
    }

    /** Writes hev-socks5-tunnel's YAML — see https://github.com/heiher/hev-socks5-tunnel */
    fun writeTunnelConfig(socksPort: Int, mtu: Int, outFile: File): File {
        val sb = StringBuilder()
        sb.appendLine("tunnel:")
        sb.appendLine("  mtu: $mtu")
        sb.appendLine("  ipv4: 198.18.0.1")
        sb.appendLine("socks5:")
        sb.appendLine("  address: '127.0.0.1'")
        sb.appendLine("  port: $socksPort")
        sb.appendLine("  udp: 'udp'")
        outFile.writeText(sb.toString())
        return outFile
    }

    private fun escapeYaml(s: String) = s.replace("\"", "\\\"")
}
