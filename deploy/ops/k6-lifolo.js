// Lifolo — লোড টেস্টের একটা ধাপ (k6)। "Ops: load test" workflow প্রতি ধাপে আলাদা করে চালায় (RATE বাড়িয়ে), যাতে
// প্রতিটা ধাপের p95/ত্রুটি আলাদা মাপা যায় আর p95 > 1 s বা ত্রুটি > 1% হলেই থামা যায়।
//
// সরাসরি VPS-এ (Cloudflare-এর পাশ কাটিয়ে): ORIGIN_IP দিলে api.lifolo.com → সেই IP (TLS-এ নাম আর সার্টিফিকেট একই)।
// শুধু ডেমো অ্যাকাউন্ট (TOKENS_FILE: {role: Firebase ID token}) — মালিকের আসল অ্যাকাউন্ট কখনো নয়।
//
// ধরন (tag kind):
//   read   লগইন করা ডেমো ব্যবহারকারীর পড়া (প্রোফাইল, রক্তের অনুরোধ, ডাক্তার/ফার্মেসি/অ্যাম্বুলেন্স/হাসপাতালের তালিকা …)
//   write  inbox_read (upsert), রক্তের অনুরোধ বানানো → দেখা → মোছা, অ্যাম্বুলেন্স ডাকা → অবস্থা → বাতিল (খুব কম)
//   guest  লগইন ছাড়া হোম-এর তালিকা (প্রতি IP-তে মিনিটে ২৪০-এর সীমা — তাই ধীর, স্থির)
//   login  লগইন-কোডের পথ, কোনো ইমেইল না পাঠিয়ে (ভুল ইমেইল → 400, না-চাওয়া কোড যাচাই → 400)
//   health GET /api/health (নেটওয়ার্ক + nginx-এর ভিত্তি)
// env: API, ORIGIN_IP, TOKENS_FILE, RATE, DURATION, STAGE, GUEST_RATE, WRITER (1/0), AMB (1/0), SUMMARY_FILE
import http from 'k6/http';
import { Rate, Trend, Counter } from 'k6/metrics';

const API = (__ENV.API || 'https://api.lifolo.com').replace(/\/$/, '');
const HOST = API.replace(/^https?:\/\//, '').replace(/\/.*$/, '');
const RATE = Number(__ENV.RATE || 10);
const DUR = __ENV.DURATION || '60s';
const STAGE = Number(__ENV.STAGE || 0);
const GUEST_RATE = Number(__ENV.GUEST_RATE || 2);
const TOKENS = JSON.parse(open(__ENV.TOKENS_FILE || './tokens.json'));
const D = 'গাজীপুর';
const DHAKA = 'ঢাকা';
const UP = 320; // গাজীপুর সদর (ডেমোর এলাকা)
const LAT = 24.0;
const LNG = 90.42;
const TAG = 'LOADTEST';

const lat = new Trend('lf_latency', true);
const errs = new Rate('lf_errors');
const skipped = new Counter('lf_skipped');

const kinds = ['read', 'write', 'guest', 'login', 'health'];
const thresholds = { lf_latency: ['p(95)<1000'], lf_errors: ['rate<0.01'] };
for (const k of kinds) {
  thresholds[`lf_latency{kind:${k}}`] = ['p(95)<1000'];
  thresholds[`lf_errors{kind:${k}}`] = ['rate<0.01'];
}

const scenarios = {
  auth: {
    executor: 'constant-arrival-rate', exec: 'auth', rate: RATE, timeUnit: '1s', duration: DUR,
    preAllocatedVUs: Math.min(800, Math.max(10, RATE)), maxVUs: Math.min(3000, Math.max(60, RATE * 5)), gracefulStop: '10s',
  },
  guest: {
    executor: 'constant-arrival-rate', exec: 'guest', rate: GUEST_RATE, timeUnit: '1s', duration: DUR,
    preAllocatedVUs: 4, maxVUs: 40, gracefulStop: '10s',
  },
  login: {
    executor: 'constant-arrival-rate', exec: 'login', rate: 1, timeUnit: '2s', duration: DUR,
    preAllocatedVUs: 2, maxVUs: 20, gracefulStop: '10s',
  },
  health: {
    executor: 'constant-arrival-rate', exec: 'health', rate: 1, timeUnit: '1s', duration: DUR,
    preAllocatedVUs: 2, maxVUs: 20, gracefulStop: '10s',
  },
};
if (__ENV.WRITER !== '0') {
  scenarios.writer = { executor: 'per-vu-iterations', exec: 'bloodWrite', vus: 1, iterations: 1, startTime: '10s', maxDuration: DUR };
}
if (__ENV.AMB === '1') {
  scenarios.amb = { executor: 'per-vu-iterations', exec: 'ambFlow', vus: 1, iterations: 1, startTime: '20s', maxDuration: DUR };
}

export const options = {
  hosts: __ENV.ORIGIN_IP ? { [HOST]: __ENV.ORIGIN_IP } : {},
  userAgent: 'lifolo-ops-loadtest/1.0 (+https://lifolo.com)',
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '120s',
  teardownTimeout: '60s',
};

// ---------------------------------------------------------------------------------------------- helpers
let warned = 0;
function note(kind, action, res, why) {
  if (warned >= 3) return;
  warned++;
  let e = '';
  try { e = String((res.json() || {}).error || '').slice(0, 90); } catch (_) { e = String(res.body || '').slice(0, 60); }
  console.warn(`ERRSAMPLE ${JSON.stringify({ k: kind, a: action, s: res.status, e: e || res.error || '', w: why })}`);
}

function post(path, body, token, kind, action, expect) {
  const headers = { 'content-type': 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  const want = expect || [200];
  const res = http.post(`${API}${path}`, JSON.stringify(body), {
    headers, timeout: '30s', tags: { kind, action },
    responseCallback: http.expectedStatuses(...want),
  });
  lat.add(res.timings.duration, { kind });
  let ok = want.includes(res.status);
  let j = null;
  if (ok && res.status === 200) {
    try { j = res.json(); } catch (_) { ok = false; }
    if (j && j.error) ok = false;
  }
  errs.add(!ok, { kind });
  if (!ok) note(kind, action, res, 'unexpected');
  return { res, ok, j };
}
const act = (action, extra, token, kind) => post('/api/community', { action, lang: 'bn', ...extra }, token, kind, action);

// ---------------------------------------------------------------------------------------------- mix
const READS = [
  ['patient', 'profile_get', {}],
  ['patient', 'blood_requests_mine', {}],
  ['patient', 'blood_requests_near', { lat: LAT, lng: LNG, maxKm: 25 }],
  ['patient', 'my_appointments', {}],
  ['patient', 'rx_mine', {}],
  ['patient', 'inbox', {}],
  ['patient', 'providers', { type: 'doctor', homeVisit: false, district: D }],
  ['patient', 'pharmacies', { district: D }],
  ['patient', 'ambulances', { district: D }],
  ['patient', 'hospitals', { district: D }],
  ['patient', 'bloodbanks', { district: D }],
  ['patient', 'labs', { district: D }],
  ['patient', 'emergencies_active', {}],
  ['patient', 'ambreq_count', { district: D, upazilaId: UP, dest: { kind: 'district', district: DHAKA }, maxFare: null, includeUnknown: true }],
  ['donor', 'blood_requests_near', { lat: LAT, lng: LNG, maxKm: 25 }],
  ['donor', 'profile_get', {}],
  ['pharmacy', 'pharmacy_me', {}],
  ['pharmacy', 'books_today', {}],
  ['doctor', 'provider_me', {}],
  ['ambulance', 'ambreq_driver', {}],
  ['ambulance', 'ambulance_mine', {}],
  ['caregiver', 'care_list', {}],
];
const GUEST = [
  ['guest_requests', { lat: LAT, lng: LNG, maxKm: 10 }],
  ['emergencies_active', { district: D }],
  ['providers', { type: 'doctor', homeVisit: false, district: D }],
  ['pharmacies', { district: D }],
  ['ambulances', { district: D }],
  ['hospitals', { district: D }],
  ['bloodbanks', { district: D }],
  ['labs', { district: D }],
  ['tips', {}],
  ['questions', {}],
];
const WRITE_ROLES = ['caregiver', 'homecare', 'assistant', 'donor', 'lab', 'bloodbank', 'doctor', 'pharmacy', 'ambulance', 'patient'];

// প্রথমে প্রতিটা পড়া একবার — যেটা এই ডেটায় চলে না (400/404), সেটা বাদ (ত্রুটি হিসেবে নয়; সারাংশে লেখা হয়)
export function setup() {
  const ok = [];
  const dropped = [];
  READS.forEach(([role, action, extra], i) => {
    const tok = TOKENS[role];
    if (!tok) { dropped.push(`${role}/${action}: টোকেন নেই`); return; }
    const res = http.post(`${API}/api/community`, JSON.stringify({ action, lang: 'bn', ...extra }), {
      headers: { 'content-type': 'application/json', authorization: `Bearer ${tok}` }, timeout: '30s', tags: { kind: 'probe' },
      responseCallback: http.expectedStatuses({ min: 200, max: 599 }),
    });
    let e = '';
    try { e = (res.json() || {}).error || ''; } catch (_) { e = 'not json'; }
    if (res.status === 200 && !e) ok.push(i);
    else dropped.push(`${role}/${action}: HTTP ${res.status} ${String(e).slice(0, 80)}`);
  });
  const gok = [];
  GUEST.forEach(([action, extra], i) => {
    const res = http.post(`${API}/api/community`, JSON.stringify({ action, lang: 'bn', ...extra }), {
      headers: { 'content-type': 'application/json' }, timeout: '30s', tags: { kind: 'probe' },
      responseCallback: http.expectedStatuses({ min: 200, max: 599 }),
    });
    let e = '';
    try { e = (res.json() || {}).error || ''; } catch (_) { e = 'not json'; }
    if (res.status === 200 && !e) gok.push(i);
    else dropped.push(`guest/${action}: HTTP ${res.status} ${String(e).slice(0, 80)}`);
  });
  console.log(`PROBE ${JSON.stringify({ reads: ok.length, guest: gok.length, dropped })}`);
  return { ok, gok, dropped };
}

export function auth(data) {
  // ~৫% লেখা (inbox_read — নিজের ঘণ্টির "দেখা হয়েছে" সময়, upsert), বাকি পড়া
  if (Math.random() < 0.05) {
    const role = Math.random() < 0.5 ? 'patient' : 'donor';
    act('inbox_read', {}, TOKENS[role], 'write');
    return;
  }
  const list = data.ok.length ? data.ok : [0];
  const [role, action, extra] = READS[list[Math.floor(Math.random() * list.length)]];
  act(action, extra, TOKENS[role], 'read');
}

export function guest(data) {
  const list = data.gok.length ? data.gok : [0];
  const [action, extra] = GUEST[list[Math.floor(Math.random() * list.length)]];
  act(action, extra, null, 'guest');
}

let flip = 0;
export function login() {
  flip++;
  if (flip % 2) {
    // ইমেইল ঠিক না → 400, কিছু পাঠানো বা লেখা হয় না
    post('/api/auth/send-code', { email: 'not-an-email', lang: 'bn' }, null, 'login', 'send_code_invalid', [400]);
  } else {
    // কখনো চাওয়া হয়নি এমন ডেমো-ডোমেইনের ঠিকানা → 400 (কোড চাওয়া হয়নি), DB-তে কিছু লেখা হয় না
    post('/api/auth/verify-code', { email: `loadtest-${__VU}@lifolo-demo.test`, code: '000000', lang: 'bn' }, null, 'login', 'verify_code_none', [400]);
  }
}

export function health() {
  const res = http.get(`${API}/api/health`, { timeout: '30s', tags: { kind: 'health', action: 'health' } });
  lat.add(res.timings.duration, { kind: 'health' });
  const ok = res.status === 200 && String(res.body).includes('"ok":true');
  errs.add(!ok, { kind: 'health' });
  if (!ok) note('health', 'health', res, 'health');
}

// রক্তের অনুরোধ: বানানো → দেখা → মোছা (প্রতি ধাপে একবার, আলাদা ডেমো অ্যাকাউন্টে — দিনে ৩টার সীমা; cleanup কোটা ফেরত আনে)
export function bloodWrite() {
  const role = WRITE_ROLES[STAGE % WRITE_ROLES.length];
  const tok = TOKENS[role];
  if (!tok) return;
  const day = 86400000;
  const neededBy = Math.floor(Date.now() / day) * day + day;
  const c = post('/api/community', {
    action: 'request_create', lang: 'bn', patientGroup: 'O+', bags: 1, hospital: `${TAG} হাসপাতাল (স্বয়ংক্রিয় পরীক্ষা)`,
    placeName: D, lat: LAT, lng: LNG, neededByMillis: neededBy, requesterPhone: '01700000000', requesterName: `${TAG} পরীক্ষা`,
    note: `${TAG} — স্বয়ংক্রিয় লোড টেস্ট, এখনই মুছে যাবে`,
  }, tok, 'write', 'request_create', [200, 429]);
  if (c.res.status === 429) { skipped.add(1, { why: 'blood_quota' }); console.warn(`SKIP blood ${role}: quota`); return; }
  if (!c.ok || !c.j || !c.j.id) return;
  act('blood_request_get', { requestId: c.j.id }, tok, 'write');
  act('request_delete', { requestId: c.j.id }, tok, 'write');
  console.log(`WROTE blood ${role} ${c.j.id}`);
}

// অ্যাম্বুলেন্স ডাকা → অবস্থা → বাতিল (রোগীর ডেমো অ্যাকাউন্ট; ঘণ্টায় ৫টার সীমা — workflow মোট ২-৩ বারের বেশি চালায় না)
export function ambFlow() {
  const tok = TOKENS.patient;
  if (!tok) return;
  const c = post('/api/community', {
    action: 'ambreq_create', lang: 'bn', district: D, upazilaId: UP, dest: { kind: 'district', district: DHAKA }, maxFare: null,
    includeUnknown: true, oxygen: false, pickup: `${TAG} পরীক্ষা`, note: `${TAG} — স্বয়ংক্রিয় লোড টেস্ট, উপেক্ষা করুন`, phone: '01700000000',
  }, tok, 'write', 'ambreq_create', [200, 409, 429]);
  if (c.res.status !== 200) {
    skipped.add(1, { why: 'amb' });
    let code = '';
    try { code = (c.res.json() || {}).code || (c.res.json() || {}).error || ''; } catch (_) { /* */ }
    console.warn(`SKIP amb: HTTP ${c.res.status} ${String(code).slice(0, 60)}`);
    return;
  }
  if (!c.j || !c.j.id) return;
  act('ambreq_status', { id: c.j.id }, tok, 'write');
  act('ambreq_cancel', { id: c.j.id, reason: 'other' }, tok, 'write');
  console.log(`WROTE amb ${c.j.id} notified=${c.j.notified}`);
}

export function handleSummary(data) {
  const out = {};
  out[__ENV.SUMMARY_FILE || 'summary.json'] = JSON.stringify(data);
  const m = data.metrics;
  const p95 = m.lf_latency ? m.lf_latency.values['p(95)'] : -1;
  const er = m.lf_errors ? m.lf_errors.values.rate : -1;
  const rps = m.http_reqs ? m.http_reqs.values.rate : 0;
  out.stdout = `stage ${STAGE} rate ${RATE}: ${rps.toFixed(1)} req/s, p95 ${p95.toFixed(0)} ms, errors ${(er * 100).toFixed(2)}%\n`;
  return out;
}
