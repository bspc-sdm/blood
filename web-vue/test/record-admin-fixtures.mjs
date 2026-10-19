// অ্যাডমিনের নকল উত্তর (test/fixtures-e2e/admin-data.json) লোকাল সার্ভার থেকে তুলে রাখা — ডেমো তথ্য, মালিকের লগইন।
// চালান (লোকাল স্ট্যাক চালু, ডেমো বানানো): BASE=http://localhost:4600 node test/record-admin-fixtures.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE || 'http://localhost:4600';
const OWNER = process.env.OWNER_EMAIL || 'ekapkbd@gmail.com';
let cookie = '';

async function post(p, body) {
  const r = await fetch(BASE + p, { method: 'POST', headers: { 'content-type': 'application/json', 'x-lifolo-admin': '1', cookie }, body: JSON.stringify({ lang: 'bn', ...body }) });
  const sc = r.headers.getSetCookie?.() || [];
  if (sc.length) cookie = sc.map((c) => c.split(';')[0]).join('; ');
  const j = await r.json();
  if (!r.ok) throw new Error(`${p} ${body.action || ''}: ${j.error}`);
  return j;
}
const act = (action, b = {}) => post('/api/community', { action, panel: true, ...b });

await post('/api/auth/send-code', { email: OWNER });
const { code } = await (await fetch(`${BASE}/__e2e/mail?to=${OWNER}`)).json();
await post('/api/auth/verify-code', { email: OWNER, code, web: true });

// ডেমো না থাকলে বানানো (প্যানেলের মতো ধাপে ধাপে)
const st0 = await act('admin_demo_status');
if (!st0.active || st0.partial) {
  for (let step = 0, i = 0; step !== null && step !== undefined && i < 40; i++) step = (await act('admin_demo_seed', { step })).next;
}

const out = {};
const keep = async (key, action, body) => {
  try {
    out[key] = await act(action, body);
  } catch (e) {
    console.warn('skip', key, e.message);
  }
};
// ছবি ছোট করে (১×১, ব্রাউজার ধরন দেখে নেয়) — ফাইল ছোট রাখতে
const TINY = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

await keep('admin_overview', 'admin_overview');
await keep('admin_stats', 'admin_stats');
await keep('admin_log', 'admin_log');
await keep('admin_emergencies', 'admin_emergencies');
await keep('admin_districts', 'admin_districts');
const d0 = (out.admin_districts?.items || [])[0];
if (d0) await keep('admin_hospitals', 'admin_hospitals', { district: d0 });
for (const [kind, action, tabs] of [
  ['badges', 'admin_badges', ['pending', 'approved', 'rejected']],
  ['pharmacies', 'admin_pharmacies', ['pending', 'approved', 'suspended', 'rejected']],
  ['ambulances', 'admin_ambulances', ['pending', 'approved', 'suspended', 'rejected']],
  ['bloodBanks', 'admin_bloodbanks', ['pending', 'approved', 'suspended', 'rejected']],
  ['labs', 'admin_labs', ['pending', 'approved', 'suspended', 'rejected']],
  ['tickets', 'admin_tickets', ['open', 'answered', 'closed']],
]) {
  for (const s of tabs) await keep(`${action}:${s}`, action, { status: s });
  void kind;
}
const first = (k) => (out.admin_overview?.[k]?.items || [])[0];
const ph = first('pharmacies');
const bd = first('badges');
if (ph) await keep(`admin_verify_docs:pharmacy:${ph.id}`, 'admin_verify_docs', { kind: 'pharmacy', id: ph.id });
if (bd) await keep(`admin_verify_docs:badge:${bd.uid}`, 'admin_verify_docs', { kind: 'badge', id: bd.uid });
for (const v of Object.keys(out).filter((k) => k.startsWith('admin_verify_docs'))) for (const p of out[v].photos || []) if (p.data) p.data = TINY;
const tk = first('tickets') || (out['admin_tickets:answered']?.items || [])[0];
if (tk) await keep(`admin_ticket:${tk.id}`, 'admin_ticket', { id: tk.id });
await keep('catalog_get', 'catalog_get');
await keep('labtests_get', 'labtests_get');
// ডাক্তারের ডেমো অ্যাকাউন্ট (সবচেয়ে বেশি ভাগ ভরা: ব্যাজ, চেম্বার, অ্যাপয়েন্টমেন্ট)
const Q = 'doctor@lifolo-demo.test';
await keep('admin_user_find:demo', 'admin_user_find', { q: Q });
const u = (out['admin_user_find:demo']?.items || [])[0];
if (u) {
  await keep(`admin_user:${u.uid}`, 'admin_user', { uid: u.uid });
  for (const s of ['blood', 'community', 'services', 'care', 'inbox', 'support', 'admin']) await keep(`admin_user_section:${u.uid}:${s}`, 'admin_user_section', { uid: u.uid, section: s });
}
await keep('admin_demo_status', 'admin_demo_status');

const file = path.join(here, 'fixtures-e2e/admin-data.json');
fs.writeFileSync(file, JSON.stringify(out));
console.log(`wrote ${Object.keys(out).length} responses, ${(fs.statSync(file).size / 1024).toFixed(0)} KB → ${file}`);
