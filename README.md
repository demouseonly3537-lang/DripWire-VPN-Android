# DripWire VPN (Android)

A small Android app that takes a dripwire-style proxy config (the JSON you get
from the panel — `inbounds` / `outbounds` / `routing`, with a `hysteria`
outbound) and uses it to open a device-wide VPN tunnel to that server.

**Nothing is hard-coded.** You paste your config into the app itself at
runtime; your auth token is stored only in the app's private storage on your
own phone, never in this repo.

## How it actually works

```
Android TUN (VpnService)
        │  fd
        ▼
hev-socks5-tunnel (native lib, relays TUN traffic to a local SOCKS5)
        │  127.0.0.1:<socks port from your config>
        ▼
hysteria client (official prebuilt binary, run as a subprocess)
        │  Hysteria2 / QUIC
        ▼
your dripwire server
```

Two well-known, existing open-source pieces do the actual protocol work —
this app does not reimplement Hysteria2 or a TUN stack from scratch:

- [apernet/hysteria](https://github.com/apernet/hysteria) — official
  Hysteria2 client. Its prebuilt Android binary is bundled and run as a
  subprocess, listening on the SOCKS5 (and HTTP, if present) port your
  config specifies.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) —
  the same tun2socks engine used by v2rayNG and other Android proxy clients.
  Built from source as a JNI library, it relays the VPN's TUN interface to
  the local SOCKS5 port above.

## Why the build happens on GitHub, not on your machine

Building this needs the Android SDK/NDK plus two downloads (the hysteria
binaries and the hev-socks5-tunnel source). The included workflow,
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml), does
all of that on GitHub's own runners every time you push to `main` (or run it
manually from the **Actions** tab) and uploads the finished `.apk` as a build
artifact you can download from that run.

To get your APK:
1. Push this project to your repo (or click **Run workflow** under
   **Actions → Build APK** if it's already there).
2. Wait for the run to go green (a few minutes — it has to build the NDK
   library and download the hysteria binaries).
3. Open the finished run → **Artifacts** → download `dripwire-vpn-debug-apk`.

It's a debug build (unsigned, installable with "allow unknown sources"). Ask
if you want a signed release build set up instead.

## Using the app

1. Install the APK, open it, paste your full config JSON into the box.
2. Tap **Connect**, grant the "VPN connection request" permission Android
   asks for (only asked once).
3. The app parses the `socks`/`http` inbound ports and the `hysteria`
   outbound's server/auth/TLS fields out of your JSON, writes the two small
   config files the native pieces need, and brings the tunnel up.

## Known limitations (v1)

- **Full-tunnel, not the exact per-port split in your config.** Your
  pasted config's `routing.rules` only sends TCP 39000-39999 and UDP
  10000-10999 through the proxy and everything else direct. This version
  routes *all* device traffic through the tunnel instead (the common
  "VPN mode" behaviour). Replicating the exact port-range split would need
  packet-level rule matching inside the tun2socks layer — doable as a
  follow-up, just not in this first pass.
- `finalmask.quicParams.disableChromeParrot` and other dripwire-panel-specific
  knobs aren't passed through — only the fields the official Hysteria2
  client itself understands (server, auth, SNI, ALPN) are translated.
- Debug build only; no code signing yet.

## Project layout

- `app/src/main/java/cc/dripwire/vpn/` — UI, config parsing, the `VpnService`.
- `app/src/main/java/hev/htproxy/TProxyService.kt` — JNI binding for the
  tun2socks library (package/class name is fixed by the native side, don't
  rename it).
- `app/src/main/jniLibs/` — **not committed**; filled in by CI before the
  Gradle build (see `.gitignore`).
- `.github/workflows/build-apk.yml` — fetches/builds the native pieces above
  and runs `./gradlew assembleDebug`.
