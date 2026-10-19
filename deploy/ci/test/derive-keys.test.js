// deploy/ci/derive-keys.js (Setup VPS (base) workflow-এর SSH চাবি) — node --test deploy/ci/test/*.test.js
const test = require('node:test');
const assert = require('assert');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const K = require('../derive-keys');

test('চাবি: একই গোপন মান → একই চাবি; আলাদা মান/লেবেল → আলাদা; পেস্টের \\r\\n আর ফাঁকা উপেক্ষা', () => {
  const a = K.derive('Pass-123');
  const b = K.derive('  Pass-123\r\n');
  assert.strictEqual(a.host.publicKey, b.host.publicKey);
  assert.strictEqual(a.root.privateKey, b.root.privateKey);
  assert.notStrictEqual(a.host.publicKey.split(' ')[1], a.root.publicKey.split(' ')[1]);
  assert.notStrictEqual(K.derive('Pass-124').root.publicKey, a.root.publicKey);
  assert.throws(() => K.derive('  '));
});

test('চাবি: OpenSSH-এর ফাইল হিসেবে চলে, আর সই যাচাই হয় (ssh-keygen থাকলে)', (t) => {
  const k = K.derive('Pass-123').root;
  assert.match(k.privateKey, /^-----BEGIN OPENSSH PRIVATE KEY-----\n[\s\S]+\n-----END OPENSSH PRIVATE KEY-----\n$/);
  assert.match(k.publicKey, /^ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI[A-Za-z0-9+/=]+ lifolo-github-root$/);
  try {
    execFileSync('ssh-keygen', ['-?'], { stdio: 'ignore' });
  } catch (e) {
    if (e.code === 'ENOENT') return t.skip('ssh-keygen নেই');
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lifolo-key-'));
  const f = path.join(dir, 'k');
  fs.writeFileSync(f, k.privateKey, { mode: 0o600 });
  assert.strictEqual(execFileSync('ssh-keygen', ['-y', '-f', f]).toString().trim(), k.publicKey);
  fs.rmSync(dir, { recursive: true });
});

test('চাবি: seed থেকে বানানো ed25519 আসল সই দেয় (Node-এর crypto দিয়ে যাচাই)', () => {
  const seed = crypto.randomBytes(32);
  const k = K.ed25519FromSeed(seed, 'c');
  const pub = Buffer.from(k.publicKey.split(' ')[1], 'base64').subarray(-32);
  const priv = crypto.createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed]), format: 'der', type: 'pkcs8' });
  const sig = crypto.sign(null, Buffer.from('hi'), priv);
  const pubKey = crypto.createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: pub.toString('base64url') }, format: 'jwk' });
  assert.ok(crypto.verify(null, Buffer.from('hi'), pubKey, sig));
});
