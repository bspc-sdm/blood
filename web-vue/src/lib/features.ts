// ফিচার সুইচ (backend/docs/FEATURES.md): নতুন সুবিধা বন্ধ অবস্থায় আসে, মালিক অ্যাডমিন পাতা থেকে ধাপে ধাপে চালু করেন।
//   featureOn('chat')      — reactive: template/computed-এ সরাসরি; বন্ধ থাকলে বোতাম/ট্যাব/মেনু লুকানো
//   refreshFeatures()      — সার্ভার থেকে নতুন করে (শুরু, লগইন, ফিরে আসা — মিনিটে একবারের বেশি নয়; SSE features_changed)
//   featureGuard('chat')   — রাউটের beforeEnter: প্রথমবারের উত্তরের অপেক্ষা, তারপর পাতা খোলে (পাতা নিজে "শিগগির আসছে" দেখায়);
//                            featureGuard('chat', '/') — বন্ধ থাকলে "এই সুবিধা শিগগির আসছে" টোস্ট আর অন্য পাতায়
//   gateRoutes(router, { '/messages': 'chat' }) — পথের শুরু দেখে একসাথে কয়েকটা (বন্ধ হলে অন্য পাতায়)
//   isFeatureOff(e)        — সার্ভারের 404 `feature_off` (বার্তা সার্ভারই পাঠায়; কখনো ক্র্যাশ নয়)
//   comingSoon() / featureSoonText() — "এই সুবিধা শিগগির আসছে"
//   setFeaturesForTest(k)  — ছোট পরীক্ষায় সার্ভার ছাড়া
// পরীক্ষার বিল্ডে (TEST_MODE) window.__lfTest.features-এ দেওয়া চাবি সার্ভারের উত্তরের আগে মানা হয়।
// SSE `features_changed`: calls/events.ts শোনে যখন কল চালু (তার স্ট্রিম খোলা থাকে); নইলে এখানকার নিজের স্ট্রিম।
import { computed, reactive, watch } from 'vue';
import type { NavigationGuard, RouteLocationRaw, Router } from 'vue-router';
import { call, onFeatureOff, ServerError } from './api';
import { auth } from './firebase';
import { tr } from './i18n';
import { store } from './store';
import { TEST_MODE, testUser } from './testmode';
import { authUid } from './uid';
import { toast } from '../ui/toast';

/** চাবিগুলো — সার্ভারের Features.KEYS-এর সাথে হুবহু */
export const FEATURE_KEYS = [
  'calls_audio',
  'calls_video',
  'appt_video',
  'chat',
  'chat_media',
  'online_chamber',
  'inv_pharmacy',
  'inv_lab',
  'inv_chamber',
  'inv_ambulance',
  'my_expenses',
  'desktop',
  // Lifolo Pay (payments CONTRACT)
  'pay_manual',
  'pay_chamber_advance',
  'pay_delivery',
  'pay_lab',
  'pay_ambulance',
  'pay_due',
  'pay_bkash_pgw',
  // চিকিৎসা পদ্ধতি আর নতুন পেশাজীবী
  'practice_systems',
  'pro_physio',
  'pro_psychologist',
  // স্বাস্থ্য তথ্যের সম্মতি (backend/docs/CONSENT.md)
  'health_consent',
] as const;
export type FeatureKey = (typeof FEATURE_KEYS)[number];

/** বন্ধ সুবিধার পাতায় (ডিপ লিংক) দেখানো লেখা */
export const featureSoonText = () => tr('This feature is coming soon', 'এই সুবিধা শিগগির আসছে'); // bn-ok
export const comingSoon = featureSoonText;

const STORE_KEY = 'lf.features';
/** ফিরে আসা/ট্যাব দেখা গেলে আবার জানতে চাওয়া — এর চেয়ে ঘন নয় */
const MIN_GAP_MS = 60_000;

interface Saved {
  uid: string | null;
  keys: Record<string, boolean>;
  v: number;
}

const saved = TEST_MODE ? null : store.getJson<Saved>(STORE_KEY);
const state = reactive({
  /** শেষ জানা অবস্থা (ইন্টারনেট না থাকলেও চলে — এই ব্রাউজারে রাখা) */
  keys: { ...(saved?.keys || {}) } as Record<string, boolean>,
  v: saved?.v ?? 0,
  /** কার জন্য (লগইন বদলালে আবার আনা) */
  uid: saved?.uid ?? null,
  /** অন্তত একবার সার্ভার থেকে এসেছে */
  loaded: false,
  at: 0,
});

/** পড়া যায় এমন অবস্থা (পরীক্ষা, ডিবাগ) */
export const features: Readonly<typeof state> = state;

/** পরীক্ষার বিল্ডে window.__lfTest.features (একেকটা চাবি) */
function testKeys(): Record<string, boolean> | null {
  if (!TEST_MODE) return null;
  const f = (window as unknown as { __lfTest?: { features?: Record<string, boolean> } }).__lfTest?.features;
  return f && typeof f === 'object' ? f : null;
}

/** এই সুবিধা এখন চালু? (reactive; জানা না গেলে বন্ধ) */
export function featureOn(key: FeatureKey | string): boolean {
  const t = testKeys();
  if (t && typeof t[key] === 'boolean') return t[key];
  return state.keys[key] === true;
}

function currentUid(): string | null {
  return auth.currentUser?.uid ?? testUser()?.uid ?? null;
}

let inFlight: Promise<void> | null = null;
let readyResolve: (() => void) | null = null;
const ready = new Promise<void>((r) => {
  readyResolve = r;
});

/** প্রথমবার আনা শেষ (সফল বা ব্যর্থ) — গার্ড এর জন্য অপেক্ষা করে */
export function featuresReady(): Promise<void> {
  return ready;
}

/**
 * সার্ভার থেকে নতুন করে (`features`, লগইন ছাড়াও)। force (ডিফল্ট) না হলে: একই মানুষের জন্য শেষ আনার ১ মিনিটের মধ্যে আবার নয়।
 * ব্যর্থ হলে আগের জানা অবস্থাই থাকে (কখনো throw করে না)। refreshFeatures(false) বা refreshFeatures({ force: false }) দুটোই চলে।
 */
export function refreshFeatures(opts: boolean | { force?: boolean } = true): Promise<void> {
  const force = typeof opts === 'boolean' ? opts : opts.force !== false;
  const uid = currentUid();
  if (!force && state.loaded && state.uid === uid && Date.now() - state.at < MIN_GAP_MS) return Promise.resolve();
  if (inFlight) return inFlight;
  inFlight = (async () => {
    try {
      const r = await call<{ keys?: Record<string, boolean>; v?: number }>('features');
      if (currentUid() !== uid) return; // এর মধ্যে লগইন বদলেছে — পরের ডাক আনবে
      const keys: Record<string, boolean> = {};
      for (const [k, on] of Object.entries(r?.keys || {})) keys[k] = on === true;
      state.keys = keys;
      state.v = Number(r?.v) || 0;
      state.uid = uid;
      state.loaded = true;
      state.at = Date.now();
      store.setJson(STORE_KEY, { uid, keys, v: state.v } satisfies Saved);
    } catch {
      // ইন্টারনেট নেই/সার্ভারে সমস্যা: আগের জানা অবস্থা
    } finally {
      inFlight = null;
      readyResolve?.();
      readyResolve = null;
    }
  })();
  return inFlight;
}

/** ছোট পরীক্ষার জন্য: সার্ভার ছাড়া চাবি বসানো (null = সব বন্ধ) */
export function setFeaturesForTest(k: Record<string, boolean> | null) {
  state.keys = { ...(k || {}) };
  state.loaded = true;
  state.at = Date.now();
  started = true;
}

/** সার্ভারের 404 `feature_off`? (সুবিধা বন্ধ — বার্তা সার্ভারের, দেখিয়ে দিলেই হয়) */
export function isFeatureOff(e: unknown): boolean {
  return e instanceof ServerError && e.code === 'feature_off';
}

/**
 * রাউটের beforeEnter। প্রথমবারের উত্তরের অপেক্ষা করে (ডিপ লিংকে খোলা পাতা ভুল করে বন্ধ না দেখায়)।
 * [fallback] ছাড়া: পাতা খোলে (পাতা নিজে [comingSoon] দেখায়)। [fallback] দিলে: বন্ধ থাকলে টোস্ট আর সেখানে।
 */
export function featureGuard(key: FeatureKey, fallback?: RouteLocationRaw): NavigationGuard {
  return async () => {
    if (!state.loaded) await Promise.race([refreshFeatures(false), new Promise((r) => setTimeout(r, 3000))]);
    if (fallback === undefined || featureOn(key)) return true;
    toast(featureSoonText());
    return fallback;
  };
}

/** পথের শুরু → চাবি: router.beforeEach দিয়ে সব মেলা পথে [featureGuard] (বন্ধ হলে টোস্ট আর [fallback]) */
export function gateRoutes(router: Router, prefixes: Record<string, FeatureKey>, fallback: RouteLocationRaw = '/') {
  const list = Object.entries(prefixes).sort((a, b) => b[0].length - a[0].length);
  router.beforeEach((to, from, next) => {
    const hit = list.find(([p]) => to.path === p || to.path.startsWith(p.endsWith('/') ? p : p + '/'));
    if (!hit) return next();
    void (featureGuard(hit[1], fallback) as (...a: unknown[]) => Promise<unknown>)(to, from).then((r) =>
      r === true ? next() : next(r as RouteLocationRaw),
    );
  });
}

// ---------------------------------------------------------------- SSE: /api/events-এর `features_changed`
// কল চালু থাকলে calls/events.ts-এর স্ট্রিমই শোনে (একই ট্যাবে দুটো স্ট্রিম নয়); নইলে এখানে।
// EventSource-এ Authorization হেডার যায় না, তাই fetch দিয়ে স্ট্রিম পড়া (টোকেনসহ)।

let sseAbort: AbortController | null = null;
let sseUid: string | null = null;
let sseRetry: ReturnType<typeof setTimeout> | undefined;
let sseFails = 0;

function stopEvents() {
  sseAbort?.abort();
  sseAbort = null;
  sseUid = null;
  clearTimeout(sseRetry);
}

async function startEvents(uid: string) {
  if (sseUid === uid && sseAbort) return;
  stopEvents();
  const ctrl = new AbortController();
  sseAbort = ctrl;
  sseUid = uid;
  try {
    const user = auth.currentUser;
    if (!user) return;
    const token = await user.getIdToken(false);
    const res = await fetch('/api/events', { headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }, signal: ctrl.signal, credentials: 'omit' });
    if (!res.ok || !res.body || !String(res.headers.get('content-type') || '').includes('text/event-stream')) throw new Error(`events ${res.status}`);
    sseFails = 0;
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buf = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += value;
      let i: number;
      while ((i = buf.search(/\r?\n\r?\n/)) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i).replace(/^\r?\n\r?\n/, '');
        const name = parseEventName(block);
        if (name === 'features_changed') void refreshFeatures(true);
        else if (name) streamTaps.forEach((f) => f(name));
      }
    }
  } catch {
    if (ctrl.signal.aborted) return;
    sseFails++;
  }
  if (ctrl.signal.aborted || sseUid !== uid) return;
  // বন্ধ হয়ে গেলে আবার (বারবার ব্যর্থ হলে আরও দেরিতে, সর্বোচ্চ ৫ মিনিট)
  sseAbort = null;
  const wait = Math.min(300_000, 5_000 * 2 ** Math.min(sseFails, 6));
  sseRetry = setTimeout(() => {
    if (sseUid === uid || sseUid === null) {
      sseUid = null;
      void startEvents(uid);
    }
  }, wait);
}

// এই স্ট্রিমের অন্য ঘটনার নাম (যেমন pay_* — sections/pay/live.ts); কলের স্ট্রিম চললে সেটার tapEvents
const streamTaps = new Set<(name: string) => void>();
export function tapFeatureStream(f: (name: string) => void) {
  streamTaps.add(f);
  return () => void streamTaps.delete(f);
}

/** একটা SSE ব্লকের `event:` নাম */
export function parseEventName(block: string): string | null {
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) return line.slice(6).trim();
  }
  return null;
}

let started = false;
/**
 * একবার (main.ts; CallLayer-ও ডাকে — দ্বিতীয়বার কিছু করে না): এখনই আনা, লগইন বদলালে আবার, ট্যাবে ফিরে এলে
 * (মিনিটে একবার), কোনো কাজে সার্ভার `feature_off` বললে নতুন করে (বোতাম লুকাতে), আর লগইন থাকলে SSE।
 */
export function initFeatures() {
  if (started) return;
  started = true;
  void refreshFeatures(true);
  watch(authUid, (uid, old) => {
    if (uid !== old) void refreshFeatures(true);
  });
  // নিজের SSE শুধু যখন কলের স্ট্রিম বন্ধ (calls/CallLayer: calls_audio বা appt_video চালু হলে সেটাই শোনে)
  const callsListen = computed(() => featureOn('calls_audio') || featureOn('appt_video'));
  watch(
    [authUid, callsListen],
    ([uid, calls]) => {
      if (uid && !calls && !TEST_MODE) void startEvents(uid);
      else stopEvents();
    },
    { immediate: true },
  );
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void refreshFeatures(false);
    });
  }
  onFeatureOff(() => void refreshFeatures(true));
}
