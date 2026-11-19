# DripWire VPN (Android)

A small Android app that takes a proxy config — in any of several common
formats, see below — and uses it to open a device-wide VPN tunnel to that
server.

**Nothing is hard-coded.** You paste your config into the app itself at
runtime; your auth token/password is stored only in the app's private storage
on your own phone, never in this repo.

## Supported configs

| Format | Notes |
|---|---|
| dripwire/V2Ray/Xray-style JSON (`inbounds`/`outbounds`/`routing`) | Any outbound protocol in it: `hysteria`, `vmess`, `vless`, `trojan`, `shadowsocks`, `socks` |
| sing-box-style JSON (`outbounds` with a `"type"` field) | Same protocol list |
| Clash-style YAML (a `proxies:` list) | `shadowsocks`, `trojan`, `vless`, `vmess` (tcp/tls/ws), `hysteria2` |
| A single share link | `vmess://`, `vless://`, `trojan://`, `ss://`, `hysteria2://` / `hy2://` |

Paste whichever one you have — the app detects the format automatically. If
several links are pasted at once (one per line), only the first is used in
this version.

## How it actually works

```
Android TUN (VpnService)
        │  fd
        ▼
hev-socks5-tunnel (native lib, relays TUN traffic to a local SOCKS5)
        │  127.0.0.1:<local port>
        ▼
   one of two engines, picked by protocol:
        │
        ├─ hysteria client (official prebuilt binary)   — hysteria / hysteria2
        │        │  Hysteria2 / QUIC
        │
        └─ Xray-core (built from source in CI)          — vmess / vless / trojan
                 │  the protocol's own wire format           / shadowsocks / socks
        ▼
your proxy server
```

Existing, well-known open-source pieces do all the actual protocol and
tunnelling work — this app does not reimplement any of these from scratch:

- [apernet/hysteria](https://github.com/apernet/hysteria) — official
  Hysteria2 client. Its prebuilt Android binary is bundled and run as a
  subprocess, exposing a local SOCKS5 port.
- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — the engine behind
  v2rayNG and most other vmess/vless/trojan/shadowsocks clients. Built from
  source for Android in CI (a plain Go cross-build, no NDK needed for this
  one — see the workflow) and likewise run as a subprocess exposing a local
  SOCKS5 port.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) —
  the same tun2socks engine used by v2rayNG and other Android proxy clients.
  Built from source as a JNI library, it relays the VPN's TUN interface into
  whichever of the two local SOCKS5 ports above is active.

## Why the build happens on GitHub, not on your machine

Building this needs the Android SDK/NDK, a Go toolchain, and source/binaries
fetched from three upstream projects. The included workflow,
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml), does
all of that on GitHub's own runners every time you push to `main` (or run it
manually from the **Actions** tab) and uploads the finished `.apk` as a build
artifact you can download from that run.

To get your APK:
1. Push this project to your repo (or click **Run workflow** under
   **Actions → Build APK** if it's already there).
2. Wait for the run to go green (a few minutes — it builds Xray-core from
   source, builds the NDK library, and downloads the hysteria binaries).
3. Open the finished run → **Artifacts** → download `dripwire-vpn-debug-apk`.

It's a debug build (unsigned, installable with "allow unknown sources"). Ask
if you want a signed release build set up instead.

## Using the app

1. Install the APK, open it, paste your config into the box (see the table
   above for what's recognised).
2. Tap **Connect**, grant the "VPN connection request" permission Android
   asks for (only asked once).
3. The app detects the format, builds whichever small config file(s) the
   matching engine needs, and brings the tunnel up.

## Known limitations (v1)

- **Full-tunnel only.** The original dripwire config's `routing.rules`
  (only certain TCP/UDP port ranges go through the proxy, everything else
  direct) isn't replicated — this version routes *all* device traffic
  through the tunnel instead (the common "VPN mode" behaviour). Doable as a
  follow-up, just not in this first pass.
- **One server per paste.** If a config lists several outbounds/proxies, or
  several share links are pasted at once, only the first usable one is
  connected — there's no picker yet.
- **Clash YAML coverage is basic**: plain TCP, TLS and WebSocket transport
  only. REALITY, gRPC transport, SIP003 plugins and multiplexing aren't read
  from Clash YAML (they are supported from the JSON formats above, except
  SIP003/multiplexing).
- `finalmask.quicParams.disableChromeParrot` and other dripwire-panel-specific
  knobs aren't passed through — only the fields the official Hysteria2/Xray
  clients themselves understand are translated.
- No WireGuard, TUIC, or SSH outbound support yet.
- Debug build only; no code signing yet.

## Project layout

- `app/src/main/java/cc/dripwire/vpn/` — UI, format detection
  (`ConfigTranslator`), the `VpnService`.
- `app/src/main/java/cc/dripwire/vpn/proxy/` — per-format parsers
  (`XrayStyleJsonParser`, `SingBoxStyleJsonParser`, `ShareLinkParser`,
  `ClashYamlParser`) and the Xray config builder (`XrayConfigBuilder`).
- `app/src/main/java/hev/htproxy/TProxyService.kt` — JNI binding for the
  tun2socks library (package/class name is fixed by the native side, don't
  rename it).
- `app/src/main/jniLibs/` — **not committed**; filled in by CI before the
  Gradle build (see `.gitignore`).
- `.github/workflows/build-apk.yml` — builds/fetches the native pieces above
  and runs `./gradlew assembleDebug`.
