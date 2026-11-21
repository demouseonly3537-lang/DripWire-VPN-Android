'use strict';

const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');
const { hashPassword } = require('./auth');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, 'data', 'rakshakx.db');
fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });

const db = new DatabaseSync(DB_PATH);
db.exec('PRAGMA journal_mode = WAL;');
db.exec('PRAGMA foreign_keys = ON;');

db.exec(`
CREATE TABLE IF NOT EXISTS admins (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS configs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  config_text TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS keys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key_code TEXT NOT NULL UNIQUE,
  config_id INTEGER NOT NULL REFERENCES configs(id) ON DELETE RESTRICT,
  duration_seconds INTEGER NOT NULL,
  device_limit INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked')),
  note TEXT,
  created_at INTEGER NOT NULL,
  activated_at INTEGER,
  expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_keys_key_code ON keys(key_code);

CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key_id INTEGER NOT NULL REFERENCES keys(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  device_name TEXT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'kicked')),
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  last_ping_ms INTEGER,
  UNIQUE(key_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_devices_key_id ON devices(key_id);
`);

// Seed the single admin account from env on first boot. Deliberately fails loudly
// (instead of silently running with no admin, or a guessable default) if the env
// vars are missing and no admin exists yet — see README for how to set them.
const adminCount = db.prepare('SELECT COUNT(*) AS n FROM admins').get().n;
if (adminCount === 0) {
  const username = process.env.ADMIN_USERNAME;
  const password = process.env.ADMIN_PASSWORD;
  if (!username || !password) {
    console.error(
      '[rakshakx] No admin account exists yet, and ADMIN_USERNAME / ADMIN_PASSWORD are not ' +
        'set in the environment. Set them (see .env.example) and restart.',
    );
    process.exit(1);
  }
  db.prepare('INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)').run(
    username,
    hashPassword(password),
    Date.now(),
  );
  console.log(`[rakshakx] Created admin account "${username}".`);
}

module.exports = { db };
