'use strict';

const { sendJson, now } = require('../util');

/**
 * App-facing endpoints. No admin auth here — a valid key code is the credential.
 * The raw proxy config is only ever returned from /activate, to a device that just
 * passed all the checks below; it is never exposed any other way.
 */
function register(router, ctx) {
  const { db } = ctx;

  function loadKey(keyCode) {
    return db.prepare('SELECT * FROM keys WHERE key_code = ?').get(String(keyCode || '').trim());
  }

  /** Returns an error string if the key can't be used right now, else null.
   * Does NOT check device binding — callers decide what to do about that themselves. */
  function keyUsabilityError(row, t) {
    if (!row) return 'invalid_key';
    if (row.status === 'blocked') return 'blocked';
    if (row.expires_at != null && t > row.expires_at) return 'expired';
    return null;
  }

  router.post('/api/v1/activate', async (req, res, body) => {
    const t = now();
    const deviceId = String(body.deviceId || '').trim();
    const deviceName = String(body.deviceName || 'Unknown device').slice(0, 200);
    if (!deviceId) { sendJson(res, 400, { ok: false, error: 'missing_device_id' }); return; }

    let row = loadKey(body.key);
    const usability = keyUsabilityError(row, t);
    if (usability) { sendJson(res, 200, { ok: false, error: usability }); return; }

    // First-ever use of this key starts its expiry clock now.
    if (row.activated_at == null) {
      const expiresAt = t + row.duration_seconds * 1000;
      db.prepare('UPDATE keys SET activated_at = ?, expires_at = ? WHERE id = ?').run(t, expiresAt, row.id);
      row = db.prepare('SELECT * FROM keys WHERE id = ?').get(row.id);
    }

    const existingDevice = db
      .prepare('SELECT * FROM devices WHERE key_id = ? AND device_id = ?')
      .get(row.id, deviceId);

    if (existingDevice) {
      if (existingDevice.status === 'kicked') {
        // Re-admit a previously kicked device as if it were new: still subject to the
        // device-limit check below, so count it as one of the "other" active devices.
      } else {
        db.prepare('UPDATE devices SET last_seen_at = ?, device_name = ? WHERE id = ?').run(
          t, deviceName, existingDevice.id,
        );
      }
    }

    if (!existingDevice || existingDevice.status === 'kicked') {
      const activeCount = db
        .prepare("SELECT COUNT(*) AS n FROM devices WHERE key_id = ? AND status = 'active'")
        .get(row.id).n;
      if (activeCount >= row.device_limit) {
        sendJson(res, 200, { ok: false, error: 'device_limit' });
        return;
      }
      if (existingDevice) {
        db.prepare("UPDATE devices SET status = 'active', last_seen_at = ?, device_name = ? WHERE id = ?").run(
          t, deviceName, existingDevice.id,
        );
      } else {
        db.prepare(
          'INSERT INTO devices (key_id, device_id, device_name, status, first_seen_at, last_seen_at) ' +
            "VALUES (?, ?, ?, 'active', ?, ?)",
        ).run(row.id, deviceId, deviceName, t, t);
      }
    }

    const config = db.prepare('SELECT config_text FROM configs WHERE id = ?').get(row.config_id);
    if (!config) {
      // The config this key points to was deleted after the key was created — tell the
      // truth rather than handing back nothing and leaving the app to guess why.
      sendJson(res, 200, { ok: false, error: 'config_missing' });
      return;
    }

    sendJson(res, 200, {
      ok: true,
      expiresAt: row.expires_at,
      serverTime: t,
      deviceLabel: deviceName,
      config: config.config_text,
    });
  });

  router.post('/api/v1/heartbeat', async (req, res, body) => {
    const t = now();
    const deviceId = String(body.deviceId || '').trim();
    const pingMs = Number.isFinite(Number(body.pingMs)) ? Math.round(Number(body.pingMs)) : null;

    const row = loadKey(body.key);
    const usability = keyUsabilityError(row, t);
    if (usability) { sendJson(res, 200, { ok: false, error: usability }); return; }

    const device = db
      .prepare('SELECT * FROM devices WHERE key_id = ? AND device_id = ?')
      .get(row.id, deviceId);
    if (!device || device.status !== 'active') {
      sendJson(res, 200, { ok: false, error: 'device_removed' });
      return;
    }

    db.prepare('UPDATE devices SET last_seen_at = ?, last_ping_ms = ? WHERE id = ?').run(
      t, pingMs, device.id,
    );
    sendJson(res, 200, { ok: true, expiresAt: row.expires_at, serverTime: t });
  });
}

module.exports = { register };
