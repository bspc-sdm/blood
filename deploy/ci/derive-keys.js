#!/usr/bin/env node
// VPS-এর SSH চাবি একটা গোপন মান (Contabo-র root পাসওয়ার্ড, VPS_ROOT_PASSWORD) থেকে বানানো —
// প্রতিবার একই চাবি আসে, তাই GitHub-এ আলাদা চাবি রাখতে হয় না (workflow নিজে সিক্রেট লিখতে পারে না)।
//
//   LIFOLO_MASTER='<গোপন মান>' node deploy/ci/derive-keys.js <ফোল্ডার>
//
// ফোল্ডারে লেখে (0600):
//   host_ed25519, host_ed25519.pub   — VPS-এর SSH host key (সার্ভারে বসানো হয়; GitHub এটা দিয়েই সার্ভার চেনে)
//   root_ed25519, root_ed25519.pub   — root-এ ঢোকার চাবি
//
// scrypt (N=2^16) — পাসওয়ার্ড অনুমান করে চাবি মেলানো ধীর। লেবেল আলাদা, তাই একটা চাবি থেকে অন্যটা বের করা যায় না।
// কোনো চাবি stdout-এ ছাপা হয় না; শুধু ফাইলে।
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const SALT_PREFIX = 'lifolo-vps-v1|';
const PKCS8_ED25519_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');

/** গোপন মান স্বাভাবিক করা: শুরু/শেষের ফাঁকা আর লাইন-শেষ বাদ (পেস্ট করার সময় যা যোগ হয়) */
function normalizeMaster(s) {
  return String(s || '').replace(/\r\n?/g, '\n').trim();
}

function seedFor(master, label) {
  return crypto.scryptSync(normalizeMaster(master), SALT_PREFIX + label, 32, { N: 1 << 16, r: 8, p: 1, maxmem: 256 * 1024 * 1024 });
}

function sshString(buf) {
  const b = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(b.length);
  return Buffer.concat([len, b]);
}

function u32(n) {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n >>> 0);
  return b;
}

/** ৩২ বাইটের seed → { privateKey (OpenSSH PEM), publicKey ("ssh-ed25519 AAAA... comment") } */
function ed25519FromSeed(seed, comment) {
  if (seed.length !== 32) throw new Error('seed must be 32 bytes');
  const priv = crypto.createPrivateKey({ key: Buffer.concat([PKCS8_ED25519_PREFIX, seed]), format: 'der', type: 'pkcs8' });
  const pub = Buffer.from(crypto.createPublicKey(priv).export({ format: 'jwk' }).x, 'base64url');
  const type = 'ssh-ed25519';
  const pubBlob = Buffer.concat([sshString(type), sshString(pub)]);
  // চেক-সংখ্যা যেকোনো হতে পারে (দুটো সমান হলেই হলো) — একই চাবি সবসময় একই ফাইল হোক, তাই seed থেকে
  const check = crypto.createHash('sha256').update(seed).digest().readUInt32BE(0);
  let privSection = Buffer.concat([
    u32(check), u32(check),
    sshString(type), sshString(pub), sshString(Buffer.concat([seed, pub])), sshString(comment),
  ]);
  const pad = [];
  for (let i = 1; (privSection.length + pad.length) % 8 !== 0; i++) pad.push(i);
  privSection = Buffer.concat([privSection, Buffer.from(pad)]);
  const body = Buffer.concat([
    Buffer.from('openssh-key-v1\0', 'binary'),
    sshString('none'), sshString('none'), sshString(''),
    u32(1), sshString(pubBlob), sshString(privSection),
  ]);
  const b64 = body.toString('base64').match(/.{1,70}/g).join('\n');
  return {
    privateKey: `-----BEGIN OPENSSH PRIVATE KEY-----\n${b64}\n-----END OPENSSH PRIVATE KEY-----\n`,
    publicKey: `${type} ${pubBlob.toString('base64')} ${comment}`,
  };
}

function derive(master) {
  if (!normalizeMaster(master)) throw new Error('empty master secret');
  return {
    host: ed25519FromSeed(seedFor(master, 'host'), 'lifolo-host'),
    root: ed25519FromSeed(seedFor(master, 'root-client'), 'lifolo-github-root'),
  };
}

function writeKeys(dir, keys) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  for (const [name, k] of Object.entries(keys)) {
    fs.writeFileSync(path.join(dir, `${name}_ed25519`), k.privateKey, { mode: 0o600 });
    fs.writeFileSync(path.join(dir, `${name}_ed25519.pub`), `${k.publicKey}\n`, { mode: 0o600 });
  }
}

module.exports = { derive, ed25519FromSeed, normalizeMaster };

if (require.main === module) {
  const dir = process.argv[2];
  if (!dir) {
    console.error('usage: LIFOLO_MASTER=... node derive-keys.js <dir>');
    process.exit(2);
  }
  writeKeys(dir, derive(process.env.LIFOLO_MASTER));
}
