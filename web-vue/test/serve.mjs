// পরীক্ষার সার্ভার: test/.build (vite build --mode test) দেয়, অন্য সব পথে index.html (SPA),
// আর /api/* এর উত্তর test/fixtures/*.mjs থেকে (আসল সার্ভার/ডাটাবেস ছাড়া)।
// চালান: node test/serve.mjs [port]
// FIXTURES_EXTRA=1 — test/fixtures-e2e/*.mjs-ও (e2e-র বাড়তি উত্তর, অ্যাডমিন প্যানেল); স্ক্রিনশটের তুলনায় লাগে না
// /admin — dist-admin (npm run build:admin), সার্ভারের মতো কড়া CSP হেডারসহ
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, process.env.BUILD_DIR || '.build');
const port = Number(process.argv[2] || 4173);

const handlers = {};
const adminHandlers = {};
const dirs = ['fixtures', ...(process.env.FIXTURES_EXTRA ? ['fixtures-e2e'] : [])];
for (const d of dirs) {
  for (const f of fs.readdirSync(path.join(here, d)).filter((x) => x.endsWith('.mjs')).sort()) {
    const m = await import(pathToFileURL(path.join(here, d, f)).href);
    Object.assign(handlers, m.default || {});
    Object.assign(adminHandlers, m.admin || {});
  }
}
const ADMIN_DIR = path.resolve(process.env.ADMIN_DIR || path.join(here, '../dist-admin'));
// vercel.json-এর /admin(.*) হেডার
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const cookiesOf = (req) => Object.fromEntries(String(req.headers.cookie || '').split(';').map((c) => c.trim().split('=')).filter((x) => x[0]));

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.woff': 'font/woff', '.webmanifest': 'application/manifest+json', '.png': 'image/png' };

export const calls = [];
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/api/')) {
    let raw = '';
    for await (const c of req) raw += c;
    let body = {};
    try { body = JSON.parse(raw || '{}'); } catch {}
    const key = url.pathname === '/api/community' ? body.action : url.pathname;
    const authed = String(req.headers.authorization || '').startsWith('Bearer ');
    const panel = !!req.headers['x-lifolo-admin'];
    const h = (panel && adminHandlers[key]) || handlers[key];
    calls.push({ key, body, authed });
    if (process.env.LOG_API) console.log('API', key, JSON.stringify(body).slice(0, 200));
    if (!h) {
      res.writeHead(400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: `fixture missing: ${key}` }));
    }
    try {
      const out = await h(body, { authed, cookies: cookiesOf(req), panel });
      if (out && out.__status) {
        res.writeHead(out.__status, { 'content-type': 'application/json', ...(out.__headers || {}) });
        return res.end(JSON.stringify(out.body || {}));
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(out ?? { ok: true }));
    } catch (e) {
      res.writeHead(500, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: String(e && e.message) }));
    }
  }
  if (url.pathname === '/admin' || url.pathname.startsWith('/admin/')) {
    const rel = url.pathname.replace(/^\/admin\/?/, '');
    let file = path.join(ADMIN_DIR, rel || 'index.html');
    if (!file.startsWith(ADMIN_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ADMIN_DIR, 'index.html');
    res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream', 'content-security-policy': CSP, 'cache-control': 'no-cache' });
    return fs.createReadStream(file).pipe(res);
  }
  let file = url.pathname.startsWith('/w/') ? path.join(root, url.pathname) : path.join(root, 'w', 'index.html');
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'w', 'index.html');
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
server.listen(port, () => console.log(`test server http://localhost:${port}`));
