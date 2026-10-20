// সরাসরি API (ব্রাউজার ছাড়া) — ডেমো রোলের লগইন টোকেন, /api/community-র action, মালিকের (অ্যাডমিন) টোকেন, ক্রন।
// যাত্রার যে ধাপ ওয়েবে নেই (যেমন অ্যাপের পুশ টোকেন) বা যেখানে শুধু সার্ভারের ফল দেখা দরকার, সেখানে।
import { execFileSync } from 'node:child_process';
import { BASE } from './stack.mjs';

export const OWNER = 'ekapkbd@gmail.com';
const tokens = new Map();

/** ID token-এর ভেতরের uid (emulator localId সবসময় দেয় না) */
export const uidOf = (idToken) => JSON.parse(Buffer.from(idToken.split('.')[1], 'base64url').toString()).user_id;

async function signIn(custom) {
  const r = await fetch(`${BASE}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=e2e`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: custom, returnSecureToken: true }),
  }).then((x) => x.json());
  if (!r.idToken) throw new Error(`emulator sign-in failed: ${JSON.stringify(r).slice(0, 200)}`);
  return r;
}

/** ডেমো রোলের ID token (লগইন পাতার "ডেমো" তালিকার মতোই: verify-code {demo}) — uid সহ */
export async function demo(role) {
  if (tokens.has(role)) return tokens.get(role);
  const r = await fetch(`${BASE}/api/auth/verify-code`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ demo: role }) }).then((x) => x.json());
  if (!r.token) throw new Error(`demo login ${role}: ${JSON.stringify(r).slice(0, 200)}`);
  const s = await signIn(r.token);
  const out = { token: s.idToken, uid: uidOf(s.idToken), role };
  tokens.set(role, out);
  return out;
}

/** মালিক (ADMIN_EMAILS) — emulator-এ সরাসরি custom token */
export async function owner() {
  if (tokens.has('owner')) return tokens.get('owner');
  const v = await (await fetch(`${BASE}/__e2e/custom-token?email=${OWNER}`)).json();
  const s = await signIn(v.token);
  const out = { token: s.idToken, uid: uidOf(s.idToken), role: 'owner' };
  tokens.set('owner', out);
  return out;
}

export class ApiError extends Error {
  constructor(status, body, action) {
    super(`${action}: ${status} ${body?.error ?? JSON.stringify(body).slice(0, 200)}`);
    this.status = status;
    this.body = body;
  }
}

/** /api/community — who: {token} (demo()/owner()) বা null (অতিথি) */
export async function act(who, action, body = {}) {
  const r = await fetch(`${BASE}/api/community`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(who ? { authorization: `Bearer ${who.token}` } : {}) },
    body: JSON.stringify({ action, lang: 'bn', ...body }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new ApiError(r.status, j, action);
  return j;
}

/** অন্য HTTP পথ (যেমন /api/respond) */
export async function post(who, path, body = {}) {
  const r = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(who ? { authorization: `Bearer ${who.token}` } : {}) },
    body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new ApiError(r.status, j, path);
  return j;
}

/** দৈনিক ক্রন (প্রোডাকশনে 03:00 UTC) — e2e/spring.sh-এর CRON_SECRET */
export async function cronDaily() {
  const r = await fetch(`${BASE}/api/cron/daily`, { headers: { authorization: 'Bearer e2e-cron-secret-0123456789' } });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new ApiError(r.status, j, 'cron daily');
  return j;
}

/**
 * সার্ভারের ডাটাবেসে সরাসরি পড়া (শুধু যাচাইয়ের জন্য) — E2E_PG_URL (postgres://…) থাকলে psql দিয়ে; না থাকলে null।
 * সারির JSON ফেরত (query নিজেই json দেয়: SELECT json_agg(…))।
 */
export function sql(query) {
  const url = process.env.E2E_PG_URL;
  if (!url) return null;
  const out = execFileSync('psql', [url, '-AtXq', '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8' }).trim();
  return out ? JSON.parse(out) : null;
}

/**
 * রোগীর (who) একটা অ্যাপয়েন্টমেন্টের অনুরোধ ডাক্তারের (doctor) কাছে — ঢাকার আজকের পরের firstDay..firstDay+20 দিনের যেটায় হয়।
 * অ্যাপের রোগীকে প্রেসক্রিপশন এখন শুধু অ্যাপয়েন্টমেন্ট থেকেই সরাসরি যায় (কাগজের রোগীর প্রেসক্রিপশন রোগী নিজে কোড দিয়ে যোগ করেন)।
 */
export async function bookAppt(who, doctor, firstDay, note = 'ই২ই প্রেসক্রিপশনের জন্য') {
  const DAY = 86400000;
  const dhakaToday = Math.floor((Date.now() + 6 * 3600000) / DAY) * DAY;
  let last;
  for (let i = firstDay; i < firstDay + 20; i++) {
    try {
      return (await act(who, 'book', { providerUid: doctor.uid, date: dhakaToday + i * DAY, chamberIndex: 0, note })).id;
    } catch (e) {
      last = e;
    }
  }
  throw last;
}
