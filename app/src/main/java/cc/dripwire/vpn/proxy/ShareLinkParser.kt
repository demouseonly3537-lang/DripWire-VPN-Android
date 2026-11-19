package cc.dripwire.vpn.proxy

import android.util.Base64
import cc.dripwire.vpn.ConfigTranslator
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Parses the single-line "share link" formats most proxy panels and apps export:
 * vmess://, vless://, trojan://, ss://, hysteria2:// (and its hy2:// alias).
 *
 * v1 limitation: if several links are pasted at once (one per line), only the first
 * non-blank line is used — see ConfigTranslator.
 */
object ShareLinkParser {

    fun parse(link: String): TunnelPlan = when {
        link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link)
        link.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(link, "vless")
        link.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(link, "trojan")
        link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link)
        link.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(link)
        link.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(link)
        else -> throw ConfigParseException("Unrecognised share link scheme")
    }

    // ---- vmess:// — base64-encoded JSON blob (the "v2rayN" format) ----------------------

    private fun parseVmess(link: String): TunnelPlan {
        val b64 = link.removePrefix("vmess://").removePrefix("VMESS://").substringBefore("#")
        val jsonStr = decodeBase64(b64)
        val j = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            throw ConfigParseException("vmess:// payload is not valid JSON: ${e.message}")
        }

        val net = j.optString("net", "tcp").ifBlank { "tcp" }
        val tlsField = j.optString("tls", "")
        val path = j.optString("path").takeIf { it.isNotBlank() }
        val host = j.optString("host").takeIf { it.isNotBlank() }

        val outbound = ProxyOutbound(
            protocol = "vmess",
            address = j.optString("add").takeIf { it.isNotBlank() }
                ?: throw ConfigParseException("vmess link missing \"add\" (server address)"),
            port = j.optString("port").toIntOrNull() ?: j.optInt("port", 443),
            uuid = j.optString("id").takeIf { it.isNotBlank() }
                ?: throw ConfigParseException("vmess link missing \"id\""),
            alterId = j.optString("aid").toIntOrNull() ?: j.optInt("aid", 0),
            vmessSecurity = j.optString("scy", "auto").ifBlank { "auto" },
            network = net,
            wsPath = path,
            wsHost = host,
            grpcServiceName = if (net == "grpc") path else null,
            security = if (tlsField == "tls" || tlsField == "reality") "tls" else "none",
            sni = j.optString("sni").takeIf { it.isNotBlank() } ?: host,
            alpn = j.optString("alpn").takeIf { it.isNotBlank() }?.split(","),
            fingerprint = j.optString("fp").takeIf { it.isNotBlank() },
        )
        val remarks = j.optString("ps", "vmess").ifBlank { "vmess" }
        return xrayPlan(remarks, outbound)
    }

    // ---- vless:// / trojan:// — URI form -------------------------------------------------

    private fun parseVlessOrTrojan(link: String, protocol: String): TunnelPlan {
        val uri = try {
            URI(link)
        } catch (e: Exception) {
            throw ConfigParseException("Could not parse $protocol link: ${e.message}")
        }
        val host = uri.host ?: throw ConfigParseException("$protocol link missing host")
        val port = if (uri.port > 0) uri.port else 443
        val userInfo = uri.userInfo ?: throw ConfigParseException("$protocol link missing credentials")
        val query = parseQuery(uri.query)
        val remarks = uri.fragment?.takeIf { it.isNotBlank() } ?: protocol

        val network = query["type"]?.takeIf { it.isNotBlank() } ?: "tcp"
        val security = query["security"]?.takeIf { it.isNotBlank() }
            ?: if (protocol == "trojan") "tls" else "none"

        val outbound = ProxyOutbound(
            protocol = protocol,
            address = host,
            port = port,
            uuid = if (protocol == "vless") userInfo else null,
            password = if (protocol == "trojan") userInfo else null,
            flow = query["flow"]?.takeIf { it.isNotBlank() },
            network = network,
            wsPath = query["path"],
            wsHost = query["host"],
            grpcServiceName = query["serviceName"] ?: (if (network == "grpc") query["path"] else null),
            security = security,
            sni = query["sni"]?.takeIf { it.isNotBlank() } ?: host,
            alpn = query["alpn"]?.split(","),
            fingerprint = query["fp"],
            allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1",
            realityPublicKey = query["pbk"],
            realityShortId = query["sid"],
            realitySpiderX = query["spx"],
        )
        return xrayPlan(remarks, outbound)
    }

    // ---- ss:// — SIP002 (ss://base64(method:pass)@host:port) or legacy fully-encoded form --

    private fun parseShadowsocks(link: String): TunnelPlan {
        var rest = link.removePrefix("ss://").removePrefix("SS://")
        val remarks = if (rest.contains("#")) {
            val r = rest.substringAfter("#")
            rest = rest.substringBefore("#")
            try { URLDecoder.decode(r, "UTF-8") } catch (e: Exception) { r }
        } else {
            "shadowsocks"
        }
        // A trailing "?plugin=..." (SIP003) isn't supported yet — dropped rather than misread.
        rest = rest.substringBefore("?")

        val methodPass: String
        val hostPort: String
        if (rest.contains("@")) {
            val userinfo = rest.substringBeforeLast("@")
            hostPort = rest.substringAfterLast("@")
            methodPass = try { decodeBase64(userinfo) } catch (e: Exception) { userinfo }
        } else {
            val decoded = decodeBase64(rest)
            methodPass = decoded.substringBeforeLast("@")
            hostPort = decoded.substringAfterLast("@")
        }

        val method = methodPass.substringBefore(":").takeIf { it.isNotBlank() }
            ?: throw ConfigParseException("ss:// link missing cipher method")
        val password = methodPass.substringAfter(":", "")
        val host = hostPort.substringBeforeLast(":").takeIf { it.isNotBlank() }
            ?: throw ConfigParseException("ss:// link missing host")
        val port = hostPort.substringAfterLast(":").toIntOrNull()
            ?: throw ConfigParseException("ss:// link missing/invalid port")

        val outbound = ProxyOutbound(
            protocol = "shadowsocks",
            address = host,
            port = port,
            ssMethod = method,
            password = password,
        )
        return xrayPlan(remarks, outbound)
    }

    // ---- hysteria2:// (alias hy2://) ------------------------------------------------------

    private fun parseHysteria2(link: String): TunnelPlan {
        val normalized = "hysteria2://" + link.substringAfter("://")
        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            throw ConfigParseException("Could not parse hysteria2 link: ${e.message}")
        }
        val host = uri.host ?: throw ConfigParseException("hysteria2 link missing host")
        val port = if (uri.port > 0) uri.port else 443
        val auth = uri.userInfo ?: throw ConfigParseException("hysteria2 link missing auth")
        val query = parseQuery(uri.query)
        val remarks = uri.fragment?.takeIf { it.isNotBlank() } ?: "hysteria2"

        val outbound = HysteriaOutbound(
            serverAddress = host,
            serverPort = port,
            auth = auth,
            sni = query["sni"]?.takeIf { it.isNotBlank() } ?: query["peer"] ?: host,
            obfs = query["obfs"]?.takeIf { it.isNotBlank() },
            obfsPassword = query["obfs-password"] ?: query["obfsParam"],
            allowInsecure = query["insecure"] == "1",
        )
        return TunnelPlan.ViaHysteria(remarks, ConfigTranslator.DEFAULT_SOCKS_PORT, outbound)
    }

    // ---- shared helpers --------------------------------------------------------------------

    private fun xrayPlan(remarks: String, outbound: ProxyOutbound): TunnelPlan.ViaXray {
        val port = ConfigTranslator.DEFAULT_SOCKS_PORT
        return TunnelPlan.ViaXray(remarks, port, XrayConfigBuilder.build(outbound, port))
    }

    private fun parseQuery(q: String?): Map<String, String> {
        if (q.isNullOrBlank()) return emptyMap()
        // URI already percent-decodes the query string as a whole when accessed this way.
        return q.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()
    }

    private fun decodeBase64(raw: String): String {
        var s = raw.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        s += "=".repeat(pad)
        val bytes = try {
            Base64.decode(s, Base64.DEFAULT)
        } catch (e: Exception) {
            throw ConfigParseException("Could not base64-decode share link: ${e.message}")
        }
        return String(bytes, Charsets.UTF_8)
    }
}
