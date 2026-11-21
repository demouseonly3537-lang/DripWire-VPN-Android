'use strict';

const crypto = require('crypto');

const MAX_BODY_BYTES = 1024 * 1024; // 1 MiB — plenty for a config/key payload, cheap DoS guard.

/** Crockford base32: excludes 0/O/1/I/L so a key is unambiguous when typed or read aloud. */
const KEY_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request body too large'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (e) {
        reject(Object.assign(new Error('Invalid JSON body'), { statusCode: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function randomKeyCode() {
  const groups = [];
  for (let g = 0; g < 3; g++) {
    let s = '';
    const bytes = crypto.randomBytes(5);
    for (let i = 0; i < 5; i++) {
      s += KEY_ALPHABET[bytes[i] % KEY_ALPHABET.length];
    }
    groups.push(s);
  }
  return `RKX-${groups.join('-')}`;
}

function now() {
  return Date.now();
}

/** Simple in-memory fixed-window rate limiter: `limit` requests per `windowMs` per key
 * (caller supplies the key, usually the client IP). Good enough for a single-process
 * deployment; intentionally has no external dependency. */
function makeRateLimiter(limit, windowMs) {
  const hits = new Map();
  return function allow(key) {
    const t = now();
    const entry = hits.get(key);
    if (!entry || t > entry.resetAt) {
      hits.set(key, { count: 1, resetAt: t + windowMs });
      return true;
    }
    if (entry.count >= limit) return false;
    entry.count += 1;
    return true;
  };
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd.length > 0) return fwd.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

module.exports = { sendJson, readJsonBody, randomKeyCode, now, makeRateLimiter, clientIp };
