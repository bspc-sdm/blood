// অ্যাডমিন প্যানেলের নকল সার্ভার (শুধু x-lifolo-admin হেডারের অনুরোধে): কুকির সেশন, কোড 123456, উত্তর admin-data.json থেকে
// (test/record-admin-fixtures.mjs লোকাল সার্ভারের ডেমো তথ্য থেকে তোলে)। লেখার কাজগুলো শুধু {ok: true}।
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const data = JSON.parse(fs.readFileSync(path.join(here, 'admin-data.json'), 'utf8'));
const COOKIE = 'lf_admin_e2e';
const logged = (c) => c.cookies[COOKIE] === '1';
const need = (fn) => (b, c) => (logged(c) ? fn(b, c) : { __status: 401, body: { error: 'Please log in', code: 'session_expired' } });
const pick = (...keys) => {
  for (const k of keys) if (data[k]) return data[k];
  return { items: [] };
};
const ok = need(() => ({ ok: true }));

export const admin = {
  '/api/auth/send-code': () => ({ ok: true, rid: 'e2e-rid' }),
  '/api/auth/verify-code': (b) => (String(b.code) === '123456'
    ? { __status: 200, __headers: { 'set-cookie': `${COOKIE}=1; Path=/; HttpOnly; SameSite=Strict` }, body: { ok: true } }
    : { __status: 400, body: { error: 'কোড মেলেনি' } }),
  '/api/admin/resolve': ok,
  web_logout: () => ({ __status: 200, __headers: { 'set-cookie': `${COOKIE}=; Path=/; Max-Age=0` }, body: { ok: true } }),
  admin_overview: need(() => data.admin_overview),
  admin_stats: need(() => data.admin_stats),
  admin_log: need((b) => (b.before ? { items: [] } : data.admin_log)),
  admin_emergencies: need(() => pick('admin_emergencies')),
  admin_districts: need(() => pick('admin_districts')),
  admin_hospitals: need(() => pick('admin_hospitals')),
  admin_badges: need((b) => pick(`admin_badges:${b.status}`)),
  admin_pharmacies: need((b) => pick(`admin_pharmacies:${b.status}`)),
  admin_ambulances: need((b) => pick(`admin_ambulances:${b.status}`)),
  admin_bloodbanks: need((b) => pick(`admin_bloodbanks:${b.status}`)),
  admin_labs: need((b) => pick(`admin_labs:${b.status}`)),
  admin_tickets: need((b) => pick(`admin_tickets:${b.status}`)),
  admin_ticket: need((b) => data[`admin_ticket:${b.id}`] || { __status: 404, body: { error: 'পাওয়া যায়নি' } }),
  admin_verify_docs: need((b) => data[`admin_verify_docs:${b.kind}:${b.id}`] || { __status: 404, body: { error: 'পাওয়া যায়নি' } }),
  catalog_get: need(() => data.catalog_get),
  labtests_get: need(() => data.labtests_get),
  admin_user_find: need(() => pick('admin_user_find:demo')),
  admin_user: need((b) => data[`admin_user:${b.uid}`] || { __status: 404, body: { error: 'পাওয়া যায়নি' } }),
  admin_user_section: need((b) => data[`admin_user_section:${b.uid}:${b.section}`] || { __status: 404, body: { error: 'পাওয়া যায়নি' } }),
  admin_demo_status: need(() => data.admin_demo_status),
  // প্যানেলের কুকি সেশনে `features` চলে না (সার্ভারের মতো: শুধু admin_* আর কয়েকটা পড়া)
  features: () => ({ __status: 403, body: { error: 'ওয়েব প্যানেলে এটা চলে না' } }),
  admin_catalog_set: need((b) => ({ items: b.items.map((x, i) => ({ ...x, code: x.code || `new${i}` })) })),
  admin_labtests_set: need((b) => ({ test: { ...b.test, code: b.test.code || 'new_test' } })),
  admin_emergency_create: need(() => ({ ok: true, id: 'em-e2e', notified: 7 })),
  ...Object.fromEntries([
    'admin_badge_resolve', 'admin_pharmacy_resolve', 'admin_ambulance_resolve', 'admin_bloodbank_resolve', 'admin_lab_resolve',
    'admin_hospital_resolve', 'admin_report_resolve', 'admin_ticket_reply', 'admin_user_action', 'admin_request_docs',
    'admin_bloodbank_add', 'admin_bloodbank_edit', 'admin_bloodbank_delete', 'admin_emergency_group', 'admin_emergency_close',
    'admin_emergency_more', 'admin_add', 'admin_remove',
  ].map((k) => [k, ok])),
};
export default {};
