package cc.dripwire.vpn.proxy

import org.json.JSONObject

/**
 * Handles one outbound object from the V2Ray/Xray-style JSON schema — the format of the
 * original dripwire-panel config this app was first built for: top-level "inbounds" /
 * "outbounds" / "routing", each outbound shaped as {"protocol": ..., "settings": {...},
 * "streamSettings": {...}}. That schema allows any Xray outbound protocol, not just
 * "hysteria", so this parser also builds vmess/vless/trojan/shadowsocks/socks outbounds
 * for the Xray engine when it finds one.
 */
object XrayStyleJsonParser {

    fun build(ob: JSONObject, protocol: String, remarks: String, socksPort: Int): TunnelPlan {
        if (protocol == "hysteria" || protocol == "hysteria2") {
            return buildHysteriaPlan(ob, remarks, socksPort)
        }
        val outbound = buildProxyOutbound(ob, protocol)
        return TunnelPlan.ViaXray(
            remarks, socksPort, outbound.address, outbound.port, XrayConfigBuilder.build(outbound, socksPort),
        )
    }

    private fun buildHysteriaPlan(ob: JSONObject, remarks: String, socksPort: Int): TunnelPlan.ViaHysteria {
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

        return TunnelPlan.ViaHysteria(remarks, socksPort, HysteriaOutbound(address, port, auth, sni))
    }

    private fun buildProxyOutbound(ob: JSONObject, protocol: String): ProxyOutbound {
        val settings = ob.optJSONObject("settings")
            ?: throw ConfigParseException("$protocol outbound has no \"settings\"")
        val stream = ob.optJSONObject("streamSettings")
        val tls = stream?.optJSONObject("tlsSettings")
        val reality = stream?.optJSONObject("realitySettings")
        val alpn = tls?.optJSONArray("alpn")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }

        return when (protocol) {
            "vmess", "vless" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                    ?: throw ConfigParseException("$protocol outbound has no \"vnext\" server entry")
                val user = vnext.optJSONArray("users")?.optJSONObject(0)
                    ?: throw ConfigParseException("$protocol outbound has no user entry")
                ProxyOutbound(
                    protocol = protocol,
                    address = vnext.optString("address").ifBlank {
                        throw ConfigParseException("$protocol: missing address")
                    },
                    port = vnext.optInt("port", 443),
                    uuid = user.optString("id").takeIf { it.isNotBlank() }
                        ?: throw ConfigParseException("$protocol: missing user id"),
                    alterId = user.optInt("alterId", 0),
                    vmessSecurity = user.optString("security", "auto").ifBlank { "auto" },
                    flow = user.optString("flow").takeIf { it.isNotBlank() },
                    network = stream?.optString("network")?.takeIf { it.isNotBlank() } ?: "tcp",
                    security = stream?.optString("security")?.takeIf { it.isNotBlank() } ?: "none",
                    wsPath = stream?.optJSONObject("wsSettings")?.optString("path"),
                    wsHost = stream?.optJSONObject("wsSettings")?.optJSONObject("headers")?.optString("Host"),
                    grpcServiceName = stream?.optJSONObject("grpcSettings")?.optString("serviceName"),
                    sni = tls?.optString("serverName") ?: reality?.optString("serverName"),
                    alpn = alpn,
                    fingerprint = tls?.optString("fingerprint") ?: reality?.optString("fingerprint"),
                    allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false,
                    realityPublicKey = reality?.optString("publicKey"),
                    realityShortId = reality?.optString("shortId"),
                    realitySpiderX = reality?.optString("spiderX"),
                )
            }

            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                    ?: throw ConfigParseException("trojan outbound has no server entry")
                ProxyOutbound(
                    protocol = "trojan",
                    address = server.optString("address").ifBlank {
                        throw ConfigParseException("trojan: missing address")
                    },
                    port = server.optInt("port", 443),
                    password = server.optString("password").takeIf { it.isNotBlank() }
                        ?: throw ConfigParseException("trojan: missing password"),
                    network = stream?.optString("network")?.takeIf { it.isNotBlank() } ?: "tcp",
                    // trojan is TLS-by-convention even if streamSettings.security is omitted.
                    security = stream?.optString("security")?.takeIf { it.isNotBlank() } ?: "tls",
                    sni = tls?.optString("serverName"),
                    alpn = alpn,
                    allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false,
                    wsPath = stream?.optJSONObject("wsSettings")?.optString("path"),
                    wsHost = stream?.optJSONObject("wsSettings")?.optJSONObject("headers")?.optString("Host"),
                    grpcServiceName = stream?.optJSONObject("grpcSettings")?.optString("serviceName"),
                )
            }

            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                    ?: throw ConfigParseException("shadowsocks outbound has no server entry")
                ProxyOutbound(
                    protocol = "shadowsocks",
                    address = server.optString("address").ifBlank {
                        throw ConfigParseException("shadowsocks: missing address")
                    },
                    port = server.optInt("port", 8388),
                    ssMethod = server.optString("method").takeIf { it.isNotBlank() }
                        ?: throw ConfigParseException("shadowsocks: missing cipher method"),
                    password = server.optString("password").takeIf { it.isNotBlank() }
                        ?: throw ConfigParseException("shadowsocks: missing password"),
                )
            }

            "socks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                    ?: throw ConfigParseException("socks outbound has no server entry")
                val user = server.optJSONArray("users")?.optJSONObject(0)
                ProxyOutbound(
                    protocol = "socks",
                    address = server.optString("address").ifBlank {
                        throw ConfigParseException("socks: missing address")
                    },
                    port = server.optInt("port", 1080),
                    username = user?.optString("user")?.takeIf { it.isNotBlank() },
                    password = user?.optString("pass")?.takeIf { it.isNotBlank() },
                )
            }

            else -> throw ConfigParseException("Unsupported outbound protocol: $protocol")
        }
    }
}
