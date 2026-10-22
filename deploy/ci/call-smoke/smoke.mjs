// কল সার্ভারের (LiveKit) smoke test: দুটো headless Chromium অংশগ্রহণকারী একই ঘরে ঢুকে নকল ক্যামেরা/মাইক publish করে,
// আর দুজনেই অন্যজনের অডিও+ভিডিও পায় কি না দেখা হয়। মোড:
//   direct     — যে পথ পাওয়া যায় (UDP/TCP সরাসরি); সাথে ৩য় জন ঢুকতে পারে না কি না (ঘরে সর্বোচ্চ ২ জন)
//   relay-udp  — শুধু TURN/UDP (3478) দিয়ে (iceTransportPolicy=relay)
//   relay-tls  — শুধু TURN/TLS (turns:…:443) দিয়ে — আলাদা কল-সার্ভারে (dedicated) থাকলে
//
// পরিবেশ:
//   LIVEKIT_URL           wss://lifolo.com (সার্ভার /rtc নিজে যোগ করে)
//   LIVEKIT_API_KEY/SECRET  বা  LIFOLO_MASTER (তাহলে deploy/ci/livekit-keys.js দিয়ে বানানো)
//   SMOKE_MODES           কমা দিয়ে (ডিফল্ট direct,relay-udp)
//   SMOKE_OUT             ফলের JSON (ডিফল্ট smoke-result.json); SMOKE_MD — বাংলা সারণি (markdown)
//   SMOKE_INSECURE=1      নিজে-সই করা সার্টিফিকেট মানা (শুধু CI-র পরীক্ষা)
//   SMOKE_RESOLVE         "host=ip" — Chromium-এ নামটা এই IP-তে (Cloudflare আটকালে SSH tunnel দিয়ে origin-এ)
//   SMOKE_TIMEOUT_MS      প্রতি মোডে অপেক্ষা (ডিফল্ট 45000)
// সব মোড পাস করলে exit 0।
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { chromium } from 'playwright';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const LK = require('../livekit-keys.js');

const URL_ = String(process.env.LIVEKIT_URL || '').trim();
if (!/^wss?:\/\/[A-Za-z0-9.:-]+\/?$/.test(URL_)) {
  console.error('LIVEKIT_URL দিন (যেমন wss://lifolo.com)');
  process.exit(2);
}
let apiKey = process.env.LIVEKIT_API_KEY;
let apiSecret = process.env.LIVEKIT_API_SECRET;
if (!apiKey || !apiSecret) ({ apiKey, apiSecret } = LK.livekitKeys(process.env.LIFOLO_MASTER));
const MODES = String(process.env.SMOKE_MODES || 'direct,relay-udp').split(',').map((s) => s.trim()).filter(Boolean);
const TIMEOUT = Number(process.env.SMOKE_TIMEOUT_MS || 45000);
const OUT = process.env.SMOKE_OUT || 'smoke-result.json';

// পাতা আর livekit-client একটা ছোট লোকাল সার্ভার থেকে (127.0.0.1 = secure context, getUserMedia চলে)
const files = {
  '/': ['text/html', readFileSync(join(here, 'page.html'))],
  '/livekit-client.umd.js': ['text/javascript', readFileSync(join(here, 'node_modules/livekit-client/dist/livekit-client.umd.js'))],
};
const server = createServer((req, res) => {
  const f = files[req.url.split('?')[0]];
  if (!f) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'content-type': f[0] }).end(f[1]);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const PAGE = `http://127.0.0.1:${server.address().port}/`;

const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'];
if (process.env.SMOKE_INSECURE === '1') args.push('--ignore-certificate-errors');
if (process.env.SMOKE_RESOLVE) {
  const [h, ip] = process.env.SMOKE_RESOLVE.split('=');
  if (/^[a-z0-9.-]+$/.test(h || '') && /^[0-9.]+$/.test(ip || '')) args.push(`--host-resolver-rules=MAP ${h} ${ip}`);
}
const browser = await chromium.launch({ args });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const token = (room, identity) => LK.accessToken({ apiKey, apiSecret, room, identity, name: identity, ttlSec: 600 });

async function participant(mode, room, identity, publish = true) {
  const ctx = await browser.newContext({ ignoreHTTPSErrors: process.env.SMOKE_INSECURE === '1' });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') console.log(`  [${identity}] ${m.text().slice(0, 300)}`); });
  await page.goto(PAGE);
  await page.evaluate((m) => { window.__mode = m; }, mode);
  const state = await page.evaluate(([u, t, p]) => window.join(u, t, p), [URL_, token(room, identity), publish]);
  return { ctx, page, state };
}

function ok(mode, p) {
  if (p.state !== 'connected' || p.remotes < 1 || p.subs < 2) return false;
  if (p.inbound.audio < 2000 || p.inbound.video < 20000) return false;
  if (!p.pairs.length) return false;
  if (mode === 'relay-udp') return p.pairs.every((x) => x.local === 'relay' && x.relayProtocol === 'udp');
  if (mode === 'relay-tls') return p.pairs.every((x) => x.local === 'relay' && x.relayProtocol === 'tls');
  return true;
}

async function runMode(mode) {
  const room = `smoke-${mode}-${randomBytes(4).toString('hex')}`;
  const r = { mode, ok: false, error: null, a: null, b: null, cap: null, ms: 0 };
  const t0 = Date.now();
  const ctxs = [];
  try {
    const A = await participant(mode, room, 'smoke-a');
    ctxs.push(A.ctx);
    const B = await participant(mode, room, 'smoke-b');
    ctxs.push(B.ctx);
    while (Date.now() - t0 < TIMEOUT) {
      await sleep(2000);
      r.a = await A.page.evaluate(() => window.probe());
      r.b = await B.page.evaluate(() => window.probe());
      if (ok(mode, r.a) && ok(mode, r.b)) { r.ok = true; break; }
    }
    if (!r.ok) r.error = 'media did not flow both ways in time';
    if (mode === 'direct') {
      // ঘরে সর্বোচ্চ ২ জন: ৩য় জন ঢুকতে পারার কথা নয়
      try {
        const C = await participant(mode, room, 'smoke-c', false);
        ctxs.push(C.ctx);
        r.cap = 'ঢুকে গেল ❌';
        r.ok = false;
        r.error = r.error || 'room cap (2) not enforced';
      } catch (e) {
        // সার্ভার কারণ জানায় না (সংযোগ বন্ধ করে দেয়) — তাই নিয়ন্ত্রণ: একই ৩য় জন আরেকটা খালি ঘরে ঢুকতে পারে কি না
        try {
          const D = await participant(mode, `${room}-ctl`, 'smoke-c', false);
          ctxs.push(D.ctx);
          r.cap = 'ঘর ভরা, ফেরানো হলো ✅ (খালি ঘরে ঠিকই ঢোকে)';
        } catch (e2) {
          r.ok = false;
          r.error = r.error || `third participant cannot join any room: ${String(e2.message || e2).slice(0, 120)}`;
          r.cap = `refused: ${String(e.message || e).split('\n')[0].slice(0, 120)}`;
        }
      }
    }
  } catch (e) {
    r.error = String(e.message || e).split('\n')[0].slice(0, 300);
  } finally {
    r.ms = Date.now() - t0;
    for (const c of ctxs) await c.close().catch(() => {});
  }
  return r;
}

const results = [];
for (const m of MODES) {
  console.log(`== ${m}`);
  const r = await runMode(m);
  console.log(JSON.stringify(r, null, 1));
  results.push(r);
}
await browser.close();
server.close();
writeFileSync(OUT, JSON.stringify(results, null, 2));

const label = {
  direct: 'সরাসরি (UDP/TCP)',
  'relay-udp': 'শুধু TURN/UDP 3478 (relay)',
  'relay-tls': 'শুধু TURN/TLS 443 (relay)',
};
const pathOf = (p) => (p && p.pairs.length ? [...new Set(p.pairs.map((x) => `${x.local}/${x.relayProtocol || x.protocol}`))].join(', ') : '—');
const kb = (n) => `${Math.round((n || 0) / 1024)} KB`;
if (process.env.SMOKE_MD) {
  const lines = [
    '| পরীক্ষা | ফল | A-র পথ | B-র পথ | A পেল (অডিও/ভিডিও) | B পেল (অডিও/ভিডিও) | সময় | মন্তব্য |',
    '|---|---|---|---|---|---|---|---|',
  ];
  for (const r of results) {
    const note = [r.error, r.cap ? `৩য় জন: ${r.cap}` : ''].filter(Boolean).join('; ').replace(/\|/g, '/');
    lines.push(`| ${label[r.mode] || r.mode} | ${r.ok ? '✅' : '❌'} | ${pathOf(r.a)} | ${pathOf(r.b)} | ${r.a ? `${kb(r.a.inbound.audio)} / ${kb(r.a.inbound.video)}` : '—'} | ${r.b ? `${kb(r.b.inbound.audio)} / ${kb(r.b.inbound.video)}` : '—'} | ${Math.round(r.ms / 1000)} s | ${note || ''} |`);
  }
  const ice = results.find((r) => r.a && r.a.ice.length)?.a.ice.flat() || [];
  lines.push('', `সার্ভারের দেওয়া ICE/TURN ঠিকানার ধরন: ${[...new Set(ice)].map((u) => `\`${u}\``).join(', ') || '—'}`);
  writeFileSync(process.env.SMOKE_MD, `${lines.join('\n')}\n`);
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
