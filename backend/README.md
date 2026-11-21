# RakshakX backend

Licensing + admin API for the RakshakX VPN app. Generates and tracks license
keys, binds them to devices, and hands out the real proxy config only to a
device that presents a valid, unblocked, unexpired key — the Android app
never sees or stores the raw config.

Zero npm dependencies: it's built entirely on Node's built-in `http`,
`crypto` and `sqlite` modules, plus a zero-build vanilla-JS admin panel (no
React/Vite/Tailwind step) so the whole thing runs from `node server.js` with
nothing to install.

Requires **Node.js 22.5+** (for the built-in `node:sqlite` module).

## Quick start (local, no Docker)

```bash
cp .env.example .env
# edit .env: set ADMIN_USERNAME, ADMIN_PASSWORD, and a random JWT_SECRET
#   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
node server.js
```

Open `http://localhost:8787/` for the admin panel. Sign in with the
`ADMIN_USERNAME` / `ADMIN_PASSWORD` you set — that account is created
automatically the first time the server starts.

## Quick start (Docker)

```bash
cp .env.example .env   # same as above
docker compose up -d --build
```

The SQLite database lives in a named Docker volume (`rakshakx-data`), so it
survives `docker compose down` / upgrades. To back it up:

```bash
docker run --rm -v rakshakx-backend_rakshakx-data:/data -v "$PWD":/backup \
  alpine tar czf /backup/rakshakx-db-backup.tgz -C /data .
```

## Environment variables

| Variable         | Required | Description                                                              |
|-------------------|:--------:|---------------------------------------------------------------------------|
| `ADMIN_USERNAME`  | yes (first boot) | Username for the one admin account, created automatically on first run. |
| `ADMIN_PASSWORD`  | yes (first boot) | Password for that account. Only read if no admin exists yet.            |
| `JWT_SECRET`      | **yes**  | Long random string used to sign admin session tokens. Server refuses to start without it. |
| `PORT`            | no       | HTTP port to listen on. Default `8787`.                                  |
| `DB_PATH`         | no       | Path to the SQLite file. Default `./data/rakshakx.db` (`/app/data/rakshakx.db` in Docker). |

Changing the admin account later (once one already exists) is done from the
panel itself (change-password) — `ADMIN_USERNAME`/`ADMIN_PASSWORD` are only
consulted on a completely empty database.

## API

All responses are JSON. Admin endpoints require `Authorization: Bearer
<token>` from `/api/admin/login`. App-facing endpoints need no auth — a valid
key code is the credential.

### Admin (used by the admin panel)

- `POST /api/admin/login` — `{username, password}` → `{ok, token}`
- `POST /api/admin/change-password`
- `GET /api/admin/stats` — dashboard counters
- `GET /api/admin/configs`, `POST /api/admin/configs`, `DELETE /api/admin/configs/:id`
- `GET /api/admin/keys` (supports `?search=` and `?status=`), `POST /api/admin/keys` (bulk-generate, with `durationSeconds`, `deviceLimit`, `configId`, `quantity`, `note`)
- `GET /api/admin/keys/:id` — key detail + its devices
- `POST /api/admin/keys/:id/block`, `/unblock`, `/reset`, `DELETE /api/admin/keys/:id`
- `POST /api/admin/devices/:id/kick`

### App-facing (used by the Android app)

- `POST /api/v1/activate` — `{key, deviceId, deviceName}` → on success,
  `{ok: true, expiresAt, serverTime, deviceLabel, config}` where `config` is
  the raw proxy config text to feed into the app's existing parser. On
  failure, always HTTP 200 with `{ok: false, error}`, where `error` is one
  of `invalid_key`, `blocked`, `expired`, `device_limit`, `config_missing`,
  `missing_device_id`. The key's expiry clock starts on its *first* successful
  activation, not when it was generated.
- `POST /api/v1/heartbeat` — `{key, deviceId, pingMs}` → `{ok: true,
  expiresAt, serverTime}` while the key/device are still valid, or
  `{ok: false, error}` (`invalid_key` / `blocked` / `expired` /
  `device_removed`) the moment they aren't — the app should treat any
  `ok: false` heartbeat as "disconnect now and show why."

## Security notes

- The admin token is a hand-rolled HMAC-signed (HS256-style) JWT-alike — see
  `auth.js`. It is not a general-purpose JWT implementation; don't reuse it
  for anything beyond this server's own session tokens.
- Passwords are hashed with scrypt (`auth.js`), never stored or logged in
  plaintext.
- `/api/v1/activate` and `/api/admin/login` are rate-limited per IP
  (in-memory, fixed-window).
- Put this behind TLS (a reverse proxy like Caddy/nginx, or a platform that
  terminates TLS for you) before exposing it publicly — the server itself
  only speaks plain HTTP.
