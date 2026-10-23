// desktop/scripts/updater-key.js — node --test desktop/scripts/test/*.test.js
// Tauri CLI (desktop/node_modules) থাকলে আসল `tauri signer sign` দিয়ে সই করে, Node-এর ed25519 দিয়ে সই যাচাই।
const test = require('node:test');
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const K = require('../updater-key');

const SPKI_ED25519_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

function pubFromBox(b64) {
  const lines = Buffer.from(b64, 'base64').toString().split('\n');
  assert.match(lines[0], /^untrusted comment: minisign public key: [0-9A-F]{16}$/);
  const raw = Buffer.from(lines[1], 'base64');
  assert.strictEqual(raw.length, 42);
  assert.strictEqual(raw.subarray(0, 2).toString(), 'Ed');
  return { keynum: raw.subarray(2, 10), pk: crypto.createPublicKey({ key: Buffer.concat([SPKI_ED25519_PREFIX, raw.subarray(10)]), format: 'der', type: 'spki' }) };
}

test('BLAKE2b: RFC/OpenSSL-এর সাথে মেলে', () => {
  assert.strictEqual(K.blake2b(Buffer.alloc(0), 32).toString('hex'), '0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8');
  for (const n of [0, 3, 127, 128, 129, 256, 1000]) {
    const d = crypto.randomBytes(n);
    assert.ok(K.blake2b(d, 64).equals(crypto.createHash('blake2b512').update(d).digest()), `len ${n}`);
  }
});

test('scrypt-এর মান minisign-এর মতো (N=2^15, r=8, p=1)', () => {
  assert.deepStrictEqual(K.scryptParams(1048576n, 33554432n), { N: 32768, r: 8, p: 1 });
});

test('চাবি: একই গোপন মান → একই চাবি; আলাদা মান → আলাদা; ফাঁকা নয়', () => {
  const a = K.deriveUpdaterKey('Pass-123');
  const b = K.deriveUpdaterKey('  Pass-123\r\n');
  assert.deepStrictEqual(a, b);
  assert.notStrictEqual(K.deriveUpdaterKey('Pass-124').publicKey, a.publicKey);
  assert.throws(() => K.deriveUpdaterKey(' '));
  const sk = Buffer.from(a.privateKey, 'base64').toString().split('\n');
  assert.strictEqual(sk[0], 'untrusted comment: rsign encrypted secret key');
  const raw = Buffer.from(sk[1], 'base64');
  assert.strictEqual(raw.length, 2 + 2 + 2 + 32 + 8 + 8 + 8 + 64 + 32);
  assert.strictEqual(raw.subarray(0, 6).toString(), 'EdScB2');
  pubFromBox(a.publicKey);
});

test('Tauri CLI চাবিটা খুলে সই করে, আর সই এই public key দিয়ে যাচাই হয়', (t) => {
  const bin = path.join(__dirname, '..', '..', 'node_modules', '.bin', process.platform === 'win32' ? 'tauri.cmd' : 'tauri');
  if (!fs.existsSync(bin)) return t.skip('desktop/node_modules-এ Tauri CLI নেই (npm ci)');
  const k = K.deriveUpdaterKey('Pass-123');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lifolo-upd-'));
  const f = path.join(dir, 'lifolo-desktop-1.0.0.exe');
  const data = crypto.randomBytes(5000);
  fs.writeFileSync(f, data);
  execFileSync(bin, ['signer', 'sign', '-k', k.privateKey, '-p', k.password, f], { stdio: 'pipe', shell: process.platform === 'win32' });
  const sig = Buffer.from(fs.readFileSync(`${f}.sig`, 'utf8'), 'base64').toString().split('\n');
  const s = Buffer.from(sig[1], 'base64');
  const { keynum, pk } = pubFromBox(k.publicKey);
  assert.ok(s.subarray(2, 10).equals(keynum), 'key id');
  const alg = s.subarray(0, 2).toString();
  const msg = alg === 'ED' ? K.blake2b(data, 64) : data;
  assert.ok(crypto.verify(null, msg, pk, s.subarray(10)), 'file signature');
  const trusted = sig[2].replace(/^trusted comment: /, '');
  assert.ok(crypto.verify(null, Buffer.concat([s.subarray(10), Buffer.from(trusted)]), pk, Buffer.from(sig[3], 'base64')), 'global signature');
  fs.rmSync(dir, { recursive: true });
});
