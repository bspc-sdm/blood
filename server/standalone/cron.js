// দৈনিক ক্রন চালানো (systemd timer প্রতিদিন ০৩:০০ UTC-তে এটা চালায়; vercel.json-এর "crons"-এর বদলে)।
// Vercel ক্রন যেভাবে ডাকে ঠিক সেভাবে: GET, "Authorization: Bearer $CRON_SECRET"।
// গোপন চাবি কমান্ড লাইনে যায় না (ps-এ দেখা যায় না), পরিবেশ থেকে পড়া হয়।
//
// চালানো:  node standalone/cron.js [/api/cron/daily]
// পরিবেশ:  CRON_SECRET (দরকারি), PORT (৩০০০), CRON_BASE_URL (না দিলে http://127.0.0.1:$PORT), CRON_TIMEOUT (৯০০ সেকেন্ড)
'use strict';

const http = require('http');
const https = require('https');

function runCron(cronPath = '/api/cron/daily', env = process.env) {
  const secret = env.CRON_SECRET;
  if (!secret) return Promise.reject(new Error('CRON_SECRET is not set'));
  const base = env.CRON_BASE_URL || `http://127.0.0.1:${env.PORT || 3000}`;
  const url = new URL(cronPath, base);
  const lib = url.protocol === 'https:' ? https : http;
  const timeoutMs = Number(env.CRON_TIMEOUT || 900) * 1000;
  return new Promise((resolve, reject) => {
    const req = lib.request(url, {
      method: 'GET',
      headers: { Authorization: `Bearer ${secret}`, 'User-Agent': 'vercel-cron/1.0' },
      timeout: timeoutMs,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('timeout', () => req.destroy(new Error(`cron request timed out after ${timeoutMs / 1000}s`)));
    req.on('error', reject);
    req.end();
  });
}

/**
 * সার্ভার তখন চালু না থাকলে (যেমন ঠিক ০৩:০০-এ রিস্টার্ট) কয়েকবার আবার চেষ্টা — শুধু সংযোগই না হলে।
 * অনুরোধ একবার পৌঁছে গেলে আর নয়: দৈনিক কাজ দুবার চললে একই নোটিফিকেশন দুবার যেতে পারে।
 */
async function runWithRetry(cronPath, env = process.env, attempts = 5, waitMs = 30_000) {
  for (let i = 1; ; i++) {
    try {
      return await runCron(cronPath, env);
    } catch (e) {
      const notConnected = ['ECONNREFUSED', 'EHOSTUNREACH', 'ENOTFOUND', 'EAI_AGAIN'].includes(e && e.code);
      if (!notConnected || i >= attempts) throw e;
      console.error(`[cron] server not reachable (${e.code}), retry ${i}/${attempts - 1} in ${waitMs / 1000}s`);
      await new Promise((r) => setTimeout(r, waitMs));
    }
  }
}

if (require.main === module) {
  const p = process.argv[2] || '/api/cron/daily';
  runWithRetry(p).then(
    (r) => {
      console.log(`[cron] ${p} -> ${r.status} ${r.body.slice(0, 2000)}`);
      process.exit(r.status >= 200 && r.status < 300 ? 0 : 1);
    },
    (e) => {
      console.error(`[cron] ${p} failed:`, e.message);
      process.exit(1);
    },
  );
}

module.exports = { runCron, runWithRetry };
