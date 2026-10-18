// Lifolo সার্ভার — Vercel ছাড়া, সাধারণ Node HTTP সার্ভার হিসেবে (VPS-এ nginx-এর পেছনে)।
// api/**.js হ্যান্ডলারগুলো বদলায় না: Vercel-এর Node runtime যেভাবে req/res সাজায় (req.body, req.query,
// req.cookies, res.status/json/send/redirect) ঠিক সেভাবে সাজিয়ে ডাকা হয়। vercel.json চালু হওয়ার সময় পড়া হয়।
//
// চালানো:  PORT=3000 node standalone/server.js
// পরিবেশ:  PORT (৩০০০), HOST (127.0.0.1), DEFAULT_MAX_DURATION (৩০০ সেকেন্ড), SHUTDOWN_TIMEOUT (২৫ সেকেন্ড)
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const querystring = require('querystring');
const { loadVercelConfig, scanApi, matchSource, applyDestination } = require('./vercel-config');

const SERVER_DIR = path.join(__dirname, '..');
// Vercel-এর অনুরোধের বডির সীমা ৪.৫ MB — একই রাখা হলো (nginx-এ client_max_body_size 5m)
const MAX_BODY = Math.floor(4.5 * 1024 * 1024);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.wasm': 'application/wasm',
  '.pdf': 'application/pdf',
  '.apk': 'application/vnd.android.package-archive',
};

const NOT_FOUND_BODY = 'The page could not be found\n\nNOT_FOUND\n';

// ---------- পেছনের কাজ (Vercel-এর waitUntil) ----------
// লম্বা সময় চলা সার্ভারে সাধারণ promise এমনিই চলতে থাকে। তবু lib/ambulance.js যে request-context খোঁজে
// সেটা এখানে দেওয়া হলো, যাতে (১) পেছনের কাজের ভুল লগে আসে, (২) বন্ধ করার সময় সেগুলো শেষ হওয়া পর্যন্ত অপেক্ষা করা যায়।
const background = new Set();
function waitUntil(p) {
  const t = Promise.resolve(p)
    .catch((e) => console.error('[background] task failed:', e && e.stack ? e.stack : e))
    .finally(() => background.delete(t));
  background.add(t);
}
const CTX_SYMBOL = Symbol.for('@vercel/request-context');
if (!globalThis[CTX_SYMBOL]) {
  globalThis[CTX_SYMBOL] = { get: () => ({ waitUntil, headers: {} }) };
}

// ---------- Vercel-এর মতো req/res সাহায্যকারী ----------
function parseQuery(searchParams) {
  const q = {};
  for (const [k, v] of searchParams) {
    if (Object.prototype.hasOwnProperty.call(q, k)) q[k] = [].concat(q[k], v);
    else q[k] = v;
  }
  return q;
}

function parseCookies(header) {
  const out = {};
  for (const part of String(header || '').split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    if (!k || Object.prototype.hasOwnProperty.call(out, k)) continue;
    let v = part.slice(i + 1).trim();
    if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1);
    try {
      out[k] = decodeURIComponent(v);
    } catch {
      out[k] = v;
    }
  }
  return out;
}

class BodyError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** পুরো বডি পড়া (সীমা পেরোলে 413) */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > MAX_BODY) {
      reject(new BodyError(413, 'FUNCTION_PAYLOAD_TOO_LARGE'));
      return;
    }
    const chunks = [];
    let size = 0;
    let done = false;
    req.on('data', (c) => {
      if (done) return;
      size += c.length;
      if (size > MAX_BODY) {
        done = true;
        req.pause();
        reject(new BodyError(413, 'FUNCTION_PAYLOAD_TOO_LARGE'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!done) {
        done = true;
        resolve(Buffer.concat(chunks));
      }
    });
    req.on('error', (e) => {
      if (!done) {
        done = true;
        reject(e);
      }
    });
  });
}

/** Vercel-এর @vercel/node-এর মতো: content-type দেখে বডি বোঝা */
function parseBody(req, buf) {
  const type = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
  if (!type) return undefined;
  switch (type) {
    case 'application/json': {
      const text = buf.toString('utf8');
      if (!text.trim()) return undefined;
      try {
        return JSON.parse(text);
      } catch {
        throw new BodyError(400, 'Invalid JSON');
      }
    }
    case 'application/x-www-form-urlencoded':
      return querystring.parse(buf.toString('utf8'));
    case 'text/plain':
      return buf.toString('utf8');
    case 'application/octet-stream':
      return buf;
    default:
      return undefined;
  }
}

function etagOf(chunk) {
  return `"${crypto.createHash('sha1').update(chunk).digest('base64').slice(0, 27)}"`;
}

function isFresh(req, etag) {
  const inm = req.headers['if-none-match'];
  if (!inm || !etag) return false;
  return inm.split(',').map((s) => s.trim().replace(/^W\//, '')).includes(etag.replace(/^W\//, ''));
}

function enhanceRes(req, res) {
  res.status = (code) => {
    res.statusCode = code;
    return res;
  };
  res.send = (body) => {
    let chunk = body;
    if (chunk === null || chunk === undefined) chunk = '';
    else if (typeof chunk === 'object' && !Buffer.isBuffer(chunk)) return res.json(chunk);
    else if (typeof chunk === 'number' || typeof chunk === 'boolean') chunk = String(chunk);
    if (Buffer.isBuffer(chunk)) {
      if (!res.getHeader('content-type')) res.setHeader('Content-Type', 'application/octet-stream');
    } else {
      if (!res.getHeader('content-type')) res.setHeader('Content-Type', 'text/html');
      const t = String(res.getHeader('content-type'));
      if (!/charset=/i.test(t)) res.setHeader('Content-Type', `${t}; charset=utf-8`);
      chunk = Buffer.from(chunk, 'utf8');
    }
    if (chunk.length && !res.getHeader('etag')) res.setHeader('ETag', etagOf(chunk));
    if (res.statusCode >= 200 && res.statusCode < 300 && ['GET', 'HEAD'].includes(req.method) && isFresh(req, res.getHeader('etag'))) {
      res.statusCode = 304;
      res.removeHeader('Content-Type');
      res.removeHeader('Content-Length');
      res.end();
      return res;
    }
    res.setHeader('Content-Length', chunk.length);
    if (req.method === 'HEAD') res.end();
    else res.end(chunk);
    return res;
  };
  res.json = (obj) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    return res.send(JSON.stringify(obj));
  };
  res.redirect = (statusOrUrl, url) => {
    let status = 307;
    let loc = statusOrUrl;
    if (typeof statusOrUrl === 'number') {
      status = statusOrUrl;
      loc = url;
    }
    if (typeof loc !== 'string') throw new Error('Invalid redirect arguments');
    res.writeHead(status, { Location: loc }).end();
    return res;
  };
  return res;
}

/** সময় শেষ হলে বা উত্তর চলে গেলে পরের সব লেখা চুপচাপ বাদ (হ্যান্ডলার দেরিতে লিখলে ক্র্যাশ নয়) */
function muteRes(res) {
  const noop = () => res;
  for (const k of ['status', 'json', 'send', 'redirect', 'setHeader', 'removeHeader', 'writeHead', 'write', 'end', 'appendHeader']) res[k] = noop;
}

// ---------- আসল ক্লায়েন্টের IP ----------
// কোড IP পড়ে x-forwarded-for-এর প্রথম মান থেকে (Vercel এটা নিজে বসায়, ক্লায়েন্ট বদলাতে পারে না)।
// এখানে শুধু নিজের মেশিনের (nginx) কাছ থেকে আসা হেডার বিশ্বাস করা হয়, আর তার শেষ মানটা (nginx যা দেখেছে) রাখা হয় —
// ক্লায়েন্টের পাঠানো নকল x-forwarded-for দিয়ে রেট-লিমিট এড়ানো যায় না।
const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
function normalizeClientIp(req) {
  const peer = req.socket.remoteAddress || '';
  let ip = peer.replace(/^::ffff:/, '');
  const trusted = LOOPBACK.has(peer) || (process.env.TRUSTED_PROXIES || '').split(',').map((s) => s.trim()).filter(Boolean).includes(peer);
  if (trusted) {
    const parts = String(req.headers['x-forwarded-for'] || '').split(',').map((s) => s.trim()).filter(Boolean);
    if (parts.length) ip = parts[parts.length - 1];
  }
  req.headers['x-forwarded-for'] = ip || 'unknown';
  req.headers['x-real-ip'] = ip || 'unknown';
}

// ---------- অ্যাপ ----------
function createApp(options = {}) {
  const serverDir = options.serverDir || SERVER_DIR;
  const publicDir = fs.realpathSync(path.join(serverDir, 'public'));
  const config = loadVercelConfig(path.join(serverDir, 'vercel.json'));
  for (const w of config.warnings) console.warn('[config]', w);
  const routes = scanApi(path.join(serverDir, 'api'));
  const defaultMaxDuration = Number(options.defaultMaxDuration || process.env.DEFAULT_MAX_DURATION) || 300;
  const loaded = new Map();
  let inflight = 0;
  let closing = false;

  function maxDurationFor(rel) {
    for (const f of config.functions) if (f.re.test(rel) && f.maxDuration) return f.maxDuration;
    return defaultMaxDuration;
  }

  function loadHandler(route) {
    if (!loaded.has(route.file)) {
      const mod = require(route.file);
      const fn = typeof mod === 'function' ? mod : mod && typeof mod.default === 'function' ? mod.default : null;
      if (!fn) throw new Error(`${route.rel} does not export a handler`);
      loaded.set(route.file, fn);
    }
    return loaded.get(route.file);
  }

  function matchingHeaders(pathname) {
    const out = [];
    for (const rule of config.headers) if (matchSource(rule, pathname)) out.push(...rule.headers);
    return out;
  }

  function applyHeaders(res, list) {
    for (const h of list) res.setHeader(h.key, h.value);
  }

  /** public/-এর ভেতরের ফাইল (বাইরে যাওয়া, .., নাল বাইট, symlink দিয়ে বাইরে — সব বাদ) */
  function resolveStatic(pathname) {
    const segs = pathname.split('/').slice(1);
    const dirIndex = pathname.endsWith('/');
    const parts = [];
    for (const raw of segs) {
      if (raw === '') continue;
      let s;
      try {
        s = decodeURIComponent(raw);
      } catch {
        return null;
      }
      if (s === '.' || s === '..' || s.includes('/') || s.includes('\\') || s.includes('\0')) return null;
      parts.push(s);
    }
    let file = path.join(publicDir, ...parts);
    if (dirIndex) file = path.join(file, 'index.html');
    let real;
    try {
      real = fs.realpathSync(file);
    } catch {
      return null;
    }
    if (real !== publicDir && !real.startsWith(publicDir + path.sep)) return null;
    let st;
    try {
      st = fs.statSync(real);
    } catch {
      return null;
    }
    return st.isFile() ? { file: real, stat: st } : null;
  }

  function resolveTarget(pathname) {
    if (pathname === '/api' || pathname.startsWith('/api/')) {
      const route = routes.get(pathname);
      return route ? { type: 'function', route } : null;
    }
    const s = resolveStatic(pathname);
    return s ? { type: 'static', ...s } : null;
  }

  function sendNotFound(req, res, headers) {
    applyHeaders(res, headers);
    res.statusCode = 404;
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.setHeader('Cache-Control', 'public, max-age=0, must-revalidate');
    res.setHeader('Content-Length', Buffer.byteLength(NOT_FOUND_BODY));
    res.end(req.method === 'HEAD' ? undefined : NOT_FOUND_BODY);
  }

  function serveStatic(req, res, target, headers) {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.statusCode = 405;
      res.setHeader('Allow', 'GET, HEAD');
      res.setHeader('Content-Type', 'text/plain; charset=utf-8');
      res.end('Method Not Allowed');
      return;
    }
    const { file, stat } = target;
    const etag = `W/"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
    res.setHeader('Content-Type', MIME[path.extname(file).toLowerCase()] || 'application/octet-stream');
    // Vercel-এর স্থির ফাইলের সাধারণ নিয়ম; vercel.json-এর headers পরে বসে তাই সেটাই জেতে
    res.setHeader('Cache-Control', 'public, max-age=0, must-revalidate');
    res.setHeader('ETag', etag);
    res.setHeader('Last-Modified', stat.mtime.toUTCString());
    res.setHeader('Accept-Ranges', 'none');
    applyHeaders(res, headers);
    if (isFresh(req, etag)) {
      res.statusCode = 304;
      res.removeHeader('Content-Type');
      res.end();
      return;
    }
    res.statusCode = 200;
    res.setHeader('Content-Length', stat.size);
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    const stream = fs.createReadStream(file);
    stream.on('error', (e) => {
      console.error('[static] read failed', file, e.message);
      res.destroy(e);
    });
    stream.pipe(res);
  }

  async function invokeFunction(req, res, target, url, query, headers) {
    const { route } = target;
    applyHeaders(res, headers);
    enhanceRes(req, res);

    let buf;
    try {
      buf = await readBody(req);
      req.body = parseBody(req, buf);
    } catch (e) {
      const status = e instanceof BodyError ? e.status : 400;
      res.statusCode = status;
      res.setHeader('Content-Type', 'text/plain; charset=utf-8');
      res.setHeader('Connection', 'close');
      res.end(status === 413 ? 'Request Entity Too Large\n\nFUNCTION_PAYLOAD_TOO_LARGE\n' : `${e.message}\n`);
      return;
    }
    req.query = query;
    Object.defineProperty(req, 'cookies', { configurable: true, enumerable: true, get: () => parseCookies(req.headers.cookie) });

    let fn;
    try {
      fn = loadHandler(route);
    } catch (e) {
      console.error(`[api] failed to load ${route.rel}:`, e && e.stack ? e.stack : e);
      res.status(500).send('A server error has occurred\n\nFUNCTION_INVOCATION_FAILED\n');
      return;
    }

    const seconds = maxDurationFor(route.rel);
    let timer;
    const timeout = new Promise((resolve) => {
      timer = setTimeout(() => resolve('timeout'), seconds * 1000);
    });
    // হ্যান্ডলার উত্তর শেষ না করে ফিরলেও (Vercel-এর মতো) উত্তর শেষ হওয়া বা সময় শেষ হওয়া পর্যন্ত অপেক্ষা
    const finished = new Promise((resolve) => {
      if (res.writableEnded) resolve('done');
      res.on('finish', () => resolve('done'));
      res.on('close', () => resolve('done'));
    });
    const run = (async () => {
      try {
        await fn(req, res);
      } catch (e) {
        console.error(`[api] ${route.rel} threw:`, e && e.stack ? e.stack : e);
        if (!res.headersSent) {
          res.statusCode = 500;
          res.setHeader('Content-Type', 'text/plain; charset=utf-8');
          res.end('A server error has occurred\n\nFUNCTION_INVOCATION_FAILED\n');
        } else if (!res.writableEnded) res.destroy();
      }
      return finished;
    })();
    const outcome = await Promise.race([run, timeout]);
    clearTimeout(timer);
    if (outcome === 'timeout') {
      console.error(`[api] ${route.rel} exceeded maxDuration ${seconds}s`);
      if (!res.headersSent) {
        res.statusCode = 504;
        res.setHeader('Content-Type', 'text/plain; charset=utf-8');
        res.setHeader('Connection', 'close');
        res.end('An error occurred with your deployment\n\nFUNCTION_INVOCATION_TIMEOUT\n');
      } else if (!res.writableEnded) res.destroy();
      muteRes(res);
      // হ্যান্ডলার পেছনে চলতেই থাকে (Node-এ থামানো যায় না) — শেষ হলে/ভুল হলে লগ
      waitUntil(run);
    }
  }

  async function handle(req, res) {
    inflight++;
    res.on('close', () => {
      inflight--;
    });
    if (closing) res.setHeader('Connection', 'close');
    try {
      if (!req.url || req.url[0] !== '/') {
        res.statusCode = 400;
        res.end('Bad Request');
        return;
      }
      normalizeClientIp(req);
      const url = new URL(req.url, 'http://localhost');
      const pathname = url.pathname;

      // সার্ভারের নিজের জীবিত-আছি পরীক্ষা (ডাটাবেস/Firebase ছাড়া); আসল সংযোগ পরীক্ষা /api/health
      if (pathname === '/healthz') {
        res.statusCode = closing ? 503 : 200;
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.setHeader('Cache-Control', 'no-store');
        res.end(JSON.stringify({ ok: !closing, uptime: Math.round(process.uptime()) }));
        return;
      }

      const headers = matchingHeaders(pathname);

      for (const rule of config.redirects) {
        const params = matchSource(rule, pathname);
        if (!params) continue;
        const loc = applyDestination(rule.destination, params);
        const status = rule.statusCode || (rule.permanent === false ? 307 : 308);
        applyHeaders(res, headers);
        res.writeHead(status, { Location: loc });
        res.end();
        return;
      }

      // Vercel-এর মতো: আগে আসল ফাইল/ফাংশন, না পেলে rewrites (প্রথম মিলটা)
      let target = resolveTarget(pathname);
      let query = parseQuery(url.searchParams);
      if (!target) {
        for (const rule of config.rewrites) {
          const params = matchSource(rule, pathname);
          if (!params) continue;
          const dest = new URL(applyDestination(rule.destination, params), 'http://localhost');
          // গন্তব্যের query আগে, তারপর আসল অনুরোধের query (একই নাম থাকলে গন্তব্যেরটা থাকে)
          const merged = new URLSearchParams(dest.searchParams);
          for (const [k, v] of url.searchParams) if (!dest.searchParams.has(k)) merged.append(k, v);
          query = parseQuery(merged);
          target = resolveTarget(dest.pathname);
          break;
        }
      }
      if (!target) return sendNotFound(req, res, headers);
      if (target.type === 'static') return serveStatic(req, res, target, headers);
      return await invokeFunction(req, res, target, url, query, headers);
    } catch (e) {
      console.error('[server] request failed:', e && e.stack ? e.stack : e);
      if (!res.headersSent) {
        res.statusCode = 500;
        res.setHeader('Content-Type', 'text/plain; charset=utf-8');
        res.end('Internal Server Error');
      } else if (!res.writableEnded) res.destroy();
    }
  }

  const server = http.createServer((req, res) => {
    handle(req, res);
  });
  // ধীরে ধীরে পাঠানো অনুরোধ (slowloris) আটকাতে; nginx-এর keepalive (৬০ সে.)-এর চেয়ে একটু বেশি রাখা
  server.headersTimeout = 20_000;
  server.requestTimeout = 60_000;
  server.keepAliveTimeout = 65_000;
  server.maxHeadersCount = 100;

  /** নতুন অনুরোধ বন্ধ, চলতি অনুরোধ আর পেছনের কাজ শেষ হওয়া পর্যন্ত অপেক্ষা (সর্বোচ্চ timeoutMs) */
  async function shutdown(timeoutMs = Number(process.env.SHUTDOWN_TIMEOUT || 25) * 1000) {
    if (closing) return;
    closing = true;
    const closed = new Promise((resolve) => server.close(() => resolve()));
    server.closeIdleConnections();
    const deadline = Date.now() + timeoutMs;
    while ((inflight > 0 || background.size > 0) && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 100));
      server.closeIdleConnections();
    }
    if (inflight > 0 || background.size > 0) console.error(`[server] shutdown timeout: ${inflight} request(s), ${background.size} background task(s) abandoned`);
    server.closeAllConnections();
    await closed;
  }

  return { server, shutdown, config, routes, background, stats: () => ({ inflight, background: background.size }) };
}

function start(options = {}) {
  const app = createApp(options);
  const port = options.port !== undefined ? options.port : Number(process.env.PORT || 3000);
  const host = options.host || process.env.HOST || '127.0.0.1';
  return new Promise((resolve, reject) => {
    app.server.once('error', reject);
    app.server.listen(port, host, () => {
      app.server.off('error', reject);
      app.port = app.server.address().port;
      resolve(app);
    });
  });
}

if (require.main === module) {
  process.on('unhandledRejection', (e) => console.error('[server] unhandled rejection:', e && e.stack ? e.stack : e));
  start()
    .then((app) => {
      console.log(`[server] Lifolo listening on ${app.server.address().address}:${app.port} (${app.routes.size} API routes)`);
      // প্রথম অনুরোধের আগেই হ্যান্ডলারগুলো লোড (ভুল থাকলে এখনই লগে আসে; সার্ভার তবু চলে)
      setImmediate(() => {
        for (const route of app.routes.values()) {
          try {
            require(route.file);
          } catch (e) {
            console.error(`[server] preload failed for ${route.rel}:`, e && e.message);
          }
        }
      });
      let stopping = false;
      const stop = (sig) => {
        if (stopping) return;
        stopping = true;
        console.log(`[server] ${sig} received, shutting down`);
        app.shutdown().then(() => process.exit(0), (e) => {
          console.error(e);
          process.exit(1);
        });
      };
      process.on('SIGTERM', () => stop('SIGTERM'));
      process.on('SIGINT', () => stop('SIGINT'));
    })
    .catch((e) => {
      console.error('[server] failed to start:', e);
      process.exit(1);
    });
}

module.exports = { createApp, start, parseQuery, parseCookies, MAX_BODY };
