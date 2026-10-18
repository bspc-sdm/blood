// Vercel ছাড়া চলা সার্ভার (standalone/server.js): রাউট, rewrites, headers, স্থির ফাইল, ক্রন, আর req/res সাহায্যকারী।
// সার্ভার এই প্রসেসেই চালু হয় (র‍্যান্ডম পোর্টে), Firebase-এর বদলে নকল (fakefb)।
const test = require('node:test');
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');
const http = require('http');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const { start } = require(path.join(SERVER, 'standalone/server.js'));
const { runCron } = require(path.join(SERVER, 'standalone/cron.js'));
const { loadVercelConfig, compileSource, matchSource } = require(path.join(SERVER, 'standalone/vercel-config.js'));

process.env.CRON_SECRET = 'test-cron-secret';

/** কাঁচা HTTP অনুরোধ (fetch পথ স্বাভাবিক করে ফেলে, তাই http.request) */
function call(port, method, p, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path: p, headers }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json;
        try {
          json = JSON.parse(text);
        } catch {
          json = undefined;
        }
        resolve({ status: res.statusCode, headers: res.headers, text, json });
      });
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}
const postJson = (port, p, obj, headers = {}) => call(port, 'POST', p, {
  headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(obj),
});

let app;
test.before(async () => {
  app = await start({ port: 0 });
});
test.after(async () => {
  await app.shutdown(2000);
});

test('vercel.json-এর সব নিয়ম বোঝা যায় (কিছু বাদ পড়েনি)', () => {
  const cfg = loadVercelConfig(path.join(SERVER, 'vercel.json'));
  assert.deepStrictEqual(cfg.warnings, []);
  assert.ok(cfg.rewrites.length >= 7);
  assert.strictEqual(cfg.crons[0].path, '/api/cron/daily');
  // path-to-regexp-এর মতো: strict আর case-sensitive
  const r = compileSource('/r/:id');
  assert.deepStrictEqual(matchSource(r, '/r/abc'), { id: 'abc' });
  assert.strictEqual(matchSource(r, '/r/abc/'), null);
  assert.strictEqual(matchSource(r, '/R/abc'), null);
  assert.strictEqual(matchSource(r, '/r/a/b'), null);
  const opt = compileSource('/x/:rest*');
  assert.deepStrictEqual(matchSource(opt, '/x'), { rest: '' });
  assert.deepStrictEqual(matchSource(opt, '/x/a/b'), { rest: 'a/b' });
});

test('API রাউট: /healthz, /api/health, community (অতিথি), অজানা কাজ', async () => {
  const port = app.port;
  const hz = await call(port, 'GET', '/healthz');
  assert.strictEqual(hz.status, 200);
  assert.strictEqual(hz.json.ok, true);

  const h = await call(port, 'GET', '/api/health');
  assert.strictEqual(h.status, 200, h.text);
  assert.deepStrictEqual(h.json, { ok: true, firebase: true });
  assert.match(h.headers['content-type'], /^application\/json; charset=utf-8/);

  const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
  F.store.set('ambulances/a1', { name: 'জরুরি সেবা', status: 'approved', district: DIST, phone: '01711000000', types: [] });
  const a = await postJson(port, '/api/community', { lang: 'bn', action: 'ambulances', district: DIST });
  assert.strictEqual(a.status, 200, a.text);
  assert.strictEqual(a.json.items.length, 1);
  assert.strictEqual(a.json.items[0].name, 'জরুরি সেবা');

  // লগইন দরকার এমন কাজ টোকেন ছাড়া
  const w = await postJson(port, '/api/community', { lang: 'bn', action: 'post_create' });
  assert.strictEqual(w.status, 401, w.text);

  // ভুল JSON → 400 (Vercel-এর মতো), হ্যান্ডলার পর্যন্ত যায় না
  const bad = await call(port, 'POST', '/api/community', { headers: { 'content-type': 'application/json' }, body: '{oops' });
  assert.strictEqual(bad.status, 400);
  assert.match(bad.text, /Invalid JSON/);

  // API-তে vercel.json-এর পাতার CSP বসে না
  assert.strictEqual(a.headers['content-security-policy'], undefined);
});

test('rewrites: /r/:id, /rx/:code, /shop/:code, assetlinks, /admin, favicon', async () => {
  const port = app.port;
  F.store.set('requests/req1', {
    requesterUid: 'u1', requesterName: 'গোপন', patientGroup: 'B+', bags: 1, hospital: 'মেডিকেল', placeName: 'ময়মনসিংহ',
    neededByMillis: Date.now() + 86400000, status: 'open',
  });
  const r = await call(port, 'GET', '/r/req1');
  assert.strictEqual(r.status, 200, r.text.slice(0, 300));
  assert.match(r.headers['content-type'], /^text\/html/);
  assert.match(r.text, /B\+/);
  assert.ok(!r.text.includes('গোপন'));

  const missing = await call(port, 'GET', '/r/nope404');
  assert.strictEqual(missing.status, 404);
  assert.match(missing.text, /Request not found/);

  // পথের মান দিয়ে নতুন query প্যারামিটার ঢোকানো যায় না
  const inj = await call(port, 'GET', '/r/x&assetlinks=1');
  assert.strictEqual(inj.status, 404);
  assert.match(inj.headers['content-type'], /^text\/html/);

  const rx = await call(port, 'GET', '/rx/ABCDEFGH23');
  assert.strictEqual(rx.status, 404);
  assert.match(rx.text, /No prescription/);
  assert.strictEqual(rx.headers['cache-control'], 'no-store');

  const shop = await call(port, 'GET', '/shop/ABC234');
  assert.strictEqual(shop.status, 404);
  assert.match(shop.text, /No pharmacy/);

  const al = await call(port, 'GET', '/.well-known/assetlinks.json');
  assert.strictEqual(al.status, 200);
  assert.match(al.headers['content-type'], /^application\/json/);
  assert.strictEqual(al.json[0].target.package_name, 'com.bspc.blood');

  const adm = await call(port, 'GET', '/admin');
  assert.strictEqual(adm.status, 200);
  assert.match(adm.headers['content-type'], /^text\/html/);
  assert.strictEqual(adm.text, fs.readFileSync(path.join(SERVER, 'public/admin/index.html'), 'utf8'));
  assert.match(adm.headers['content-security-policy'], /default-src 'none'/);
  assert.strictEqual(adm.headers['x-frame-options'], 'DENY');
  assert.strictEqual(adm.headers['x-robots-tag'], 'noindex, nofollow');
  assert.strictEqual(adm.headers['cache-control'], 'no-cache');

  const fav = await call(port, 'GET', '/favicon.ico');
  assert.strictEqual(fav.status, 200);
  assert.strictEqual(fav.headers['content-type'], 'image/svg+xml');
});

test('স্থির ফাইল আর /w/ ওয়েবের SPA fallback', async () => {
  const port = app.port;
  const indexHtml = fs.readFileSync(path.join(SERVER, 'public/w/index.html'), 'utf8');
  for (const p of ['/', '/services', '/doctors/abc?x=1', '/w/index.html', '/w/']) {
    const r = await call(port, 'GET', p);
    assert.strictEqual(r.status, 200, p);
    assert.strictEqual(r.text, indexHtml, p);
    assert.match(r.headers['content-type'], /^text\/html; charset=utf-8/);
    assert.strictEqual(r.headers['cache-control'], 'no-cache', p);
    assert.match(r.headers['content-security-policy'], /connect-src 'self' https:\/\/firestore/, p);
    assert.strictEqual(r.headers['x-frame-options'], 'DENY');
  }

  const asset = fs.readdirSync(path.join(SERVER, 'public/w/assets')).find((f) => f.endsWith('.js'));
  const a = await call(port, 'GET', `/w/assets/${asset}`);
  assert.strictEqual(a.status, 200);
  assert.match(a.headers['content-type'], /^application\/javascript/);
  assert.strictEqual(a.headers['cache-control'], 'public, max-age=31536000, immutable');
  assert.strictEqual(a.headers['content-security-policy'], undefined);
  assert.ok(a.headers.etag);
  const again = await call(port, 'GET', `/w/assets/${asset}`, { headers: { 'if-none-match': a.headers.etag } });
  assert.strictEqual(again.status, 304);
  assert.strictEqual(again.text, '');

  const head = await call(port, 'HEAD', `/w/assets/${asset}`);
  assert.strictEqual(head.status, 200);
  assert.strictEqual(head.text, '');

  const font = fs.readdirSync(path.join(SERVER, 'public/w/assets')).find((f) => f.endsWith('.woff2'));
  if (font) assert.strictEqual((await call(port, 'GET', `/w/assets/${font}`)).headers['content-type'], 'font/woff2');
  const mf = await call(port, 'GET', '/w/manifest.webmanifest');
  assert.match(mf.headers['content-type'], /^application\/manifest\+json/);
  const css = await call(port, 'GET', '/admin/style.css');
  assert.match(css.headers['content-type'], /^text\/css/);

  const post = await call(port, 'POST', '/', { body: 'x' });
  assert.strictEqual(post.status, 405);
});

test('404: অজানা API, /w/-এর ভেতরে না থাকা ফাইল, পথ দিয়ে বাইরে যাওয়ার চেষ্টা', async () => {
  const port = app.port;
  for (const p of ['/api/nothing', '/api/', '/api/cron', '/w/missing.js', '/w/assets/nope.css', '/r/a/b', '/w/..%2f..%2fvercel.json', '/w/%2e%2e%2fpackage.json']) {
    const r = await call(port, 'GET', p);
    assert.strictEqual(r.status, 404, `${p} -> ${r.status}`);
    assert.match(r.text, /NOT_FOUND|not found/i, p);
  }
  // স্বাভাবিক করা পথ (/w/../vercel.json → /vercel.json) public-এর বাইরে যায় না, SPA পাতা আসে
  for (const p of ['/w/../vercel.json', '/w/%2e%2e/%2e%2e/package.json', '/w/%2E%2E/api/health.js']) {
    const t = await call(port, 'GET', p);
    assert.ok(!t.text.includes('"rewrites"') && !t.text.includes('"dependencies"') && !t.text.includes('module.exports'), p);
  }
  // nested API রাউট পাওয়া যায় (GET তাই হ্যান্ডলার নিজেই ফিরিয়ে দেয়)
  const vc = await call(port, 'GET', '/api/auth/verify-code');
  assert.ok([400, 405].includes(vc.status), String(vc.status));
});

test('ক্রন: গোপন চাবি ছাড়া 401, চাবিসহ চলে (cron.js দিয়েও)', async () => {
  const port = app.port;
  const no = await call(port, 'GET', '/api/cron/daily');
  assert.strictEqual(no.status, 401);
  const wrong = await call(port, 'GET', '/api/cron/daily', { headers: { authorization: 'Bearer wrong' } });
  assert.strictEqual(wrong.status, 401);
  const ok = await call(port, 'GET', '/api/cron/daily', { headers: { authorization: 'Bearer test-cron-secret' } });
  assert.strictEqual(ok.status, 200, ok.text.slice(0, 500));
  assert.strictEqual(ok.json.ok, true);

  const viaRunner = await runCron('/api/cron/daily', { CRON_SECRET: 'test-cron-secret', PORT: String(port) });
  assert.strictEqual(viaRunner.status, 200, viaRunner.body.slice(0, 500));
  await assert.rejects(runCron('/api/cron/daily', { PORT: String(port) }), /CRON_SECRET/);
  const bad = await runCron('/api/cron/daily', { CRON_SECRET: 'nope', PORT: String(port) });
  assert.strictEqual(bad.status, 401);
});

// ---------- req/res সাহায্যকারী: আলাদা ছোট সার্ভার ফোল্ডার দিয়ে ----------
function fixtureDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lifolo-standalone-'));
  fs.mkdirSync(path.join(dir, 'api/nested'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'public/w'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'vercel.json'), JSON.stringify({
    functions: { 'api/slow.js': { maxDuration: 1 } },
    redirects: [{ source: '/old/:id', destination: '/r/:id', permanent: true }],
    rewrites: [{ source: '/e/:id', destination: '/api/echo?id=:id&fixed=1' }],
  }));
  fs.writeFileSync(path.join(dir, 'api/echo.js'), `module.exports = (req, res) => {
    res.status(201).json({ method: req.method, query: req.query, body: req.body === undefined ? '__undef' : Buffer.isBuffer(req.body) ? 'buf:' + req.body.length : req.body,
      cookies: req.cookies, ip: req.headers['x-forwarded-for'] });
  };`);
  fs.writeFileSync(path.join(dir, 'api/text.js'), "module.exports = (req, res) => { res.setHeader('X-A', 'b'); res.status(200).send('<p>hi</p>'); };");
  fs.writeFileSync(path.join(dir, 'api/go.js'), "module.exports = (req, res) => res.redirect('/w/');");
  fs.writeFileSync(path.join(dir, 'api/throws.js'), "module.exports = async () => { throw new Error('boom'); };");
  fs.writeFileSync(path.join(dir, 'api/bg.js'), `module.exports = (req, res) => {
    const ctx = globalThis[Symbol.for('@vercel/request-context')]?.get?.();
    ctx.waitUntil(new Promise((r) => setTimeout(r, 50)).then(() => { globalThis.__bgDone = true; }));
    res.status(200).json({ ok: true });
  };`);
  fs.writeFileSync(path.join(dir, 'api/slow.js'), "module.exports = (req, res) => { setTimeout(() => res.status(200).json({ late: true }), 3000); };");
  fs.writeFileSync(path.join(dir, 'api/_private.js'), "module.exports = (req, res) => res.status(200).send('secret');");
  fs.writeFileSync(path.join(dir, 'api/nested/deep.js'), "module.exports = (req, res) => res.status(200).json({ deep: true });");
  fs.writeFileSync(path.join(dir, 'public/w/a.txt'), 'hello');
  fs.writeFileSync(path.join(dir, 'outside-secret.txt'), 'TOP SECRET');
  fs.symlinkSync(path.join(dir, 'outside-secret.txt'), path.join(dir, 'public/w/leak.txt'));
  return dir;
}

test('req/res সাহায্যকারী Vercel-এর মতো: body, query, cookies, IP, send, redirect, সময়সীমা, সীমা', async () => {
  const dir = fixtureDir();
  const fx = await start({ port: 0, serverDir: dir });
  const port = fx.port;
  try {
    const j = await postJson(port, '/api/echo?a=1&a=2&b=x', { hello: 'বিশ্ব' }, { cookie: 'lf_s=abc%20d; other=2', 'x-forwarded-for': '6.6.6.6, 9.8.7.6' });
    assert.strictEqual(j.status, 201);
    assert.deepStrictEqual(j.json.query, { a: ['1', '2'], b: 'x' });
    assert.deepStrictEqual(j.json.body, { hello: 'বিশ্ব' });
    assert.deepStrictEqual(j.json.cookies, { lf_s: 'abc d', other: '2' });
    // nginx (লোকালহোস্ট) যেটা শেষে বসিয়েছে সেটাই — ক্লায়েন্টের বানানো প্রথম মান নয়
    assert.strictEqual(j.json.ip, '9.8.7.6');

    const noXff = await call(port, 'GET', '/api/echo');
    assert.strictEqual(noXff.json.ip, '127.0.0.1');
    assert.strictEqual(noXff.json.body, '__undef');

    const form = await call(port, 'POST', '/api/echo', { headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: 'x=1&y=%E0%A6%95' });
    assert.deepStrictEqual(form.json.body, { x: '1', y: 'ক' });
    const txt = await call(port, 'POST', '/api/echo', { headers: { 'content-type': 'text/plain' }, body: 'plain' });
    assert.strictEqual(txt.json.body, 'plain');
    const bin = await call(port, 'POST', '/api/echo', { headers: { 'content-type': 'application/octet-stream' }, body: Buffer.alloc(10) });
    assert.strictEqual(bin.json.body, 'buf:10');

    const rw = await call(port, 'GET', '/e/q1?id=evil&extra=2');
    assert.deepStrictEqual(rw.json.query, { id: 'q1', fixed: '1', extra: '2' });

    const rd = await call(port, 'GET', '/old/z9');
    assert.strictEqual(rd.status, 308);
    assert.strictEqual(rd.headers.location, '/r/z9');

    const t = await call(port, 'GET', '/api/text');
    assert.strictEqual(t.headers['content-type'], 'text/html; charset=utf-8');
    assert.strictEqual(t.headers['x-a'], 'b');
    assert.strictEqual(t.text, '<p>hi</p>');
    assert.ok(t.headers.etag);
    assert.strictEqual((await call(port, 'GET', '/api/text', { headers: { 'if-none-match': t.headers.etag } })).status, 304);

    const go = await call(port, 'GET', '/api/go');
    assert.strictEqual(go.status, 307);
    assert.strictEqual(go.headers.location, '/w/');

    const th = await call(port, 'GET', '/api/throws');
    assert.strictEqual(th.status, 500);
    assert.match(th.text, /FUNCTION_INVOCATION_FAILED/);

    globalThis.__bgDone = false;
    const bg = await call(port, 'GET', '/api/bg');
    assert.strictEqual(bg.status, 200);
    assert.strictEqual(fx.background.size, 1);
    await new Promise((r) => setTimeout(r, 120));
    assert.strictEqual(globalThis.__bgDone, true);
    assert.strictEqual(fx.background.size, 0);

    assert.strictEqual((await call(port, 'GET', '/api/nested/deep')).json.deep, true);
    assert.strictEqual((await call(port, 'GET', '/api/_private')).status, 404);
    assert.strictEqual((await call(port, 'GET', '/w/a.txt')).text, 'hello');
    const leak = await call(port, 'GET', '/w/leak.txt');
    assert.strictEqual(leak.status, 404);
    assert.ok(!leak.text.includes('SECRET'));

    const started = Date.now();
    const slow = await call(port, 'GET', '/api/slow');
    assert.strictEqual(slow.status, 504);
    assert.match(slow.text, /FUNCTION_INVOCATION_TIMEOUT/);
    assert.ok(Date.now() - started < 2500);

    const big = await call(port, 'POST', '/api/echo', { headers: { 'content-type': 'application/json' }, body: Buffer.alloc(5 * 1024 * 1024, 32) });
    assert.strictEqual(big.status, 413);
  } finally {
    await fx.shutdown(5000);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('বন্ধ করার সময় চলতি অনুরোধ শেষ হয়, তারপর নতুন সংযোগ নেয় না', async () => {
  const dir = fixtureDir();
  fs.writeFileSync(path.join(dir, 'api/wait.js'), "module.exports = (req, res) => { setTimeout(() => res.status(200).json({ done: true }), 300); };");
  const fx = await start({ port: 0, serverDir: dir });
  try {
    const pending = call(fx.port, 'GET', '/api/wait');
    await new Promise((r) => setTimeout(r, 50));
    const stopping = fx.shutdown(5000);
    const r = await pending;
    assert.strictEqual(r.status, 200);
    assert.deepStrictEqual(r.json, { done: true });
    await stopping;
    await assert.rejects(call(fx.port, 'GET', '/healthz'));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
