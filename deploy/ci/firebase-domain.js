#!/usr/bin/env node
// Firebase Authentication-এর "Authorized domains"-এ VPS-এর ঠিকানা যোগ করা (নইলে ওয়েবে Google/ইমেইল লগইন আটকায়)।
//   FIREBASE_SERVICE_ACCOUNT=... node deploy/ci/firebase-domain.js <hostname>
// আগে থেকে থাকলে কিছু বদলায় না। না পারলে (অনুমতি নেই ইত্যাদি) exit 1 — workflow হাতে করার নিয়ম দেখায়।
'use strict';
const path = require('path');
const { loadServiceAccount, accessToken } = require(path.join(__dirname, '..', '..', 'server', 'standalone', 'gtoken.js'));

async function ensureDomain(host, { sa, token, fetchImpl = fetch }) {
  const url = `https://identitytoolkit.googleapis.com/admin/v2/projects/${encodeURIComponent(sa.project_id)}/config`;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'X-Goog-User-Project': sa.project_id };
  const res = await fetchImpl(url, { headers });
  if (!res.ok) throw new Error(`read config: HTTP ${res.status}`);
  const domains = (await res.json()).authorizedDomains || [];
  if (domains.includes(host)) return { added: false, domains };
  const next = [...domains, host];
  const up = await fetchImpl(`${url}?updateMask=authorizedDomains`, { method: 'PATCH', headers, body: JSON.stringify({ authorizedDomains: next }) });
  if (!up.ok) throw new Error(`update config: HTTP ${up.status}`);
  return { added: true, domains: next };
}

module.exports = { ensureDomain };

if (require.main === module) {
  (async () => {
    const host = process.argv[2];
    if (!host) throw new Error('usage: firebase-domain.js <hostname>');
    const sa = loadServiceAccount();
    const token = await accessToken(sa);
    const r = await ensureDomain(host, { sa, token });
    console.log(r.added ? `added ${host} to Firebase authorized domains` : `${host} already authorized`);
  })().catch((e) => {
    console.log(`::warning::Firebase-এ ডোমেইন যোগ করা যায়নি (${e.message})`);
    process.exit(1);
  });
}
