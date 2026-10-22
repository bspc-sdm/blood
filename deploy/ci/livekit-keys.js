#!/usr/bin/env node
// কল সার্ভারের (LiveKit, wss://call.lifolo.com) API key/secret — কল-VPS-এর root পাসওয়ার্ড (GitHub সিক্রেট
// CALL_VPS_ROOT_PASSWORD) থেকে বানানো, derive-keys.js / backup-key.js-এর মতোই। একই পাসওয়ার্ড → সবসময় একই চাবি,
// তাই নতুন কোনো GitHub সিক্রেট লাগে না: Setup call server workflow এটা দিয়ে কল-সার্ভারে চাবি বসায়, আর Deploy backend
// একই হিসাবে মূল অ্যাপের app.env-এ LIVEKIT_* লেখে।
//
//   LIFOLO_MASTER='<CALL_VPS_ROOT_PASSWORD>' node deploy/ci/livekit-keys.js --env <ফাইল>      # LIVEKIT_URL/KEY/SECRET যোগ করে
//   LIFOLO_MASTER='<CALL_VPS_ROOT_PASSWORD>' node deploy/ci/livekit-keys.js --keyfile <ফাইল>  # LiveKit-এর key_file ("KEY: SECRET")
//   … --mask   (GitHub Actions-এ key আর secret লগে লুকানো: ::add-mask::)
//
// CALL_DOMAIN (না দিলে call.lifolo.com) থেকে LIVEKIT_URL=wss://<CALL_DOMAIN>। ফাইল 0600; stdout-এ চাবি ছাপা হয় না।
// scrypt (N=2^16), লেবেল "livekit-api" — SSH/ব্যাকআপের লেবেল আলাদা, তাই একটা থেকে অন্যটা বের করা যায় না।
// key = "API" + ১২টা base62 অক্ষর, secret = ৪৩টা base62 অক্ষর (livekit-server generate-keys-এর মতোই গঠন)।
//
// মডিউল হিসেবে: { livekitKeys(master), accessToken({...}), callUrl() } — smoke test-এর টোকেন বানাতে।
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const { normalizeMaster } = require('./derive-keys.js');

const SALT = 'lifolo-vps-v1|livekit-api';
const B62 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
const DOMAIN_RE = /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

function base62(buf, len) {
  let n = BigInt(`0x${buf.toString('hex')}`);
  let s = '';
  while (s.length < len) {
    s = B62[Number(n % 62n)] + s;
    n /= 62n;
  }
  return s;
}

function livekitKeys(master) {
  const m = normalizeMaster(master);
  if (!m) throw new Error('empty master secret');
  const b = crypto.scryptSync(m, SALT, 48, { N: 1 << 16, r: 8, p: 1, maxmem: 256 * 1024 * 1024 });
  // ১২ base62 অক্ষর ≈ ৭১ বিট (১৬ বাইট থেকে), ৪৩ অক্ষর ≈ ২৫৬ বিট (৩২ বাইট থেকে)
  return { apiKey: `API${base62(b.subarray(0, 16), 12)}`, apiSecret: base62(b.subarray(16, 48), 43) };
}

function callDomain() {
  const d = String(process.env.CALL_DOMAIN || 'call.lifolo.com').trim().toLowerCase();
  if (!DOMAIN_RE.test(d)) throw new Error(`bad CALL_DOMAIN: ${d}`);
  return d;
}

function callUrl() {
  return `wss://${callDomain()}`;
}

const b64u = (x) => Buffer.from(typeof x === 'string' ? x : JSON.stringify(x)).toString('base64url');

/** LiveKit-এর access token (JWT, HS256) — শুধু room join, publish/subscribe; recording/admin নয় */
function accessToken({ apiKey, apiSecret, room, identity, name, ttlSec = 600, canPublish = true, canSubscribe = true }) {
  if (!apiKey || !apiSecret || !room || !identity) throw new Error('apiKey, apiSecret, room, identity required');
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    iss: apiKey,
    sub: identity,
    nbf: now - 10,
    exp: now + ttlSec,
    jti: `${identity}-${crypto.randomBytes(6).toString('hex')}`,
    video: { room, roomJoin: true, canPublish, canSubscribe, canPublishData: true },
  };
  if (name) payload.name = name;
  const unsigned = `${b64u({ alg: 'HS256', typ: 'JWT' })}.${b64u(payload)}`;
  const sig = crypto.createHmac('sha256', apiSecret).update(unsigned).digest('base64url');
  return `${unsigned}.${sig}`;
}

module.exports = { livekitKeys, accessToken, callUrl, base62 };

if (require.main === module) {
  const args = process.argv.slice(2);
  const usage = () => {
    console.error('usage: LIFOLO_MASTER=... [CALL_DOMAIN=call.lifolo.com] node livekit-keys.js [--env FILE] [--keyfile FILE] [--mask]');
    process.exit(2);
  };
  if (!args.length) usage();
  let k;
  try {
    k = livekitKeys(process.env.LIFOLO_MASTER);
  } catch (e) {
    console.error(`livekit-keys: ${e.message} (LIFOLO_MASTER খালি?)`);
    process.exit(1);
  }
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--mask') {
      process.stdout.write(`::add-mask::${k.apiKey}\n::add-mask::${k.apiSecret}\n`);
    } else if (a === '--env' && args[i + 1]) {
      const f = args[++i];
      fs.appendFileSync(f, `LIVEKIT_URL=${callUrl()}\nLIVEKIT_API_KEY=${k.apiKey}\nLIVEKIT_API_SECRET=${k.apiSecret}\n`, { mode: 0o600 });
      fs.chmodSync(f, 0o600);
    } else if (a === '--keyfile' && args[i + 1]) {
      const f = args[++i];
      fs.writeFileSync(f, `${k.apiKey}: ${k.apiSecret}\n`, { mode: 0o600 });
      fs.chmodSync(f, 0o600);
    } else {
      usage();
    }
  }
}
