// deploy/ci/livekit-keys.js (কল সার্ভারের LiveKit চাবি) — node --test deploy/ci/test/livekit-keys.test.js
const test = require('node:test');
const assert = require('assert');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const L = require('../livekit-keys');
const D = require('../derive-keys');
const B = require('../backup-key');

test('চাবি: একই পাসওয়ার্ড → একই key/secret; পেস্টের ফাঁকা/\\r\\n উপেক্ষা; আলাদা পাসওয়ার্ড → আলাদা', () => {
  const a = L.livekitKeys('Call-Pass-1');
  const b = L.livekitKeys('  Call-Pass-1\r\n');
  assert.deepStrictEqual(a, b);
  assert.match(a.apiKey, /^API[0-9A-Za-z]{12}$/);
  assert.match(a.apiSecret, /^[0-9A-Za-z]{43}$/);
  const c = L.livekitKeys('Call-Pass-2');
  assert.notStrictEqual(c.apiKey, a.apiKey);
  assert.notStrictEqual(c.apiSecret, a.apiSecret);
  assert.throws(() => L.livekitKeys(' '));
});

test('চাবি: SSH/ব্যাকআপের চাবির সাথে মেলে না (লেবেল আলাদা)', () => {
  const k = L.livekitKeys('Same-Pass');
  const backup = B.backupPassphrase('Same-Pass');
  const ssh = D.derive('Same-Pass');
  assert.ok(!backup.includes(k.apiSecret));
  assert.ok(!ssh.root.privateKey.includes(k.apiSecret));
});

test('base62: নির্দিষ্ট দৈর্ঘ্য, শূন্য বাইটেও', () => {
  assert.strictEqual(L.base62(Buffer.alloc(16), 12), '000000000000');
  assert.strictEqual(L.base62(Buffer.from([61]), 2), '0z');
});

test('টোকেন: HS256 সই মেলে, LiveKit-এর grant আর মেয়াদ', () => {
  const { apiKey, apiSecret } = L.livekitKeys('Call-Pass-1');
  const t = L.accessToken({ apiKey, apiSecret, room: 'c_x', identity: 'u1', name: 'A', ttlSec: 120 });
  const [h, p, s] = t.split('.');
  assert.strictEqual(crypto.createHmac('sha256', apiSecret).update(`${h}.${p}`).digest('base64url'), s);
  const body = JSON.parse(Buffer.from(p, 'base64url'));
  assert.strictEqual(body.iss, apiKey);
  assert.strictEqual(body.sub, 'u1');
  assert.deepStrictEqual({ room: body.video.room, roomJoin: body.video.roomJoin }, { room: 'c_x', roomJoin: true });
  assert.ok(body.exp - body.nbf <= 130 && body.exp > Date.now() / 1000);
  assert.strictEqual(body.video.roomRecord, undefined);
  assert.throws(() => L.accessToken({ apiKey, apiSecret, room: '', identity: 'u' }));
});

test('CLI: --env আর --keyfile (0600), stdout-এ চাবি নেই; CALL_DOMAIN', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lifolo-lk-'));
  const env = path.join(dir, 'provided.env');
  const kf = path.join(dir, 'keys.yaml');
  fs.writeFileSync(env, 'X=1\n');
  const script = path.join(__dirname, '..', 'livekit-keys.js');
  const out = execFileSync('node', [script, '--env', env, '--keyfile', kf], {
    env: { ...process.env, LIFOLO_MASTER: 'Call-Pass-1', CALL_DOMAIN: 'call.example.org' },
  }).toString();
  assert.strictEqual(out, '');
  const k = L.livekitKeys('Call-Pass-1');
  assert.strictEqual(fs.readFileSync(env, 'utf8'),
    `X=1\nLIVEKIT_URL=wss://call.example.org\nLIVEKIT_API_KEY=${k.apiKey}\nLIVEKIT_API_SECRET=${k.apiSecret}\n`);
  assert.strictEqual(fs.readFileSync(kf, 'utf8'), `${k.apiKey}: ${k.apiSecret}\n`);
  assert.strictEqual(fs.statSync(kf).mode & 0o777, 0o600);
  assert.strictEqual(fs.statSync(env).mode & 0o777, 0o600);
  const tf = path.join(dir, 'token');
  execFileSync('node', [script, '--token', tf], { env: { ...process.env, LIFOLO_MASTER: 'Call-Pass-1' } });
  const tb = JSON.parse(Buffer.from(fs.readFileSync(tf, 'utf8').trim().split('.')[1], 'base64url'));
  assert.deepStrictEqual([tb.iss, tb.video.room, tb.video.canPublish, tb.video.canSubscribe], [k.apiKey, 'lifolo-health', false, false]);
  assert.strictEqual(fs.statSync(tf).mode & 0o777, 0o600);
  const mask = execFileSync('node', [script, '--mask'], { env: { ...process.env, LIFOLO_MASTER: 'Call-Pass-1' } }).toString();
  assert.strictEqual(mask, `::add-mask::${k.apiKey}\n::add-mask::${k.apiSecret}\n`);
  assert.throws(() => execFileSync('node', [script, '--env', env], { env: { ...process.env, LIFOLO_MASTER: '' }, stdio: 'ignore' }));
  assert.throws(() => execFileSync('node', [script, '--env', env], {
    env: { ...process.env, LIFOLO_MASTER: 'x', CALL_DOMAIN: 'bad domain' }, stdio: 'ignore',
  }));
  fs.rmSync(dir, { recursive: true });
});
