package cc.dripwire.vpn.proxy

import org.yaml.snakeyaml.Yaml

/**
 * Parses a Clash-style YAML config's "proxies:" list. Supports the common cases — plain
 * TCP, TLS, and WebSocket transport for vmess/vless/trojan, plus shadowsocks and
 * hysteria/hysteria2 — using the field names Clash.Meta (mihomo) and the original Clash
 * both use. Anything more exotic (REALITY, gRPC, SIP003 plugins, multiplexing) isn't
 * read here; such a proxy entry is skipped rather than connected half-configured.
 *
 * v1 limitation: connects the first proxy entry it understands, same as a pasted share
 * link — it does not let you pick from the list.
 */
object ClashYamlParser {

    @Suppress("UNCHECKED_CAST")
    fun parse(raw: String): TunnelPlan {
        val root = try {
            Yaml().load<Map<String, Any?>>(raw)
        } catch (e: Exception) {
            throw ConfigParseException("Could not parse as Clash YAML: ${e.message}")
        } ?: throw ConfigParseException("Empty YAML document")

        val proxies = root["proxies"] as? List<Map<String, Any?>>
            ?: throw ConfigParseException("No \"proxies\" list found in this YAML")
        if (proxies.isEmpty()) throw ConfigParseException("\"proxies\" list is empty")

        for (p in proxies) {
            tryBuild(p)?.let { return it }
        }
        throw ConfigParseException(
            "None of the proxies in this YAML use a protocol/transport this app understands " +
                "(supported: shadowsocks, trojan, vless, vmess over tcp/tls/ws; hysteria2).",
        )
    }

    private fun tryBuild(p: Map<String, Any?>): TunnelPlan? {
        val type = (p["type"] as? String)?.lowercase() ?: return null
        val name = (p["name"] as? String)?.takeIf { it.isNotBlank() } ?: type
        val server = p["server"] as? String ?: return null
        val port = toInt(p["port"]) ?: return null
        val socksPort = ConfigTranslator.DEFAULT_SOCKS_PORT
        val wsOpts = p["ws-opts"] as? Map<String, Any?>
        val wsPath = wsOpts?.get("path") as? String
        val wsHost = (wsOpts?.get("headers") as? Map<String, Any?>)?.get("Host") as? String
        val insecure = (p["skip-cert-verify"] as? Boolean) ?: false

        return when (type) {
            "hysteria2", "hysteria" -> {
                val auth = (p["password"] as? String) ?: (p["auth-str"] as? String) ?: return null
                TunnelPlan.ViaHysteria(
                    name,
                    socksPort,
                    HysteriaOutbound(
                        serverAddress = server,
                        serverPort = port,
                        auth = auth,
                        sni = (p["sni"] as? String)?.takeIf { it.isNotBlank() } ?: server,
                        obfs = p["obfs"] as? String,
                        obfsPassword = p["obfs-password"] as? String,
                        allowInsecure = insecure,
                    ),
                )
            }

            "ss", "shadowsocks" -> {
                val outbound = ProxyOutbound(
                    protocol = "shadowsocks",
                    address = server,
                    port = port,
                    ssMethod = (p["cipher"] as? String) ?: return null,
                    password = (p["password"] as? String) ?: return null,
                )
                TunnelPlan.ViaXray(name, socksPort, XrayConfigBuilder.build(outbound, socksPort))
            }

            "trojan" -> {
                val outbound = ProxyOutbound(
                    protocol = "trojan",
                    address = server,
                    port = port,
                    password = (p["password"] as? String) ?: return null,
                    security = "tls",
                    sni = (p["sni"] as? String)?.takeIf { it.isNotBlank() } ?: server,
                    allowInsecure = insecure,
                    network = if (p["network"] == "ws") "ws" else "tcp",
                    wsPath = wsPath,
                    wsHost = wsHost,
                )
                TunnelPlan.ViaXray(name, socksPort, XrayConfigBuilder.build(outbound, socksPort))
            }

            "vless" -> {
                val outbound = ProxyOutbound(
                    protocol = "vless",
                    address = server,
                    port = port,
                    uuid = (p["uuid"] as? String) ?: return null,
                    flow = p["flow"] as? String,
                    security = if ((p["tls"] as? Boolean) == true) "tls" else "none",
                    sni = (p["servername"] as? String)?.takeIf { it.isNotBlank() } ?: server,
                    allowInsecure = insecure,
                    network = (p["network"] as? String)?.takeIf { it.isNotBlank() } ?: "tcp",
                    wsPath = wsPath,
                    wsHost = wsHost,
                )
                TunnelPlan.ViaXray(name, socksPort, XrayConfigBuilder.build(outbound, socksPort))
            }

            "vmess" -> {
                val outbound = ProxyOutbound(
                    protocol = "vmess",
                    address = server,
                    port = port,
                    uuid = (p["uuid"] as? String) ?: return null,
                    alterId = toInt(p["alterId"]) ?: 0,
                    vmessSecurity = (p["cipher"] as? String)?.takeIf { it.isNotBlank() } ?: "auto",
                    security = if ((p["tls"] as? Boolean) == true) "tls" else "none",
                    sni = (p["servername"] as? String)?.takeIf { it.isNotBlank() } ?: server,
                    allowInsecure = insecure,
                    network = (p["network"] as? String)?.takeIf { it.isNotBlank() } ?: "tcp",
                    wsPath = wsPath,
                    wsHost = wsHost,
                )
                TunnelPlan.ViaXray(name, socksPort, XrayConfigBuilder.build(outbound, socksPort))
            }

            else -> null
        }
    }

    private fun toInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
