// Google API-র access token — service account-এর চাবি দিয়ে (OAuth2 JWT bearer), কোনো বাড়তি প্যাকেজ ছাড়া।
// ব্যবহার: standalone/backup-upload.js (Cloud Storage-এ ব্যাকআপ), deploy/ci/firebase-domain.js (Auth-এর ডোমেইন)।
'use strict';
const crypto = require('crypto');

const SCOPE = 'https://www.googleapis.com/auth/cloud-platform';

/** FIREBASE_SERVICE_ACCOUNT (JSON বা base64) → অবজেক্ট */
function loadServiceAccount(raw = process.env.FIREBASE_SERVICE_ACCOUNT) {
  if (!raw) throw new Error('FIREBASE_SERVICE_ACCOUNT is not set');
  const s = String(raw).trim();
  return JSON.parse(s.startsWith('{') ? s : Buffer.from(s, 'base64').toString('utf8'));
}

function signedJwt(sa, scope = SCOPE, now = Math.floor(Date.now() / 1000)) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = enc({ alg: 'RS256', typ: 'JWT' });
  const claims = enc({ iss: sa.client_email, scope, aud: sa.token_uri || 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 });
  const sig = crypto.sign('RSA-SHA256', Buffer.from(`${head}.${claims}`), sa.private_key).toString('base64url');
  return `${head}.${claims}.${sig}`;
}

async function accessToken(sa, scope = SCOPE, fetchImpl = fetch) {
  const res = await fetchImpl(sa.token_uri || 'https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: signedJwt(sa, scope) }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || !body.access_token) throw new Error(`Google token error: HTTP ${res.status} ${body.error || ''}`.trim());
  return body.access_token;
}

module.exports = { loadServiceAccount, signedJwt, accessToken, SCOPE };
