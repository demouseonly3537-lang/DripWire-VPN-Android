package cc.dripwire.vpn.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a complete Xray-core config.json from one [ProxyOutbound]: a plain, unauthenticated
 * SOCKS5 inbound on 127.0.0.1:[socksPort] feeding straight into that outbound. hev-socks5-tunnel
 * (the TUN-to-SOCKS5 relay, unchanged from the hysteria-only version of this app) talks to that
 * inbound; Xray does the actual vmess/vless/trojan/shadowsocks/socks protocol work from there to
 * the real server.
 *
 * Field names follow the standard V2Ray/Xray-core config schema — see
 * https://guide.v2fly.org (vmess/vless) and https://xtls.github.io (trojan/shadowsocks), which
 * this project's ConfigTranslator was checked against.
 */
object XrayConfigBuilder {

    fun build(outbound: ProxyOutbound, socksPort: Int): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
        root.put("inbounds", JSONArray().put(inbound))

        val out = JSONObject()
            .put("tag", "proxy")
            .put("protocol", outbound.protocol)
            .put("settings", buildSettings(outbound))
        buildStreamSettings(outbound)?.let { out.put("streamSettings", it) }

        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom")
        root.put("outbounds", JSONArray().put(out).put(direct))

        return root.toString(2)
    }

    private fun buildSettings(o: ProxyOutbound): JSONObject = when (o.protocol) {
        "vmess" -> JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", o.address)
                    .put("port", o.port)
                    .put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("id", requireField(o.uuid, "vmess user id"))
                                .put("alterId", o.alterId)
                                .put("security", o.vmessSecurity),
                        ),
                    ),
            ),
        )

        "vless" -> JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", o.address)
                    .put("port", o.port)
                    .put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("id", requireField(o.uuid, "vless user id"))
                                .put("encryption", "none")
                                .apply { o.flow?.let { put("flow", it) } },
                        ),
                    ),
            ),
        )

        "trojan" -> JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", o.address)
                    .put("port", o.port)
                    .put("password", requireField(o.password, "trojan password")),
            ),
        )

        "shadowsocks" -> JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", o.address)
                    .put("port", o.port)
                    .put("method", requireField(o.ssMethod, "shadowsocks cipher method"))
                    .put("password", requireField(o.password, "shadowsocks password")),
            ),
        )

        "socks" -> {
            val server = JSONObject().put("address", o.address).put("port", o.port)
            if (o.username != null) {
                server.put(
                    "users",
                    JSONArray().put(JSONObject().put("user", o.username).put("pass", o.password ?: "")),
                )
            }
            JSONObject().put("servers", JSONArray().put(server))
        }

        else -> throw ConfigParseException("Unsupported protocol for the Xray engine: ${o.protocol}")
    }

    private fun buildStreamSettings(o: ProxyOutbound): JSONObject? {
        if (o.network == "tcp" && o.security == "none") return null
        val s = JSONObject().put("network", o.network)

        when (o.network) {
            "ws" -> s.put(
                "wsSettings",
                JSONObject().apply {
                    put("path", o.wsPath ?: "/")
                    o.wsHost?.let { put("headers", JSONObject().put("Host", it)) }
                },
            )
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", o.grpcServiceName ?: ""))
        }

        when (o.security) {
            "tls" -> {
                s.put("security", "tls")
                s.put(
                    "tlsSettings",
                    JSONObject().apply {
                        put("serverName", o.sni ?: o.address)
                        put("allowInsecure", o.allowInsecure)
                        o.alpn?.let { put("alpn", JSONArray(it)) }
                        o.fingerprint?.let { put("fingerprint", it) }
                    },
                )
            }
            "reality" -> {
                s.put("security", "reality")
                s.put(
                    "realitySettings",
                    JSONObject().apply {
                        put("serverName", o.sni ?: o.address)
                        o.fingerprint?.let { put("fingerprint", it) }
                        put("publicKey", o.realityPublicKey ?: "")
                        o.realityShortId?.let { put("shortId", it) }
                        put("spiderX", o.realitySpiderX ?: "")
                    },
                )
            }
        }
        return s
    }

    private fun requireField(value: String?, what: String): String =
        value?.takeIf { it.isNotBlank() } ?: throw ConfigParseException("Missing $what")
}
