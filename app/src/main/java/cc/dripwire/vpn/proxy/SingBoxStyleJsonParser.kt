package cc.dripwire.vpn.proxy

import org.json.JSONObject

/**
 * Handles one outbound object from sing-box's own native JSON schema, which (unlike the
 * Xray/V2Ray schema in [XrayStyleJsonParser]) uses a "type" field and flat top-level fields
 * (server/server_port/uuid/password/...) instead of nested settings/streamSettings objects.
 * Field names here follow sing-box's documented config reference.
 */
object SingBoxStyleJsonParser {

    fun build(ob: JSONObject, type: String, remarks: String, socksPort: Int): TunnelPlan {
        if (type == "hysteria" || type == "hysteria2") {
            return buildHysteriaPlan(ob, remarks, socksPort)
        }
        val outbound = buildProxyOutbound(ob, type)
        return TunnelPlan.ViaXray(remarks, socksPort, XrayConfigBuilder.build(outbound, socksPort))
    }

    private fun buildHysteriaPlan(ob: JSONObject, remarks: String, socksPort: Int): TunnelPlan.ViaHysteria {
        val address = ob.optString("server").ifBlank {
            throw ConfigParseException("hysteria2 outbound has no \"server\"")
        }
        val port = ob.optInt("server_port", 443)
        val auth = ob.optString("password").takeIf { it.isNotBlank() }
            ?: throw ConfigParseException("hysteria2 outbound has no \"password\"")

        val tls = ob.optJSONObject("tls")
        val sni = tls?.optString("server_name")?.takeIf { it.isNotBlank() }
        val obfsObj = ob.optJSONObject("obfs")

        return TunnelPlan.ViaHysteria(
            remarks,
            socksPort,
            HysteriaOutbound(
                serverAddress = address,
                serverPort = port,
                auth = auth,
                sni = sni,
                obfs = obfsObj?.optString("type")?.takeIf { it.isNotBlank() },
                obfsPassword = obfsObj?.optString("password"),
                allowInsecure = tls?.optBoolean("insecure", false) ?: false,
            ),
        )
    }

    private fun buildProxyOutbound(ob: JSONObject, type: String): ProxyOutbound {
        val tls = ob.optJSONObject("tls")
        val transport = ob.optJSONObject("transport")
        val reality = tls?.optJSONObject("reality")
        val realityEnabled = reality?.optBoolean("enabled", false) ?: false
        val tlsEnabled = tls?.optBoolean("enabled", false) ?: false
        val security = when {
            realityEnabled -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val alpn = tls?.optJSONArray("alpn")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }

        val base = ProxyOutbound(
            protocol = type,
            address = ob.optString("server").ifBlank {
                throw ConfigParseException("$type outbound has no \"server\"")
            },
            port = ob.optInt("server_port", 443),
            network = transport?.optString("type")?.takeIf { it.isNotBlank() } ?: "tcp",
            security = security,
            sni = tls?.optString("server_name")?.takeIf { it.isNotBlank() },
            alpn = alpn,
            fingerprint = tls?.optJSONObject("utls")?.optString("fingerprint")?.takeIf { it.isNotBlank() },
            allowInsecure = tls?.optBoolean("insecure", false) ?: false,
            wsPath = transport?.optString("path")?.takeIf { it.isNotBlank() },
            wsHost = transport?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() },
            grpcServiceName = transport?.optString("service_name")?.takeIf { it.isNotBlank() },
            realityPublicKey = reality?.optString("public_key")?.takeIf { it.isNotBlank() },
            realityShortId = reality?.optString("short_id")?.takeIf { it.isNotBlank() },
        )

        return when (type) {
            "vmess" -> base.copy(
                uuid = ob.optString("uuid").takeIf { it.isNotBlank() }
                    ?: throw ConfigParseException("vmess: missing uuid"),
                alterId = ob.optInt("alter_id", 0),
                vmessSecurity = ob.optString("security", "auto").ifBlank { "auto" },
            )
            "vless" -> base.copy(
                uuid = ob.optString("uuid").takeIf { it.isNotBlank() }
                    ?: throw ConfigParseException("vless: missing uuid"),
                flow = ob.optString("flow").takeIf { it.isNotBlank() },
            )
            "trojan" -> base.copy(
                password = ob.optString("password").takeIf { it.isNotBlank() }
                    ?: throw ConfigParseException("trojan: missing password"),
                security = if (base.security == "none") "tls" else base.security,
            )
            "shadowsocks" -> base.copy(
                ssMethod = ob.optString("method").takeIf { it.isNotBlank() }
                    ?: throw ConfigParseException("shadowsocks: missing method"),
                password = ob.optString("password").takeIf { it.isNotBlank() }
                    ?: throw ConfigParseException("shadowsocks: missing password"),
            )
            "socks" -> base.copy(
                username = ob.optString("username").takeIf { it.isNotBlank() },
                password = ob.optString("password").takeIf { it.isNotBlank() },
            )
            else -> throw ConfigParseException("Unsupported outbound type: $type")
        }
    }
}
