#!/usr/bin/env node
// Setup VPS workflow-এর গোপন সেটিং: Vercel থেকে আনা, GitHub secrets দিয়ে ফাঁক পূরণ, VPS-এর /etc/lifolo/env-এ মেলানো।
//
//   node server-env.js vercel <out.json>
//       VERCEL_TOKEN দিয়ে blood-server-ashy.vercel.app যে প্রজেক্টের, তার production মানগুলো আনে।
//       প্রতিটা মান লগে লুকানো হয় (::add-mask::); কোনো মান ছাপা হয় না।
//   node server-env.js merge <vercel.json> <current.env> <out.env> [--check]
//       মান বেছে নেওয়া: GitHub secret (একই নাম) > Vercel > VPS-এ আগে থেকে থাকা। কোনো দরকারি মান কোথাও না পেলে
//       বাংলায় কোনটা, কোথা থেকে আনতে হবে লিখে exit 3। --check: শুধু যাচাই, out.env লেখে না।
'use strict';
const fs = require('fs');

const ALIAS = process.env.VERCEL_ALIAS || 'blood-server-ashy.vercel.app';
const PROJECT_NAME = process.env.VERCEL_PROJECT || 'blood-server';
const API = 'https://api.vercel.com';

/** সার্ভার যা পড়ে (server/lib, api, standalone) — Vercel-এ থাকা সেটিং */
const KEYS = ['FIREBASE_SERVICE_ACCOUNT', 'ADMIN_EMAILS', 'GMAIL_USER', 'GMAIL_APP_PASSWORD', 'SMS_URL_TEMPLATE', 'CRON_SECRET', 'WEB_SESSION_SECRET'];
/** না থাকলে সার্ভার চলে না (লগইন কোডের ইমেইল, Firebase) */
const REQUIRED = ['FIREBASE_SERVICE_ACCOUNT', 'GMAIL_USER', 'GMAIL_APP_PASSWORD'];
/** VPS নিজে র‍্যান্ডম বানায় (setup-vps.sh) — Vercel-এর মান পড়া না গেলেও চলে */
const GENERATED = ['CRON_SECRET', 'WEB_SESSION_SECRET'];

const WHERE = {
  FIREBASE_SERVICE_ACCOUNT: 'Firebase Console → ⚙️ Project settings → Service accounts → "Generate new private key" → নামানো .json ফাইলটা Notepad-এ খুলে পুরো লেখা',
  ADMIN_EMAILS: 'অ্যাডমিনদের ইমেইল, কমা দিয়ে আলাদা (যেমন a@gmail.com,b@gmail.com)',
  GMAIL_USER: 'যে Gmail থেকে লগইন কোড যায়, সেই ঠিকানা (যেমন lifolo.app@gmail.com)',
  GMAIL_APP_PASSWORD: 'ওই Gmail-এ লগইন করে myaccount.google.com/apppasswords → নতুন App password বানান → ১৬ অক্ষরের কোড',
  SMS_URL_TEMPLATE: 'SMS গেটওয়ের ড্যাশবোর্ড → API লিংক, যেখানে নম্বরের জায়গায় {to} আর লেখার জায়গায় {msg}',
  CRON_SECRET: 'দরকার নেই (VPS নিজে বানায়)',
  WEB_SESSION_SECRET: 'দরকার নেই (VPS নিজে বানায়)',
};

// ---------- লগে মান লুকানো ----------
function maskLines(value) {
  const out = [];
  const v = String(value);
  if (v.length >= 4) out.push(v);
  for (const line of v.split(/\r?\n/)) {
    const t = line.trim();
    if (t.length >= 6 && t !== v) out.push(t);
  }
  return out;
}
function mask(value) {
  if (!value || !process.env.GITHUB_ACTIONS) return;
  for (const m of maskLines(value)) process.stdout.write(`::add-mask::${m.replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A')}\n`);
}

// ---------- Vercel ----------
async function vercel(token, pathname, teamId) {
  const u = new URL(API + pathname);
  if (teamId) u.searchParams.set('teamId', teamId);
  const res = await fetch(u, { headers: { Authorization: `Bearer ${token}` } });
  const body = await res.json().catch(() => ({}));
  return { status: res.status, body };
}

async function findProject(token) {
  const teams = await vercel(token, '/v2/teams?limit=100');
  if (teams.status === 401 || teams.status === 403) {
    throw new Error('VERCEL_TOKEN কাজ করছে না (মেয়াদ শেষ বা ভুল)। Vercel → Account Settings → Tokens থেকে নতুন বানিয়ে সিক্রেটটা বদলান।');
  }
  const scopes = [null, ...((teams.body && teams.body.teams) || []).map((t) => t.id)];
  for (const teamId of scopes) {
    const a = await vercel(token, `/v4/aliases/${encodeURIComponent(ALIAS)}`, teamId);
    if (a.status === 200 && a.body.projectId) return { projectId: a.body.projectId, teamId };
  }
  for (const teamId of scopes) {
    const p = await vercel(token, `/v9/projects/${encodeURIComponent(PROJECT_NAME)}`, teamId);
    if (p.status === 200 && p.body.id) return { projectId: p.body.id, teamId };
  }
  throw new Error(`Vercel-এ ${ALIAS} (বা "${PROJECT_NAME}") প্রজেক্ট পাওয়া যায়নি। টোকেন বানানোর সময় Scope-এ সেই টিম/অ্যাকাউন্ট বেছেছেন তো?`);
}

const targets = (e) => (Array.isArray(e.target) ? e.target : [e.target]).filter(Boolean);

/** একটা env-এর পুরো উত্তর থেকে মান পড়া যায় কি না */
function readable(detail) {
  if (!detail || detail.type === 'sensitive' || typeof detail.value !== 'string') return false;
  return detail.type === 'plain' || detail.decrypted === true;
}

async function fetchVercel(token) {
  const { projectId, teamId } = await findProject(token);
  const list = await vercel(token, `/v10/projects/${projectId}/env`, teamId);
  if (list.status !== 200) throw new Error(`Vercel env তালিকা আনা যায়নি (HTTP ${list.status})`);
  const envs = (list.body.envs || []).filter((e) => targets(e).includes('production') && !e.gitBranch);
  const values = {};
  const unreadable = [];
  for (const key of KEYS) {
    const e = envs.find((x) => x.key === key);
    if (!e) continue;
    const d = await vercel(token, `/v1/projects/${projectId}/env/${e.id}`, teamId);
    if (d.status === 200 && readable(d.body)) {
      mask(d.body.value);
      values[key] = d.body.value;
    } else {
      unreadable.push(key);
    }
  }
  const absent = KEYS.filter((k) => !(k in values) && !unreadable.includes(k));
  const other = [...new Set(envs.map((e) => e.key))].filter((k) => !KEYS.includes(k)).sort();
  return { projectId, teamId, values, unreadable, absent, other };
}

// ---------- env ফাইল ----------
/** systemd EnvironmentFile আর bash `.` — দুটোতেই একই মান: ডাবল কোট, ভেতরে \ " $ ` এর আগে \ */
function quote(value) {
  const v = String(value);
  if (/[\r\n\0]/.test(v)) throw new Error('value contains newline');
  return `"${v.replace(/[\\"$`]/g, (c) => `\\${c}`)}"`;
}

/** KEY=... লাইন থেকে মান (শুধু আমাদের লেখা রূপগুলো: কোট ছাড়া, '...' বা "...") */
function parseEnv(text) {
  const out = {};
  for (const line of String(text || '').split('\n')) {
    const m = /^([A-Z_][A-Z0-9_]*)=(.*)$/.exec(line);
    if (!m) continue;
    let v = m[2].trim();
    if (v.length >= 2 && v[0] === "'" && v.endsWith("'")) v = v.slice(1, -1);
    else if (v.length >= 2 && v[0] === '"' && v.endsWith('"')) v = v.slice(1, -1).replace(/\\([\\"$`])/g, '$1');
    if (!(m[1] in out)) out[m[1]] = v;
  }
  return out;
}

/** KEY= লাইন বদলানো (প্রথমটা), না থাকলে শেষে যোগ */
function setLines(text, updates) {
  let lines = String(text || '').split('\n');
  if (lines.length && lines[lines.length - 1] === '') lines.pop();
  for (const [k, v] of Object.entries(updates)) {
    const line = `${k}=${quote(v)}`;
    const i = lines.findIndex((l) => l.startsWith(`${k}=`));
    if (i >= 0) {
      lines[i] = line;
      lines = lines.filter((l, j) => j === i || !l.startsWith(`${k}=`));
    } else lines.push(line);
  }
  return `${lines.join('\n')}\n`;
}

/** Firebase service account: JSON বা base64 → যাচাই করা base64 (এক লাইন) আর project_id */
function normalizeFirebase(raw) {
  const s = String(raw || '').trim();
  let json;
  try {
    json = JSON.parse(s.startsWith('{') ? s : Buffer.from(s, 'base64').toString('utf8'));
  } catch {
    throw new Error('FIREBASE_SERVICE_ACCOUNT পড়া যাচ্ছে না — Firebase থেকে নামানো .json ফাইলের পুরো লেখা দিন');
  }
  if (!json || json.type !== 'service_account' || !json.private_key || !json.client_email || !json.project_id) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT-এ service account-এর JSON নেই (private_key / client_email / project_id পাওয়া যায়নি)');
  }
  return { base64: Buffer.from(JSON.stringify(json)).toString('base64'), projectId: json.project_id, json };
}

/**
 * চূড়ান্ত মান বেছে নেওয়া।
 * vercel: fetchVercel()-এর ফল (বা {} যদি টোকেন না থাকে); secrets: GitHub secret (একই নাম); current: VPS-এর এখনকার env
 * ফেরত { updates, gaps: [{key, reason}], sources: {key: 'github'|'vercel'|'vps'} }
 */
function decide({ vercel = {}, secrets = {}, current = {} }) {
  const vals = vercel.values || {};
  const unreadable = vercel.unreadable || [];
  const updates = {};
  const sources = {};
  const gaps = [];
  for (const key of KEYS) {
    const gh = (secrets[key] || '').trim();
    if (gh) {
      updates[key] = gh;
      sources[key] = 'github';
    } else if (key in vals) {
      updates[key] = vals[key];
      sources[key] = 'vercel';
    } else if (current[key]) {
      sources[key] = 'vps';
    } else if (unreadable.includes(key) && !GENERATED.includes(key)) {
      gaps.push({ key, reason: 'sensitive' });
    } else if (REQUIRED.includes(key)) {
      gaps.push({ key, reason: 'missing' });
    }
  }
  if (updates.FIREBASE_SERVICE_ACCOUNT) updates.FIREBASE_SERVICE_ACCOUNT = normalizeFirebase(updates.FIREBASE_SERVICE_ACCOUNT).base64;
  for (const [k, v] of Object.entries(updates)) {
    if (/[\r\n]/.test(v)) updates[k] = v.replace(/\r?\n/g, k === 'ADMIN_EMAILS' ? ',' : '').trim();
  }
  return { updates, gaps, sources };
}

function gapMessage(gaps, hadToken) {
  const lines = [
    '## ❌ কিছু গোপন সেটিং পাওয়া যায়নি',
    '',
    hadToken
      ? 'Vercel-এ নিচের মানগুলো **Sensitive** — Vercel নিজেও আর দেখায় না, তাই আনা যায়নি (বা Vercel-এ নেই)।'
      : 'VERCEL_TOKEN সিক্রেট নেই, আর VPS-এও মানগুলো আগে বসানো হয়নি।',
    'প্রতিটার জন্য GitHub-এ **একই নামে** একটা সিক্রেট যোগ করুন (Settings → Secrets and variables → Actions → New repository secret), তারপর আবার Run workflow:',
    '',
    '| সিক্রেটের নাম (হুবহু) | মান কোথায় পাবেন |',
    '|---|---|',
    ...gaps.map((g) => `| \`${g.key}\` | ${WHERE[g.key]}${g.reason === 'sensitive' ? ' (Vercel-এ Sensitive)' : ''} |`),
    '',
  ];
  return lines.join('\n');
}

function readSecrets(env = process.env) {
  const s = {};
  for (const k of KEYS) if (env[k]) s[k] = env[k];
  return s;
}

async function main() {
  const [cmd, ...args] = process.argv.slice(2);
  const summary = process.env.GITHUB_STEP_SUMMARY;
  if (cmd === 'vercel') {
    const out = args[0];
    const token = (process.env.VERCEL_TOKEN || '').trim();
    let result = { values: {}, unreadable: [], absent: [], other: [], token: false };
    if (token) {
      result = { ...(await fetchVercel(token)), token: true };
      console.log(`Vercel project: ${result.projectId}${result.teamId ? ` (team ${result.teamId})` : ''}`);
      console.log(`  read:       ${Object.keys(result.values).join(', ') || '-'}`);
      console.log(`  sensitive:  ${result.unreadable.join(', ') || '-'}`);
      console.log(`  not set:    ${result.absent.join(', ') || '-'}`);
      if (result.other.length) console.log(`  other vars (not copied, server does not read them): ${result.other.join(', ')}`);
    } else {
      console.log('VERCEL_TOKEN নেই — শুধু GitHub secrets আর VPS-এ আগে থেকে থাকা মান');
    }
    fs.writeFileSync(out, JSON.stringify(result), { mode: 0o600 });
    return;
  }
  if (cmd === 'merge') {
    const [vercelPath, currentPath, outPath] = args;
    const check = args.includes('--check');
    const v = JSON.parse(fs.readFileSync(vercelPath, 'utf8'));
    const currentText = fs.existsSync(currentPath) ? fs.readFileSync(currentPath, 'utf8') : '';
    const secrets = readSecrets();
    let d;
    try {
      d = decide({ vercel: v, secrets, current: parseEnv(currentText) });
    } catch (e) {
      console.log(`::error::${e.message}`);
      process.exit(3);
    }
    for (const val of Object.values(d.updates)) mask(val);
    if (d.gaps.length) {
      const msg = gapMessage(d.gaps, v.token);
      if (summary) fs.appendFileSync(summary, msg);
      console.log(`::error::এই সেটিংগুলো পাওয়া যায়নি: ${d.gaps.map((g) => g.key).join(', ')} — কোথা থেকে আনবেন, Summary পাতায় লেখা আছে।`);
      process.exit(3);
    }
    console.log(Object.entries(d.sources).map(([k, s]) => `${k} ← ${s}`).join('\n'));
    if (!check) fs.writeFileSync(outPath, setLines(currentText, d.updates), { mode: 0o600 });
    const fb = d.updates.FIREBASE_SERVICE_ACCOUNT || parseEnv(currentText).FIREBASE_SERVICE_ACCOUNT;
    if (fb && process.env.GITHUB_OUTPUT) {
      fs.appendFileSync(process.env.GITHUB_OUTPUT, `firebase_project=${normalizeFirebase(fb).projectId}\n`);
    }
    return;
  }
  console.error('usage: server-env.js vercel <out.json> | merge <vercel.json> <current.env> <out.env> [--check]');
  process.exit(2);
}

module.exports = { KEYS, REQUIRED, GENERATED, quote, parseEnv, setLines, normalizeFirebase, decide, readable, gapMessage, maskLines, findProject, fetchVercel };

if (require.main === module) {
  main().catch((e) => {
    console.log(`::error::${e.message}`);
    process.exit(1);
  });
}
