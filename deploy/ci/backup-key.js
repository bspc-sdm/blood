#!/usr/bin/env node
// বাইরের (R2) ব্যাকআপ কপি এনক্রিপ্ট/ডিক্রিপ্ট করার চাবি — VPS_ROOT_PASSWORD থেকে বানানো (derive-keys.js-এর মতোই),
// তাই আলাদা কোনো চাবি কোথাও রাখতে হয় না। একই পাসওয়ার্ড → সবসময় একই চাবি।
//
//   LIFOLO_MASTER='<VPS_ROOT_PASSWORD>' node deploy/ci/backup-key.js <ফাইল>
//
// <ফাইল>-এ (0600) একটা লাইন লেখে: gpg-র passphrase (৬৪ অক্ষরের hex)। stdout-এ কিছু ছাপা হয় না।
// ব্যবহার: gpg --batch --pinentry-mode loopback --passphrase-file <ফাইল> --decrypt lifolo-….dump.gpg > lifolo.dump
//
// scrypt (N=2^16), লেবেল "backup-encryption" — SSH চাবিগুলোর লেবেল আলাদা, তাই একটা থেকে অন্যটা বের করা যায় না।
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const { normalizeMaster } = require('./derive-keys.js');

const SALT = 'lifolo-vps-v1|backup-encryption';

function backupPassphrase(master) {
  const m = normalizeMaster(master);
  if (!m) throw new Error('empty master secret');
  return crypto.scryptSync(m, SALT, 32, { N: 1 << 16, r: 8, p: 1, maxmem: 256 * 1024 * 1024 }).toString('hex');
}

module.exports = { backupPassphrase };

if (require.main === module) {
  const out = process.argv[2];
  if (!out) {
    console.error('usage: LIFOLO_MASTER=... node backup-key.js <file>');
    process.exit(2);
  }
  fs.writeFileSync(out, `${backupPassphrase(process.env.LIFOLO_MASTER)}\n`, { mode: 0o600 });
}
