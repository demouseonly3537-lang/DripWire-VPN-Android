# RakshakX VPN (Android)

An Android app that connects to a proxy server using a **license key** —
end users never see or paste any technical config. An admin manages servers
and keys from a separate web panel (see [`backend/`](backend/)); the app
only ever shows the person: connected or not, their device, time remaining
on their key, and the live ping.

```
┌─────────────┐  paste key   ┌──────────────────┐   HTTPS   ┌──────────────────────┐
│  RakshakX   │ ───────────► │ RakshakX backend │ ◄───────► │   Admin SaaS panel   │
│ Android app │ ◄─────────── │ (licensing API)  │           │ (generate/block keys,│
└─────────────┘  config +    └──────────────────┘           │  upload configs, see │
       │         expiry                                      │  devices per key)    │
       │ full device VPN tunnel                               └──────────────────────┘
       ▼
 your proxy server
```

- **End user**: opens the app, pastes a key (`RKX-XXXXX-XXXXX-XXXXX`), taps
  Connect. From then on they only ever see their device, time remaining, and
  ping — never the server address, protocol, or raw config.
- **Admin**: uses the web panel (see [`backend/README.md`](backend/README.md))
  to upload proxy configs, generate keys with a duration (1 hour / 1 day /
  30 days / a custom length) and a device limit, see which device(s) are
  using each key, block a key, or kick a specific device. A blocked or
  expired key — or a device an admin kicks — disconnects the app within one
  heartbeat interval (~15s), not just on next launch.

## Supported proxy configs (admin side only)

The admin panel accepts whatever config an end user previously could —
end users themselves never see or paste any of this:

| Format | Notes |
|---|---|
| dripwire/V2Ray/Xray-style JSON (`inbounds`/`outbounds`/`routing`) | Any outbound protocol: `hysteria`, `vmess`, `vless`, `trojan`, `shadowsocks`, `socks` |
| sing-box-style JSON (`outbounds` with a `"type"` field) | Same protocol list |
| Clash-style YAML (a `proxies:` list) | `shadowsocks`, `trojan`, `vless`, `vmess` (tcp/tls/ws), `hysteria2` |
| A single share link | `vmess://`, `vless://`, `trojan://`, `ss://`, `hysteria2://` / `hy2://` |

The app's format-detection/parsing code is unchanged from before — it's just
fed by the backend's `/api/v1/activate` response now, instead of a text box
the end user typed into.

## How the tunnel actually works

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

## The licensing backend (`backend/`)

A separate, zero-npm-dependency Node.js service (plain `node:http` +
`node:sqlite` + `node:crypto`) that the app and the admin panel both talk to.
See [`backend/README.md`](backend/README.md) for how to run/deploy it
(including Docker) and its full API reference. In short, the app calls:

- `POST /api/v1/activate` — turns a key into the real proxy config (once),
  starting that key's expiry clock on first use.
- `POST /api/v1/heartbeat` — called every ~15s while connected; any
  `{ok:false}` answer (expired/blocked/kicked) tears the tunnel down with a
  clear, plain-English reason shown in the app.

## Connecting the app to your backend

The app needs to know your backend's HTTPS base URL at **build time** (it's
baked into `BuildConfig.BACKEND_BASE_URL`, never user-editable — end users
only ever paste a key). Two ways to set it:

- **CI**: set a repository variable named `BACKEND_BASE_URL` (Settings →
  Secrets and variables → Actions → Variables) to your deployed backend's
  URL, e.g. `https://api.yourdomain.com`. The workflow picks it up
  automatically on the next build.
- **Local build**: pass `-PbackendBaseUrl=https://api.yourdomain.com` to
  Gradle.

Without either, the app builds against a placeholder URL — it will show a
"could not reach the server" error for any key, which is expected until a
real backend is deployed and configured.

## Why the build happens on GitHub, not on your machine

Building this needs the Android SDK/NDK, a Go toolchain, and source/binaries
fetched from three upstream projects. The included workflow,
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml), does
all of that on GitHub's own runners every time you push to `main` (or run it
manually from the **Actions** tab) and uploads the finished `.apk` as a build
artifact you can download from that run. A separate workflow,
[`.github/workflows/backend-ci.yml`](.github/workflows/backend-ci.yml),
smoke-tests the backend (API behaviour + the Docker image) on every push
that touches `backend/`.

To get your APK:
1. Push this project to your repo (or click **Run workflow** under
   **Actions → Build APK** if it's already there).
2. Wait for the run to go green (a few minutes — it builds Xray-core from
   source, builds the NDK library, and downloads the hysteria binaries).
3. Open the finished run → **Artifacts** → download `dripwire-vpn-debug-apk`.

It's a debug build (unsigned, installable with "allow unknown sources"). Ask
if you want a signed release build set up instead.

## Using the app (end user)

1. Install the APK, open it.
2. Paste your key (`RKX-XXXXX-XXXXX-XXXXX`, given by whoever manages your
   access) and tap **Connect**.
3. Grant the "VPN connection request" permission Android asks for (only
   asked once).
4. You'll see a card with your device, time remaining on the key, and the
   live ping — that's it. Any problem (wrong key, expired, blocked, too many
   devices already using this key, no internet) shows as one plain sentence
   above the key field.

## Known limitations (v1)

- **Full-tunnel only.** Per-port routing rules from the original
  dripwire-panel config format aren't replicated — this version routes *all*
  device traffic through the tunnel instead (the common "VPN mode"
  behaviour).
- **One server per key.** The admin panel currently attaches exactly one
  config to a key; there's no per-key picker across multiple outbounds yet.
- **Clash YAML coverage is basic**: plain TCP, TLS and WebSocket transport
  only. REALITY, gRPC transport, SIP003 plugins and multiplexing aren't read
  from Clash YAML (they are supported from the JSON formats above, except
  SIP003/multiplexing).
- No WireGuard, TUIC, or SSH outbound support yet.
- Debug build only; no code signing yet.
- The heartbeat interval is fixed at 15 seconds (not admin-configurable yet).

## Project layout

- `app/src/main/java/cc/dripwire/vpn/` — UI (`MainActivity`), format
  detection (`ConfigTranslator`), the `VpnService` (`DripwireVpnService`,
  which also runs the ping/heartbeat monitor loop).
- `app/src/main/java/cc/dripwire/vpn/license/` — `LicenseClient` (talks to
  the backend's `/api/v1/activate` + `/api/v1/heartbeat`) and
  `DeviceIdentity` (this device's persisted UUID + display name).
- `app/src/main/java/cc/dripwire/vpn/proxy/` — per-format parsers
  (`XrayStyleJsonParser`, `SingBoxStyleJsonParser`, `ShareLinkParser`,
  `ClashYamlParser`) and the Xray config builder (`XrayConfigBuilder`).
  Unchanged by the licensing rework — still fed a raw config string, now
  sourced from the backend instead of a user-typed text box.
- `app/src/main/java/hev/htproxy/TProxyService.kt` — JNI binding for the
  tun2socks library (package/class name is fixed by the native side, don't
  rename it).
- `app/src/main/jniLibs/` — **not committed**; filled in by CI before the
  Gradle build (see `.gitignore`).
- `backend/` — the licensing API + admin SaaS panel (separate Node.js
  service, see its own README).
- `.github/workflows/build-apk.yml` — builds/fetches the native pieces above
  and assembles the debug APK.
- `.github/workflows/backend-ci.yml` — smoke-tests the backend API and
  Docker image.
