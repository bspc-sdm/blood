// পরীক্ষার সার্ভার: test/.build (vite build --mode test) দেয়, অন্য সব পথে index.html (SPA),
// আর /api/* এর উত্তর test/fixtures/*.mjs থেকে (আসল সার্ভার/ডাটাবেস ছাড়া)।
// চালান: node test/serve.mjs [port]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, process.env.BUILD_DIR || '.build');
const port = Number(process.argv[2] || 4173);

const handlers = {};
for (const f of fs.readdirSync(path.join(here, 'fixtures')).filter((x) => x.endsWith('.mjs')).sort()) {
  const m = await import(pathToFileURL(path.join(here, 'fixtures', f)).href);
  Object.assign(handlers, m.default || {});
}

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
    const h = handlers[key];
    calls.push({ key, body, authed });
    if (process.env.LOG_API) console.log('API', key, JSON.stringify(body).slice(0, 200));
    if (!h) {
      res.writeHead(400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: `fixture missing: ${key}` }));
    }
    try {
      const out = await h(body, { authed });
      if (out && out.__status) {
        res.writeHead(out.__status, { 'content-type': 'application/json' });
        return res.end(JSON.stringify(out.body || {}));
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(out ?? { ok: true }));
    } catch (e) {
      res.writeHead(500, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: String(e && e.message) }));
    }
  }
  let file = url.pathname.startsWith('/w/') ? path.join(root, url.pathname) : path.join(root, 'w', 'index.html');
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'w', 'index.html');
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
server.listen(port, () => console.log(`test server http://localhost:${port}`));
