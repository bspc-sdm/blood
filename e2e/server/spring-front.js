// e2e/web-এর স্ট্যাক, কিন্তু সার্ভার = Kotlin/Spring (backend/) — local.js-এর জায়গায় (E2E_BACKEND দিলে stack.mjs এটা চালায়)।
//   API      — /api/*, /r/*, /rx/*, /shop/*, /.well-known/* হুবহু Spring-এ পাঠানো (VPS-এর nginx-এর মতো, rewrite Spring নিজে করে)
//   লগইন    — Firebase Auth emulator (Spring-ও FIREBASE_AUTH_EMULATOR_HOST দিয়ে সেখানেই যায়)
//   ইমেইল   — Spring-এ SES নেই, তাই কোডটা তার লগে ("email to … not sent. Subject: … কোড: 123456"); /__e2e/mail সেখান থেকে পড়ে
//   OAuth    — /__e2e/oauth-token: Spring-এর নকল service account-এর token_uri; emulator-এর অ্যাডমিন টোকেন "owner" দেয়
//   ওয়েব     — /w/* = BUILD_DIR, /admin* = ADMIN_DIR (Vue অ্যাডমিন বিল্ড; না দিলে পুরোনো server/public/admin), বাকি সব SPA
//   হেডার    — প্রোডাকশনের nginx-এর CSP (deploy/deploy-web.sh): /admin… কড়া CSP, বাকি পাতা ওয়েবের CSP — যাতে CSP-তে আটকানো
//              কিছু এখানেও ধরা পড়ে
// চালানো: E2E_BACKEND=http://127.0.0.1:8080 E2E_BACKEND_LOG=spring.log PORT=4600 BUILD_DIR=e2e/.build/w node e2e/server/spring-front.js
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');

const SERVER = path.join(__dirname, '../../server');
const PORT = Number(process.env.PORT || 4600);
const EMU = process.env.FIREBASE_AUTH_EMULATOR_HOST || '127.0.0.1:9099';
const BUILD = path.resolve(process.env.BUILD_DIR || path.join(__dirname, '../.build/w'));
const ADMIN = path.resolve(process.env.ADMIN_DIR || path.join(SERVER, 'public/admin'));
// deploy/deploy-web.sh-এর CSP_WEB আর CSP_ADMIN (Sentry ছাড়া)
const CSP_WEB = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data: blob:; connect-src 'self' https://firestore.googleapis.com https://identitytoolkit.googleapis.com https://securetoken.googleapis.com; manifest-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
const CSP_ADMIN = "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const BACKEND = new URL(process.env.E2E_BACKEND || 'http://127.0.0.1:8080');
const BACKEND_LOG = process.env.E2E_BACKEND_LOG || '';
const PROJECT = process.env.E2E_PROJECT || 'blood-3eb28';

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.webmanifest': 'application/manifest+json', '.ico': 'image/x-icon', '.txt': 'text/plain' };

function sendFile(res, file) {
  const csp = file.startsWith(ADMIN + path.sep) ? CSP_ADMIN : CSP_WEB;
  res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-cache', 'content-security-policy': csp });
  fs.createReadStream(file).pipe(res);
}

function staticFile(urlPath) {
  const rel = decodeURIComponent(urlPath);
  let base;
  let sub;
  if (rel.startsWith('/w/')) { base = BUILD; sub = rel.slice(3); } else if (rel.startsWith('/admin')) { base = ADMIN; sub = rel.slice(6).replace(/^\//, '') || 'index.html'; } else return null;
  const f = path.resolve(base, sub);
  if (!f.startsWith(base)) return null;
  return fs.existsSync(f) && fs.statSync(f).isFile() ? f : null;
}

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

function proxyEmu(req, res) {
  const [host, port] = EMU.split(':');
  const up = http.request({ host, port, path: req.url, method: req.method, headers: { ...req.headers, host: EMU } }, (r) => {
    res.writeHead(r.statusCode, r.headers);
    r.pipe(res);
  });
  up.on('error', (e) => { res.writeHead(502); res.end(String(e)); });
  req.pipe(up);
}

/** Auth emulator-এর REST (অ্যাডমিন: Bearer owner) */
function emu(p, body) {
  const [host, port] = EMU.split(':');
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const r = http.request({ host, port, path: p, method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer owner', 'content-length': data.length } }, (x) => {
      const chunks = [];
      x.on('data', (c) => chunks.push(c));
      x.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); } catch (e) { reject(e); }
      });
    });
    r.on('error', reject);
    r.end(data);
  });
}

const b64u = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');

/** emulator সই ছাড়া custom token নেয় (Admin SDK-ও emulator-এ এমনই বানায়) */
async function customTokenFor(email) {
  const base = `/identitytoolkit.googleapis.com/v1/projects/${PROJECT}/accounts`;
  let uid = (await emu(`${base}:lookup`, { email: [email] })).users?.[0]?.localId;
  if (!uid) uid = (await emu(base, { email, emailVerified: true })).localId;
  if (!uid) throw new Error(`emulator: no account for ${email}`);
  const now = Math.floor(Date.now() / 1000);
  const iss = 'firebase-auth-emulator@example.com';
  return `${b64u({ alg: 'none', typ: 'JWT' })}.${b64u({ iss, sub: iss, aud: 'https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit', iat: now, exp: now + 3600, uid })}.`;
}

/** Spring-এর লগে শেষ লগইন কোড (LoggingMailSender: "email to <to> not sent. Subject: আপনার লগইন কোড: 123456") */
function lastCode(to) {
  if (!BACKEND_LOG || !fs.existsSync(BACKEND_LOG)) return { code: null, subject: null };
  const lines = fs.readFileSync(BACKEND_LOG, 'utf8').split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const m = /email to (\S+) not sent\. Subject: (.*)$/.exec(lines[i]);
    if (m && (!to || m[1] === to)) return { code: (/(\d{6})/.exec(m[2]) || [])[1] || null, subject: m[2].trim() };
  }
  return { code: null, subject: null };
}

const log = [];

function proxyApi(req, res, url) {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const raw = Buffer.concat(chunks);
    let action;
    if (/json/.test(req.headers['content-type'] || '') && raw.length) {
      try { action = JSON.parse(raw.toString('utf8')).action; } catch {}
    }
    const headers = { ...req.headers, host: BACKEND.host, 'x-forwarded-for': req.headers['x-forwarded-for'] || '127.0.0.1', 'x-forwarded-proto': 'https' };
    if (raw.length) headers['content-length'] = raw.length; else delete headers['content-length'];
    delete headers['transfer-encoding'];
    const t0 = Date.now();
    const up = http.request({ host: BACKEND.hostname, port: BACKEND.port, path: url.pathname + url.search, method: req.method, headers }, (r) => {
      const entry = { path: url.pathname, action, status: r.statusCode, ms: 0 };
      if (r.statusCode >= 400 && /json/.test(r.headers['content-type'] || '')) {
        const out = [];
        r.on('data', (c) => out.push(c));
        r.on('end', () => {
          const body = Buffer.concat(out);
          try { entry.error = JSON.parse(body.toString('utf8')).error; } catch {}
          entry.ms = Date.now() - t0;
          log.push(entry);
          if (process.env.E2E_VERBOSE) console.log('API', r.statusCode, url.pathname, action, entry.error);
          res.writeHead(r.statusCode, r.headers);
          res.end(body);
        });
        return;
      }
      entry.ms = Date.now() - t0;
      if (!url.pathname.startsWith('/api/events')) log.push(entry);
      res.writeHead(r.statusCode, r.headers);
      r.pipe(res);
    });
    up.on('error', (e) => {
      log.push({ path: url.pathname, action, status: 502, ms: Date.now() - t0, error: String(e) });
      if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: `backend unreachable: ${e.message}` }));
    });
    up.end(raw);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://local');
  const p = url.pathname;
  try {
    if (/^\/(identitytoolkit\.googleapis\.com|securetoken\.googleapis\.com|emulator)\//.test(p)) return proxyEmu(req, res);

    // পরীক্ষার সাহায্য (আসল সার্ভারে নেই)
    if (p === '/__e2e/mail') return json(res, 200, lastCode(url.searchParams.get('to')));
    if (p === '/__e2e/custom-token') return json(res, 200, { token: await customTokenFor(url.searchParams.get('email')) });
    if (p === '/__e2e/oauth-token') {
      req.resume();
      return json(res, 200, { access_token: 'owner', expires_in: 3600, token_type: 'Bearer' });
    }
    if (p === '/__e2e/log') return json(res, 200, log.splice(0));
    if (p === '/__e2e/doc' || p === '/__e2e/query') return json(res, 501, { error: 'not available with the Spring backend (no Firestore)' });

    if (/^\/(api|r|rx|shop|\.well-known)\//.test(p)) return proxyApi(req, res, url);
    const target = p === '/favicon.ico' ? '/w/icon.svg' : p;
    const f = staticFile(target);
    if (f) return sendFile(res, f);
    if (target.startsWith('/w/assets/')) { res.writeHead(404); return res.end('NOT_FOUND'); }
    if (target === '/admin' || target.startsWith('/admin/')) return sendFile(res, path.join(ADMIN, 'index.html'));
    return sendFile(res, path.join(BUILD, 'index.html'));
  } catch (e) {
    console.error('front error', p, e);
    if (!res.headersSent) res.writeHead(500);
    res.end('error');
  }
});

server.listen(PORT, '127.0.0.1', () => console.log(`SPRING FRONT READY http://127.0.0.1:${PORT} → ${BACKEND.origin}`));
