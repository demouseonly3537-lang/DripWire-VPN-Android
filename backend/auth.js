'use strict';

/** Password hashing (scrypt, built into Node's crypto — no bcrypt dependency) and a
 * minimal hand-rolled JWT-alike (HS256-style: base64url(header).base64url(payload).
 * base64url(HMAC-SHA256)). Good enough for a single admin-panel session token; not
 * meant to be a general-purpose JWT implementation. */

const crypto = require('crypto');

const SCRYPT_KEYLEN = 64;

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, SCRYPT_KEYLEN);
  return `${salt.toString('hex')}:${hash.toString('hex')}`;
}

function verifyPassword(password, stored) {
  const parts = typeof stored === 'string' ? stored.split(':') : null;
  if (!parts || parts.length !== 2) return false;
  const [saltHex, hashHex] = parts;
  try {
    const salt = Buffer.from(saltHex, 'hex');
    const expected = Buffer.from(hashHex, 'hex');
    const actual = crypto.scryptSync(password, salt, SCRYPT_KEYLEN);
    if (actual.length !== expected.length) return false;
    return crypto.timingSafeEqual(actual, expected);
  } catch (e) {
    return false;
  }
}

function b64url(input) {
  return Buffer.from(input).toString('base64url');
}

function sign(payload, secret, expiresInSec) {
  const header = { alg: 'HS256', typ: 'JWT' };
  const body = { ...payload, iat: Math.floor(Date.now() / 1000) };
  if (expiresInSec) body.exp = body.iat + expiresInSec;
  const headerPart = b64url(JSON.stringify(header));
  const payloadPart = b64url(JSON.stringify(body));
  const data = `${headerPart}.${payloadPart}`;
  const sig = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  return `${data}.${sig}`;
}

function verify(token, secret) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [headerPart, payloadPart, sig] = parts;
  const data = `${headerPart}.${payloadPart}`;
  const expectedSig = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  const sigBuf = Buffer.from(sig);
  const expectedBuf = Buffer.from(expectedSig);
  if (sigBuf.length !== expectedBuf.length || !crypto.timingSafeEqual(sigBuf, expectedBuf)) {
    return null;
  }
  let payload;
  try {
    payload = JSON.parse(Buffer.from(payloadPart, 'base64url').toString('utf8'));
  } catch (e) {
    return null;
  }
  if (typeof payload.exp === 'number' && Math.floor(Date.now() / 1000) > payload.exp) return null;
  return payload;
}

module.exports = { hashPassword, verifyPassword, sign, verify };
