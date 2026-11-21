package cc.dripwire.vpn.proxy

/** Thrown whenever pasted config text can't be understood, with a message meant to be shown
 * to the user as-is (MainActivity does exactly that). */
class ConfigParseException(message: String) : Exception(message)

/**
 * One proxy server + credentials for a protocol handled by the bundled Xray-core binary
 * (vmess / vless / trojan / shadowsocks / socks). Independent of whatever format the config
 * was originally written in (dripwire/V2Ray-style JSON, sing-box JSON, a share link, or a
 * Clash YAML entry) — every parser in this package converts into this one shape, and
 * [XrayConfigBuilder] is the only place that turns it into an actual Xray config.json.
 */
data class ProxyOutbound(
    val protocol: String, // "vmess" | "vless" | "trojan" | "shadowsocks" | "socks"
    val address: String,
    val port: Int,
    // auth
    val uuid: String? = null, // vmess / vless
    val alterId: Int = 0, // vmess
    val vmessSecurity: String = "auto", // vmess "encryption" (named "security" in vmess configs)
    val flow: String? = null, // vless, e.g. "xtls-rprx-vision"
    val password: String? = null, // trojan / shadowsocks / socks
    val username: String? = null, // socks
    val ssMethod: String? = null, // shadowsocks cipher, e.g. "aes-256-gcm"
    // transport (streamSettings.network)
    val network: String = "tcp", // tcp | ws | grpc
    val wsPath: String? = null,
    val wsHost: String? = null,
    val grpcServiceName: String? = null,
    // tls / reality (streamSettings.security)
    val security: String = "none", // none | tls | reality
    val sni: String? = null,
    val alpn: List<String>? = null,
    val fingerprint: String? = null,
    val allowInsecure: Boolean = false,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realitySpiderX: String? = null,
)

/** One parsed Hysteria / Hysteria2 server. Handled by the bundled official hysteria binary,
 * not Xray-core — hysteria2's QUIC-based protocol is unrelated to the vmess/vless/trojan/ss
 * family above. */
data class HysteriaOutbound(
    val serverAddress: String,
    val serverPort: Int,
    val auth: String,
    val sni: String?,
    val obfs: String? = null, // e.g. "salamander"
    val obfsPassword: String? = null,
    val allowInsecure: Boolean = false,
)

/**
 * What DripwireVpnService actually needs to bring the tunnel up: which native engine to run
 * (it exposes a local SOCKS5 listener once started) and the port hev-socks5-tunnel should
 * relay the device's TUN traffic into.
 */
sealed class TunnelPlan {
    abstract val remarks: String
    abstract val socksPort: Int

    /** The real proxy server's address, so the UI can show a live ping to it (see
     * DripwireVpnService's ping prober) without needing to know which engine is in use. */
    abstract val remoteHost: String
    abstract val remotePort: Int

    data class ViaHysteria(
        override val remarks: String,
        override val socksPort: Int,
        val outbound: HysteriaOutbound,
    ) : TunnelPlan() {
        override val remoteHost: String get() = outbound.serverAddress
        override val remotePort: Int get() = outbound.serverPort
    }

    data class ViaXray(
        override val remarks: String,
        override val socksPort: Int,
        override val remoteHost: String,
        override val remotePort: Int,
        val configJson: String,
    ) : TunnelPlan()
}
