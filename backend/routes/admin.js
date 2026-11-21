'use strict';

const auth = require('../auth');
const { sendJson, randomKeyCode, now } = require('../util');

const SESSION_SECONDS = 12 * 60 * 60; // 12h admin session
const ONLINE_WINDOW_MS = 2 * 60 * 1000; // "online" badge if seen in the last 2 minutes

function requireAdmin(req, res, secret) {
  const header = req.headers['authorization'] || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  const payload = token ? auth.verify(token, secret) : null;
  if (!payload) {
    sendJson(res, 401, { ok: false, error: 'unauthorized' });
    return null;
  }
  return payload;
}

function keyStatusOf(row, t) {
  if (row.status === 'blocked') return 'blocked';
  if (row.expires_at != null && t > row.expires_at) return 'expired';
  if (row.activated_at == null) return 'unused';
  return 'active';
}

function keyView(db, row, t) {
  const deviceCount = db
    .prepare("SELECT COUNT(*) AS n FROM devices WHERE key_id = ? AND status = 'active'")
    .get(row.id).n;
  return {
    id: row.id,
    keyCode: row.key_code,
    configId: row.config_id,
    durationSeconds: row.duration_seconds,
    deviceLimit: row.device_limit,
    deviceCount,
    note: row.note,
    createdAt: row.created_at,
    activatedAt: row.activated_at,
    expiresAt: row.expires_at,
    status: keyStatusOf(row, t),
    rawStatus: row.status,
  };
}

/** Routes registered on the admin router. `ctx` = { db, env }. ctx.env.JWT_SECRET must be set. */
function register(router, ctx) {
  const { db, env } = ctx;

  router.post('/api/admin/login', async (req, res, body) => {
    const username = String(body.username || '');
    const password = String(body.password || '');
    const row = db.prepare('SELECT * FROM admins WHERE username = ?').get(username);
    if (!row || !auth.verifyPassword(password, row.password_hash)) {
      sendJson(res, 401, { ok: false, error: 'invalid_credentials' });
      return;
    }
    const token = auth.sign({ sub: row.id, username: row.username }, env.JWT_SECRET, SESSION_SECONDS);
    sendJson(res, 200, { ok: true, token, expiresIn: SESSION_SECONDS, username: row.username });
  });

  router.post('/api/admin/change-password', async (req, res, body) => {
    const admin = requireAdmin(req, res, env.JWT_SECRET);
    if (!admin) return;
    const oldPassword = String(body.oldPassword || '');
    const newPassword = String(body.newPassword || '');
    if (newPassword.length < 6) {
      sendJson(res, 400, { ok: false, error: 'new_password_too_short' });
      return;
    }
    const row = db.prepare('SELECT * FROM admins WHERE id = ?').get(admin.sub);
    if (!row || !auth.verifyPassword(oldPassword, row.password_hash)) {
      sendJson(res, 401, { ok: false, error: 'invalid_credentials' });
      return;
    }
    db.prepare('UPDATE admins SET password_hash = ? WHERE id = ?').run(
      auth.hashPassword(newPassword),
      row.id,
    );
    sendJson(res, 200, { ok: true });
  });

  router.get('/api/admin/stats', async (req, res) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const t = now();
    const totalKeys = db.prepare('SELECT COUNT(*) AS n FROM keys').get().n;
    const blockedKeys = db.prepare("SELECT COUNT(*) AS n FROM keys WHERE status = 'blocked'").get().n;
    const expiredKeys = db
      .prepare("SELECT COUNT(*) AS n FROM keys WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at < ?")
      .get(t).n;
    const unusedKeys = db
      .prepare("SELECT COUNT(*) AS n FROM keys WHERE status = 'active' AND activated_at IS NULL")
      .get().n;
    const activeKeys = totalKeys - blockedKeys - expiredKeys - unusedKeys;
    const totalDevices = db.prepare("SELECT COUNT(*) AS n FROM devices WHERE status = 'active'").get().n;
    const onlineDevices = db
      .prepare("SELECT COUNT(*) AS n FROM devices WHERE status = 'active' AND last_seen_at > ?")
      .get(t - ONLINE_WINDOW_MS).n;
    const totalConfigs = db.prepare('SELECT COUNT(*) AS n FROM configs').get().n;
    sendJson(res, 200, {
      ok: true,
      totalKeys,
      activeKeys,
      blockedKeys,
      expiredKeys,
      unusedKeys,
      totalDevices,
      onlineDevices,
      totalConfigs,
    });
  });

  // ---- Configs -------------------------------------------------------------------------

  router.get('/api/admin/configs', async (req, res) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const rows = db
      .prepare(
        `SELECT c.id, c.name, c.created_at, LENGTH(c.config_text) AS size,
                (SELECT COUNT(*) FROM keys k WHERE k.config_id = c.id) AS keyCount
         FROM configs c ORDER BY c.created_at DESC`,
      )
      .all();
    sendJson(res, 200, { ok: true, configs: rows });
  });

  router.post('/api/admin/configs', async (req, res, body) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const name = String(body.name || '').trim();
    const configText = String(body.configText || '').trim();
    if (!name) { sendJson(res, 400, { ok: false, error: 'name_required' }); return; }
    if (!configText) { sendJson(res, 400, { ok: false, error: 'config_required' }); return; }
    const info = db
      .prepare('INSERT INTO configs (name, config_text, created_at) VALUES (?, ?, ?)')
      .run(name, configText, now());
    sendJson(res, 200, { ok: true, id: Number(info.lastInsertRowid) });
  });

  router.delete('/api/admin/configs/:id', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const id = Number(params.id);
    const keyCount = db.prepare('SELECT COUNT(*) AS n FROM keys WHERE config_id = ?').get(id).n;
    if (keyCount > 0) {
      sendJson(res, 409, { ok: false, error: 'config_in_use', keyCount });
      return;
    }
    db.prepare('DELETE FROM configs WHERE id = ?').run(id);
    sendJson(res, 200, { ok: true });
  });

  // ---- Keys ------------------------------------------------------------------------------

  router.get('/api/admin/keys', async (req, res, body, params, query) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const t = now();
    let rows = db
      .prepare('SELECT k.*, c.name AS config_name FROM keys k JOIN configs c ON c.id = k.config_id ORDER BY k.created_at DESC')
      .all();
    const search = (query.search || '').trim().toLowerCase();
    if (search) {
      rows = rows.filter((r) => r.key_code.toLowerCase().includes(search) || (r.note || '').toLowerCase().includes(search));
    }
    const statusFilter = (query.status || '').trim();
    let views = rows.map((r) => ({ ...keyView(db, r, t), configName: r.config_name }));
    if (statusFilter) views = views.filter((v) => v.status === statusFilter);
    sendJson(res, 200, { ok: true, keys: views });
  });

  router.post('/api/admin/keys', async (req, res, body) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const configId = Number(body.configId);
    const durationSeconds = Number(body.durationSeconds);
    const deviceLimit = Number(body.deviceLimit);
    const quantity = Math.max(1, Math.min(100, Number(body.quantity) || 1));
    const note = body.note ? String(body.note).slice(0, 500) : null;

    if (!Number.isFinite(configId) || !db.prepare('SELECT id FROM configs WHERE id = ?').get(configId)) {
      sendJson(res, 400, { ok: false, error: 'invalid_config' });
      return;
    }
    if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) {
      sendJson(res, 400, { ok: false, error: 'invalid_duration' });
      return;
    }
    if (!Number.isFinite(deviceLimit) || deviceLimit <= 0) {
      sendJson(res, 400, { ok: false, error: 'invalid_device_limit' });
      return;
    }

    const insert = db.prepare(
      'INSERT INTO keys (key_code, config_id, duration_seconds, device_limit, status, note, created_at) ' +
        "VALUES (?, ?, ?, ?, 'active', ?, ?)",
    );
    const t = now();
    const created = [];
    for (let i = 0; i < quantity; i++) {
      let code;
      // Collisions are astronomically unlikely (15 random base32 chars) but guarded anyway.
      for (let attempt = 0; attempt < 5; attempt++) {
        code = randomKeyCode();
        if (!db.prepare('SELECT id FROM keys WHERE key_code = ?').get(code)) break;
        code = null;
      }
      if (!code) { sendJson(res, 500, { ok: false, error: 'key_generation_failed' }); return; }
      const info = insert.run(code, configId, durationSeconds, deviceLimit, note, t);
      created.push({ id: Number(info.lastInsertRowid), keyCode: code });
    }
    sendJson(res, 200, { ok: true, created });
  });

  router.get('/api/admin/keys/:id', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const id = Number(params.id);
    const row = db
      .prepare('SELECT k.*, c.name AS config_name FROM keys k JOIN configs c ON c.id = k.config_id WHERE k.id = ?')
      .get(id);
    if (!row) { sendJson(res, 404, { ok: false, error: 'not_found' }); return; }
    const devices = db
      .prepare('SELECT id, device_id, device_name, status, first_seen_at, last_seen_at, last_ping_ms FROM devices WHERE key_id = ? ORDER BY last_seen_at DESC')
      .all(id);
    const t = now();
    sendJson(res, 200, {
      ok: true,
      key: { ...keyView(db, row, t), configName: row.config_name },
      devices: devices.map((d) => ({
        id: d.id,
        deviceId: d.device_id,
        deviceName: d.device_name,
        status: d.status,
        firstSeenAt: d.first_seen_at,
        lastSeenAt: d.last_seen_at,
        lastPingMs: d.last_ping_ms,
        online: d.status === 'active' && d.last_seen_at > t - ONLINE_WINDOW_MS,
      })),
    });
  });

  function requireKey(req, res, params) {
    const row = db.prepare('SELECT * FROM keys WHERE id = ?').get(Number(params.id));
    if (!row) { sendJson(res, 404, { ok: false, error: 'not_found' }); return null; }
    return row;
  }

  router.post('/api/admin/keys/:id/block', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const row = requireKey(req, res, params);
    if (!row) return;
    db.prepare("UPDATE keys SET status = 'blocked' WHERE id = ?").run(row.id);
    sendJson(res, 200, { ok: true });
  });

  router.post('/api/admin/keys/:id/unblock', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const row = requireKey(req, res, params);
    if (!row) return;
    db.prepare("UPDATE keys SET status = 'active' WHERE id = ?").run(row.id);
    sendJson(res, 200, { ok: true });
  });

  router.post('/api/admin/keys/:id/reset', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const row = requireKey(req, res, params);
    if (!row) return;
    db.prepare('UPDATE keys SET activated_at = NULL, expires_at = NULL WHERE id = ?').run(row.id);
    db.prepare('DELETE FROM devices WHERE key_id = ?').run(row.id);
    sendJson(res, 200, { ok: true });
  });

  router.delete('/api/admin/keys/:id', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const row = requireKey(req, res, params);
    if (!row) return;
    db.prepare('DELETE FROM keys WHERE id = ?').run(row.id); // devices cascade
    sendJson(res, 200, { ok: true });
  });

  // ---- Devices -----------------------------------------------------------------------------

  router.post('/api/admin/devices/:id/kick', async (req, res, body, params) => {
    if (!requireAdmin(req, res, env.JWT_SECRET)) return;
    const id = Number(params.id);
    const row = db.prepare('SELECT * FROM devices WHERE id = ?').get(id);
    if (!row) { sendJson(res, 404, { ok: false, error: 'not_found' }); return; }
    db.prepare("UPDATE devices SET status = 'kicked' WHERE id = ?").run(id);
    sendJson(res, 200, { ok: true });
  });
}

module.exports = { register, requireAdmin };
