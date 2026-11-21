'use strict';

const http = require('node:http');
const url = require('node:url');
const path = require('node:path');
const fs = require('node:fs');

function loadEnvFile() {
  const envPath = path.join(__dirname, '.env');
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const idx = trimmed.indexOf('=');
    if (idx < 0) continue;
    const key = trimmed.slice(0, idx).trim();
    let value = trimmed.slice(idx + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = value;
  }
}
loadEnvFile();

const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  console.error('[rakshakx] JWT_SECRET is not set. Copy .env.example to .env, fill it in, and restart.');
  process.exit(1);
}

const { db } = require('./db'); // exits early itself if ADMIN_USERNAME/ADMIN_PASSWORD are missing
const { sendJson, readJsonBody, makeRateLimiter, clientIp } = require('./util');
const adminRoutes = require('./routes/admin');
const clientRoutes = require('./routes/client');

class Router {
  constructor() {
    this.routes = [];
  }

  add(method, pattern, handler) {
    const paramNames = [];
    const regexStr =
      '^' +
      pattern
        .split('/')
        .map((seg) => {
          if (seg.startsWith(':')) {
            paramNames.push(seg.slice(1));
            return '([^/]+)';
          }
          return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        })
        .join('/') +
      '$';
    this.routes.push({ method, regex: new RegExp(regexStr), paramNames, handler });
  }

  get(p, h) { this.add('GET', p, h); }
  post(p, h) { this.add('POST', p, h); }
  delete(p, h) { this.add('DELETE', p, h); }

  match(method, pathname) {
    for (const r of this.routes) {
      if (r.method !== method) continue;
      const m = r.regex.exec(pathname);
      if (m) {
        const params = {};
        r.paramNames.forEach((name, i) => {
          params[name] = decodeURIComponent(m[i + 1]);
        });
        return { handler: r.handler, params };
      }
    }
    return null;
  }
}

const router = new Router();
const ctx = { db, env: { JWT_SECRET } };
adminRoutes.register(router, ctx);
clientRoutes.register(router, ctx);

const PUBLIC_DIR = path.join(__dirname, 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? '/index.html' : pathname;
  const resolved = path.normalize(path.join(PUBLIC_DIR, rel));
  if (resolved !== PUBLIC_DIR && !resolved.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403);
    res.end();
    return true;
  }
  if (!fs.existsSync(resolved) || fs.statSync(resolved).isDirectory()) return false;
  const type = MIME[path.extname(resolved)] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': type });
  fs.createReadStream(resolved).pipe(res);
  return true;
}

const activateLimiter = makeRateLimiter(20, 60_000);
const loginLimiter = makeRateLimiter(10, 60_000);

const server = http.createServer(async (req, res) => {
  let pathname = '/';
  try {
    const parsed = url.parse(req.url, true);
    pathname = parsed.pathname || '/';

    if (pathname === '/api/v1/activate' && !activateLimiter(clientIp(req))) {
      sendJson(res, 429, { ok: false, error: 'rate_limited' });
      return;
    }
    if (pathname === '/api/admin/login' && !loginLimiter(clientIp(req))) {
      sendJson(res, 429, { ok: false, error: 'rate_limited' });
      return;
    }

    if (pathname.startsWith('/api/')) {
      const match = router.match(req.method, pathname);
      if (!match) {
        sendJson(res, 404, { ok: false, error: 'not_found' });
        return;
      }
      const body = req.method === 'POST' || req.method === 'PUT' ? await readJsonBody(req) : {};
      await match.handler(req, res, body, match.params, parsed.query);
      return;
    }

    if ((req.method === 'GET' || req.method === 'HEAD') && serveStatic(req, res, pathname)) return;

    sendJson(res, 404, { ok: false, error: 'not_found' });
  } catch (e) {
    const status = e && e.statusCode ? e.statusCode : 500;
    console.error(`[rakshakx] error handling ${req.method} ${pathname}:`, e);
    const errorCode = status === 400 ? 'bad_request' : status === 413 ? 'payload_too_large' : 'server_error';
    try {
      sendJson(res, status, { ok: false, error: errorCode });
    } catch (_sendErr) {
      // Response headers were likely already sent; nothing more we can do.
    }
  }
});

server.on('clientError', (err, socket) => {
  if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
});

const PORT = Number(process.env.PORT) || 8787;
server.listen(PORT, () => {
  console.log(`[rakshakx] backend listening on :${PORT}`);
});
