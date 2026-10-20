// লোকাল পুরো স্ট্যাক (শুধু পরীক্ষা, e2e/web): server/api/** হ্যান্ডলারগুলো বদল ছাড়াই Vercel-এর মতো করে ডাকা।
//   ডাটাবেস — মেমোরিতে (fakedb.js, server/test/support/fakefb.js-এর কপি); চালু থাকা পর্যন্ত থাকে
//   লগইন    — আসল firebase-admin, কিন্তু Firebase Auth emulator-এ (FIREBASE_AUTH_EMULATOR_HOST)
//   ইমেইল   — পাঠানো হয় না; শেষ কোড /__e2e/mail?to=<email> থেকে পড়া যায় (মালিকের লগইনের জন্য)
//   পুশ      — পাঠানো হয় না (ঘণ্টার তালিকা/inbox আসল কোডেই লেখা হয়)
//   ওয়েব     — /w/* = BUILD_DIR (VITE_AUTH_EMULATOR=origin দিয়ে বানানো বিল্ড); অন্য পাতা vercel.json-এর মতো /w/index.html
//   emulator — /identitytoolkit.googleapis.com, /securetoken.googleapis.com, /emulator একই ঠিকানা থেকে 9099-এ
// চালানো: PORT=4600 BUILD_DIR=e2e/.build/w node e2e/server/local.js
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const Module = require('module');

const SERVER = path.join(__dirname, '../../server');
const PORT = Number(process.env.PORT || 4600);
const EMU = process.env.FIREBASE_AUTH_EMULATOR_HOST || (process.env.FIREBASE_AUTH_EMULATOR_HOST = '127.0.0.1:9099');
const BUILD = path.resolve(process.env.BUILD_DIR || path.join(__dirname, '../.build/w'));
const ADMIN = path.resolve(process.env.ADMIN_DIR || path.join(__dirname, '../../server/public/admin'));
const PROJECT = 'blood-3eb28';
// email-code.js কোড হ্যাশের চাবি এখান থেকে নেয় (শুধু private_key); firebase.js-এর cert() চলে না, কারণ অ্যাপ আগেই চালু
process.env.FIREBASE_SERVICE_ACCOUNT = JSON.stringify({ project_id: PROJECT, private_key: 'e2e-local-only' });
process.env.GMAIL_USER = 'e2e@local';
process.env.GMAIL_APP_PASSWORD = 'x';
process.env.WEB_SESSION_SECRET = 'e2e-local-web-session-secret-0123456789';
process.env.TZ = process.env.TZ || 'Asia/Dhaka';

const reqFrom = (id) => Module.createRequire(path.join(SERVER, 'package.json')).resolve(id);

// ---- firebase-admin: Firestore = মেমোরি, Auth = emulator, Messaging = কিছু না ----
const fake = require('./fakedb');
const real = require(reqFrom('firebase-admin'));
if (!real.apps.length) real.initializeApp({ projectId: PROJECT });
const firestoreNs = Object.assign(() => fake.db, { FieldValue: fake.FieldValue, FieldPath: fake.FieldPath, Timestamp: real.firestore.Timestamp });
const sentPushes = [];
const messagingNs = () => ({
  sendEach: async (msgs) => { sentPushes.push(...msgs); return { responses: msgs.map(() => ({ success: true })) }; },
  send: async (m) => { sentPushes.push(m); return 'e2e'; },
});
const shim = new Proxy({}, {
  get(_, k) {
    if (k === 'firestore') return firestoreNs;
    if (k === 'messaging') return messagingNs;
    const v = real[k];
    return typeof v === 'function' ? v.bind(real) : v;
  },
});
require.cache[reqFrom('firebase-admin')].exports = shim;

// ---- nodemailer: চিঠি পাঠানো নয়, জমা রাখা ----
const mails = [];
require(reqFrom('nodemailer'));
require.cache[reqFrom('nodemailer')].exports = {
  createTransport: () => ({ sendMail: async (m) => { mails.push({ ...m, at: Date.now() }); return { messageId: 'e2e' }; } }),
};

// Vercel-এর waitUntil (lib/ambulance.js খোঁজে)
const CTX = Symbol.for('@vercel/request-context');
globalThis[CTX] = { get: () => ({ waitUntil: (p) => Promise.resolve(p).catch((e) => console.error('[bg]', e)), headers: {} }) };

// ---- রাউটিং ----
const handlers = new Map();
function handlerFor(p) {
  const file = path.join(SERVER, `${p}.js`);
  if (!file.startsWith(path.join(SERVER, 'api') + path.sep) || !fs.existsSync(file)) return null;
  if (!handlers.has(file)) handlers.set(file, require(file));
  return handlers.get(file);
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.webmanifest': 'application/manifest+json', '.ico': 'image/x-icon', '.txt': 'text/plain' };

function sendFile(res, file) {
  const type = MIME[path.extname(file)] || 'application/octet-stream';
  res.writeHead(200, { 'content-type': type, 'cache-control': 'no-cache' });
  fs.createReadStream(file).pipe(res);
}

function staticFile(urlPath) {
  // /w/* → বিল্ড; /admin* → server/public/admin
  const rel = decodeURIComponent(urlPath);
  let base;
  let sub;
  if (rel.startsWith('/w/')) { base = BUILD; sub = rel.slice(3); } else if (rel.startsWith('/admin')) { base = ADMIN; sub = rel.slice(6).replace(/^\//, '') || 'index.html'; } else return null;
  const f = path.resolve(base, sub);
  if (!f.startsWith(base)) return null;
  return fs.existsSync(f) && fs.statSync(f).isFile() ? f : null;
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

function vercelRes(res) {
  let status = 200;
  return Object.assign(res, {
    status(c) { status = c; res.statusCode = c; return res; },
    json(j) { if (!res.headersSent) res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', ...res.getHeaders() }); res.end(JSON.stringify(j)); return res; },
    send(b) { if (!res.headersSent) res.writeHead(status, res.getHeaders()); res.end(typeof b === 'string' || Buffer.isBuffer(b) ? b : JSON.stringify(b)); return res; },
    redirect(a, b) { const [c, u] = typeof a === 'number' ? [a, b] : [307, a]; res.writeHead(c, { location: u }); res.end(); return res; },
  });
}

const log = [];
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://local');
  const p = url.pathname;
  try {
    if (/^\/(identitytoolkit\.googleapis\.com|securetoken\.googleapis\.com|emulator)\//.test(p)) return proxyEmu(req, res);

    // পরীক্ষার সাহায্য (আসল সার্ভারে নেই)
    if (p === '/__e2e/mail') {
      const to = url.searchParams.get('to');
      const m = mails.filter((x) => !to || x.to === to).pop();
      const code = m && (/(\d{6})/.exec(m.subject) || [])[1];
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ code: code || null, subject: m?.subject || null }));
    }
    if (p === '/__e2e/custom-token') {
      // ইমেইলের অ্যাকাউন্ট (না থাকলে বানিয়ে) — শুধু emulator-এ, ইমেইলের কোড ছাড়া মালিকের API ডাকার জন্য
      const email = url.searchParams.get('email');
      const a = real.auth();
      const u = await a.getUserByEmail(email).catch(() => a.createUser({ email, emailVerified: true }));
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ token: await a.createCustomToken(u.uid) }));
    }
    if (p === '/__e2e/doc') {
      const d = fake.store.get(url.searchParams.get('path'));
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(d ?? null));
    }
    if (p === '/__e2e/query') {
      const col = url.searchParams.get('col');
      const rows = [...fake.store.entries()].filter(([k]) => k.startsWith(`${col}/`) && k.split('/').length === col.split('/').length + 1).map(([k, v]) => ({ id: k.split('/').pop(), ...v }));
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(rows));
    }
    if (p === '/__e2e/log') {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(log.splice(0)));
    }

    // vercel.json-এর rewrites
    let target = p;
    let query = Object.fromEntries(url.searchParams);
    let m;
    if ((m = /^\/r\/([^/]+)$/.exec(p))) { target = '/api/share'; query = { ...query, id: m[1] }; } else if ((m = /^\/rx\/([^/]+)$/.exec(p))) { target = '/api/share'; query = { ...query, rx: m[1] }; } else if ((m = /^\/shop\/([^/]+)$/.exec(p))) { target = '/api/share'; query = { ...query, shop: m[1] }; } else if (p === '/favicon.ico') target = '/w/icon.svg';

    if (target.startsWith('/api/')) {
      const h = handlerFor(target.replace(/\/$/, ''));
      if (!h) { res.writeHead(404); return res.end('NOT_FOUND'); }
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const raw = Buffer.concat(chunks).toString('utf8');
      let body;
      if (/json/.test(req.headers['content-type'] || '') && raw.trim()) {
        try { body = JSON.parse(raw); } catch { res.writeHead(400); return res.end('Invalid JSON'); }
      }
      req.body = body;
      req.query = query;
      req.headers['x-forwarded-for'] = req.headers['x-forwarded-for'] || '127.0.0.1';
      const r = vercelRes(res);
      const t0 = Date.now();
      const origJson = r.json;
      r.json = (j) => {
        log.push({ path: target, action: body?.action, status: r.statusCode, ms: Date.now() - t0, error: r.statusCode >= 400 ? j?.error : undefined });
        if (r.statusCode >= 400 && process.env.E2E_VERBOSE) console.log('API', r.statusCode, target, body?.action, j?.error);
        return origJson(j);
      };
      await h(req, r);
      return;
    }

    const f = staticFile(target);
    if (f) return sendFile(res, f);
    if (target.startsWith('/w/assets/')) { res.writeHead(404); return res.end('NOT_FOUND'); }
    if (target === '/admin' || target.startsWith('/admin/')) return sendFile(res, path.join(ADMIN, 'index.html'));
    // বাকি সব (/, /services, /w/…) → SPA
    return sendFile(res, path.join(BUILD, 'index.html'));
  } catch (e) {
    console.error('local server error', p, e);
    if (!res.headersSent) res.writeHead(500);
    res.end('error');
  }
});

server.listen(PORT, '127.0.0.1', () => console.log(`LOCAL READY http://127.0.0.1:${PORT}`));
