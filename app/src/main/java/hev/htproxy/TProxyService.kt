package hev.htproxy

/**
 * JNI binding for the hev-socks5-tunnel native library
 * (https://github.com/heiher/hev-socks5-tunnel). The package/class name
 * MUST stay exactly "hev.htproxy.TProxyService" — that is the default
 * symbol name the library's native code exports when built from source
 * without overriding APP_CFLAGS PKGNAME/CLSNAME (see
 * .github/workflows/build-apk.yml, which builds libhev-socks5-tunnel.so
 * with ndk-build before this app is compiled).
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Starts relaying the tun [fd] to the SOCKS5 proxy described in the YAML at [configPath]. */
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    /** [bytes_in, bytes_out] or similar — see upstream docs; not relied on here. */
    external fun TProxyGetStats(): LongArray
}
