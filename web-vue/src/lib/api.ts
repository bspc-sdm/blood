// সার্ভারের সাথে কথা (অ্যাপের ServerApi.kt-এর মতো): একই ঠিকানা, তাই আলাদা অনুমতি (CORS) লাগে না।
// লগইন থাকলে Firebase টোকেন যায়; না থাকলে শুধু অতিথির কাজগুলো (server/lib/guest.js) চলে।
import { auth } from './firebase';
import { getLang, tr } from './i18n';
import { careTarget, setCareMode } from './care';
import { testUser } from './testmode';

/** লগইন ছাড়া যে কাজগুলো চলে — server/lib/guest.js-এর তালিকার সাথে মেলানো */
export const PUBLIC_ACTIONS = new Set([
  'guest_requests',
  'emergencies_active',
  'questions', 'question', 'tips',
  'providers', 'home_providers', 'hospitals',
  'ambulances', 'pharmacies', 'bloodbanks', 'labs', 'lab_prices_public',
  'drug_search', 'catalog_get',
  'invoice_verify',
  // ফিচার সুইচ (lib/features.ts) — অতিথিও জানে কোনগুলো সবার জন্য চালু
  'features',
]);

export class ServerError extends Error {
  constructor(message: string, public status: number, public code?: string) {
    super(message);
  }
}
/** এই কাজে লগইন লাগে (নেটে যাওয়ার আগেই ধরা) */
export class LoginRequired extends Error {}

type Listener = (msg: string) => void;
const disabledListeners = new Set<Listener>();
/** অ্যাকাউন্ট বন্ধ/মোছার অপেক্ষায়: সেশন লগআউট করে কারণ দেখায় */
export function onAccountDisabled(f: Listener) {
  disabledListeners.add(f);
  return () => {
    disabledListeners.delete(f);
  };
}

type DeactivatedListener = (startedAt: number) => void;
const deactivatedListeners = new Set<DeactivatedListener>();
/** অ্যাকাউন্ট সাময়িক বন্ধ (423 `account_deactivated`): সেশন পুরো ওয়েব আটকে "আবার চালু করুন" দেখায়।
 *  startedAt — অনুরোধ কখন গিয়েছিল (আবার চালুর আগের পুরোনো উত্তর বাদ দিতে) */
export function onAccountDeactivated(f: DeactivatedListener) {
  deactivatedListeners.add(f);
  return () => {
    deactivatedListeners.delete(f);
  };
}

const featureOffListeners = new Set<() => void>();
/** সার্ভার বলল সুবিধাটা বন্ধ (404 `feature_off`) — lib/features নতুন করে আনে, যাতে বোতাম লুকায় */
export function onFeatureOff(f: () => void) {
  featureOffListeners.add(f);
  return () => {
    featureOffListeners.delete(f);
  };
}

async function request(path: string, body: Record<string, unknown>, token: string | null): Promise<any> {
  const startedAt = Date.now();
  const payload = { lang: getLang(), ...body };
  let res: Response;
  try {
    res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(payload),
      credentials: 'omit',
    });
  } catch {
    throw new ServerError(tr('No internet connection. Check and try again.', 'ইন্টারনেট সংযোগ নেই। দেখে আবার চেষ্টা করুন।'), 0);
  }
  let json: any = {};
  try {
    json = await res.json();
  } catch {
    json = {};
  }
  if (!res.ok) {
    if (json.code === 'account_disabled') disabledListeners.forEach((f) => f(json.error || ''));
    if (json.code === 'account_deactivated') deactivatedListeners.forEach((f) => f(startedAt));
    // রোগী কেয়ারগিভার থেকে সরিয়ে দিয়েছেন: মোড বন্ধ, যাতে বাকি সব আবার নিজের হয়ে চলে
    if (json.code === 'care_revoked') setCareMode(null);
    if (json.code === 'feature_off') featureOffListeners.forEach((f) => f());
    throw new ServerError(json.error || tr(`Server error (${res.status})`, `সার্ভারে সমস্যা (${res.status})`), res.status, json.code);
  }
  return json;
}

/** যেকোনো endpoint-এ POST (যেমন /api/respond) */
export async function post<T = any>(path: string, body: Record<string, unknown>): Promise<T> {
  const user = auth.currentUser ?? testUser();
  if (!user) {
    if (path === '/api/community' && PUBLIC_ACTIONS.has(String(body.action))) return request(path, body, null);
    throw new LoginRequired(tr('Log in to do this', 'এর জন্য লগইন করুন'));
  }
  const token = await user.getIdToken(false);
  // কেয়ারগিভার রোগীর হয়ে কাজ করছেন: অনুমোদিত কাজে রোগীর uid
  const target = path === '/api/community' ? careTarget(String(body.action)) : null;
  const b = target && !('asUid' in body) ? { ...body, asUid: target } : body;
  return request(path, b, token);
}

/** /api/community-র একটা কাজ: call('pharmacies', { district }) */
export function call<T = any>(action: string, body: Record<string, unknown> = {}): Promise<T> {
  return post<T>('/api/community', { action, ...body });
}

/** লগইন ছাড়াই (কোড পাঠানো/যাচাই) */
export function postPublic<T = any>(path: string, body: Record<string, unknown>): Promise<T> {
  return request(path, body, null);
}

export function errorText(e: unknown): string {
  if (e instanceof Error) return e.message;
  return tr('Something went wrong', 'কিছু একটা সমস্যা হয়েছে');
}
