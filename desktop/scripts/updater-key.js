#!/usr/bin/env node
// ডেস্কটপ অ্যাপের আপডেট সই করার চাবি (Tauri updater, minisign) — VPS_ROOT_PASSWORD থেকে বানানো
// (deploy/ci/derive-keys.js আর backup-key.js-এর মতোই), তাই মালিককে নতুন কোনো সিক্রেট রাখতে হয় না। একই পাসওয়ার্ড → সবসময় একই চাবি,
// তাই পুরোনো অ্যাপ নতুন আপডেটের সই চিনতে পারে।
//
//   LIFOLO_MASTER='<VPS_ROOT_PASSWORD>' node desktop/scripts/updater-key.js <ফোল্ডার>
//
// ফোল্ডারে লেখে (0600), stdout-এ কিছু নয়:
//   updater.key           TAURI_SIGNING_PRIVATE_KEY (minisign secret key box, base64) — scrypt দিয়ে এনক্রিপ্ট করা
//   updater.key.password  TAURI_SIGNING_PRIVATE_KEY_PASSWORD
//   updater.pub           tauri.conf.json → plugins.updater.pubkey (public key box, base64) — গোপন নয়
//
// scrypt (N=2^16), লেবেল আলাদা ("desktop-updater…") — SSH বা ব্যাকআপের চাবি থেকে এটা, বা এটা থেকে সেগুলো বের করা যায় না।
//
// ⚠ VPS_ROOT_PASSWORD বদলালে চাবিও বদলায়: তখন বসানো অ্যাপ নতুন আপডেট নেবে না — lifolo.com/download থেকে একবার হাতে বসাতে হবে।
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { normalizeMaster } = require('../../deploy/ci/derive-keys.js');

const SALT_PREFIX = 'lifolo-vps-v1|';
const PKCS8_ED25519_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');
// minisign-এর নিজের মান (minisign crate constants.rs): Tauri CLI এই চাবিই পড়ে
const OPSLIMIT = 1048576n;
const MEMLIMIT = 33554432n;

function kdf(master, label, len) {
  return crypto.scryptSync(normalizeMaster(master), SALT_PREFIX + label, len, { N: 1 << 16, r: 8, p: 1, maxmem: 256 * 1024 * 1024 });
}

// ---------------------------------------------------------------- BLAKE2b (RFC 7693) — minisign-এর checksum (৩২ বাইট)
const M64 = (1n << 64n) - 1n;
const IV = [
  0x6a09e667f3bcc908n, 0xbb67ae8584caa73bn, 0x3c6ef372fe94f82bn, 0xa54ff53a5f1d36f1n,
  0x510e527fade682d1n, 0x9b05688c2b3e6c1fn, 0x1f83d9abfb41bd6bn, 0x5be0cd19137e2179n,
];
const SIGMA = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15], [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
  [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4], [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
  [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13], [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
  [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11], [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
  [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5], [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
];
const rotr = (x, n) => ((x >> n) | (x << (64n - n))) & M64;

function blake2b(data, outLen = 32) {
  const h = IV.slice();
  h[0] ^= 0x01010000n ^ BigInt(outLen);
  const blocks = Math.max(1, Math.ceil(data.length / 128));
  for (let b = 0; b < blocks; b++) {
    const chunk = Buffer.alloc(128);
    data.copy(chunk, 0, b * 128, Math.min(data.length, (b + 1) * 128));
    const m = [];
    for (let i = 0; i < 16; i++) m.push(chunk.readBigUInt64LE(i * 8));
    const last = b === blocks - 1;
    const t = BigInt(last ? data.length : (b + 1) * 128);
    const v = [...h, ...IV];
    v[12] ^= t & M64;
    v[13] ^= t >> 64n;
    if (last) v[14] ^= M64;
    const G = (a, bb, c, d, x, y) => {
      v[a] = (v[a] + v[bb] + x) & M64; v[d] = rotr(v[d] ^ v[a], 32n);
      v[c] = (v[c] + v[d]) & M64; v[bb] = rotr(v[bb] ^ v[c], 24n);
      v[a] = (v[a] + v[bb] + y) & M64; v[d] = rotr(v[d] ^ v[a], 16n);
      v[c] = (v[c] + v[d]) & M64; v[bb] = rotr(v[bb] ^ v[c], 63n);
    };
    for (let r = 0; r < 12; r++) {
      const s = SIGMA[r % 10];
      G(0, 4, 8, 12, m[s[0]], m[s[1]]); G(1, 5, 9, 13, m[s[2]], m[s[3]]);
      G(2, 6, 10, 14, m[s[4]], m[s[5]]); G(3, 7, 11, 15, m[s[6]], m[s[7]]);
      G(0, 5, 10, 15, m[s[8]], m[s[9]]); G(1, 6, 11, 12, m[s[10]], m[s[11]]);
      G(2, 7, 8, 13, m[s[12]], m[s[13]]); G(3, 4, 9, 14, m[s[14]], m[s[15]]);
    }
    for (let i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
  }
  const out = Buffer.alloc(64);
  h.forEach((x, i) => out.writeBigUInt64LE(x, i * 8));
  return out.subarray(0, outLen);
}

function u64le(n) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(n);
  return b;
}

/** minisign-এর raw_scrypt_params (opslimit/memlimit → N, r, p) */
function scryptParams(opslimit, memlimit) {
  const ops = opslimit < 32768n ? 32768n : opslimit;
  const r = 8n;
  let nLog2 = 1n;
  let p;
  if (ops < memlimit / 32n) {
    p = 1n;
    const maxN = ops / (r * 4n);
    while (nLog2 < 63n && !((1n << nLog2) > maxN / 2n)) nLog2++;
  } else {
    const maxN = memlimit / (r * 128n);
    while (nLog2 < 63n && !((1n << nLog2) > maxN / 2n)) nLog2++;
    let maxrp = ops / 4n / (1n << nLog2);
    if (maxrp > 0x3fffffffn) maxrp = 0x3fffffffn;
    p = maxrp / r;
  }
  return { N: Number(1n << nLog2), r: Number(r), p: Number(p) };
}

/**
 * গোপন মান → { privateKey, password, publicKey } (তিনটাই Tauri যেভাবে চায়: base64 করা minisign "box")
 */
function deriveUpdaterKey(master) {
  if (!normalizeMaster(master)) throw new Error('empty master secret');
  const seed = kdf(master, 'desktop-updater-ed25519', 32);
  const keynum = kdf(master, 'desktop-updater-keyid', 8);
  const salt = kdf(master, 'desktop-updater-kdf-salt', 32);
  const password = kdf(master, 'desktop-updater-password', 32).toString('hex');

  const priv = crypto.createPrivateKey({ key: Buffer.concat([PKCS8_ED25519_PREFIX, seed]), format: 'der', type: 'pkcs8' });
  const pub = Buffer.from(crypto.createPublicKey(priv).export({ format: 'jwk' }).x, 'base64url');
  const sigAlg = Buffer.from('Ed');
  const sk = Buffer.concat([seed, pub]); // libsodium-এর ৬৪ বাইটের secret key
  const chk = blake2b(Buffer.concat([sigAlg, keynum, sk]), 32);

  const { N, r, p } = scryptParams(OPSLIMIT, MEMLIMIT);
  const stream = crypto.scryptSync(password, salt, 8 + 64 + 32, { N, r, p, maxmem: 256 * 1024 * 1024 });
  const plain = Buffer.concat([keynum, sk, chk]);
  const enc = Buffer.alloc(plain.length);
  for (let i = 0; i < plain.length; i++) enc[i] = plain[i] ^ stream[i];

  const skBytes = Buffer.concat([sigAlg, Buffer.from('Sc'), Buffer.from('B2'), salt, u64le(OPSLIMIT), u64le(MEMLIMIT), enc]);
  const skBox = `untrusted comment: rsign encrypted secret key\n${skBytes.toString('base64')}\n`;
  const keyIdHex = Buffer.from(keynum).reverse().toString('hex').toUpperCase();
  const pkBox = `untrusted comment: minisign public key: ${keyIdHex}\n${Buffer.concat([sigAlg, keynum, pub]).toString('base64')}\n`;
  return {
    privateKey: Buffer.from(skBox).toString('base64'),
    password,
    publicKey: Buffer.from(pkBox).toString('base64'),
    keyId: keyIdHex,
  };
}

module.exports = { deriveUpdaterKey, blake2b, scryptParams };

if (require.main === module) {
  const dir = process.argv[2];
  if (!dir) {
    console.error('usage: LIFOLO_MASTER=... node updater-key.js <dir>');
    process.exit(2);
  }
  const k = deriveUpdaterKey(process.env.LIFOLO_MASTER);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(dir, 'updater.key'), k.privateKey, { mode: 0o600 });
  fs.writeFileSync(path.join(dir, 'updater.key.password'), k.password, { mode: 0o600 });
  fs.writeFileSync(path.join(dir, 'updater.pub'), k.publicKey, { mode: 0o600 });
}
