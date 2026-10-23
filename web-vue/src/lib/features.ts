// ফিচার সুইচ (scratchpad flags CONTRACT / backend/docs/FEATURES.md): নতুন সুবিধা বন্ধ অবস্থায় আসে, মালিক অ্যাডমিন থেকে চালু করেন।
// সাময়িক ছোট রূপ — ফিচার-সুইচের কাজ main-এ এলে তার features.ts এটার জায়গা নেবে (একই API); সবাই এখান থেকেই নেয়
// (sections/appointment/online/flags.ts শুধু এটাকেই আবার দেয়)।
//   featureOn(key) — reactive; সার্ভারের `features` কাজ ({keys, v}; লগইন ছাড়াও) — জানা না গেলে বন্ধ।
//   refreshFeatures() — আবার জানা (লগইন বদলালে, ট্যাব সামনে এলে মিনিটে একবার, SSE `features_changed`)।
//   featureGuard(key) — পথের পাহারা: প্রথমবার সুইচগুলো জেনে তবে পাতা খোলে (বন্ধ থাকলে পাতা নিজেই "শিগগির আসছে")।
//   setFeaturesForTest(k) — ছোট পরীক্ষায় সার্ভার ছাড়া।
// পরীক্ষার বিল্ডে (TEST_MODE) window.__lfTest.features-এ দেওয়া চাবি সার্ভারের উত্তরের আগে মানা হয়।
import { ref, watch } from 'vue';
import { LoginRequired, call, postPublic } from './api';
import { TEST_MODE } from './testmode';
import { authUid } from './uid';
import { tr } from './i18n';

export type FeatureKey =
  | 'calls_audio' | 'calls_video' | 'appt_video' | 'chat' | 'chat_media' | 'online_chamber'
  | 'inv_pharmacy' | 'inv_lab' | 'inv_chamber' | 'inv_ambulance' | 'my_expenses' | 'desktop';

const STORE = 'lf.features';
const keys = ref<Record<string, boolean> | null>(null);
let loaded = false;
let lastAt = 0;
let inflight: Promise<void> | null = null;
let started = false;

function saved(): Record<string, boolean> | null {
  try {
    const v = JSON.parse(localStorage.getItem(STORE) || 'null');
    return v && typeof v === 'object' ? v : null;
  } catch {
    return null;
  }
}

async function fetchKeys(): Promise<Record<string, boolean> | null> {
  try {
    const r = await call<{ keys?: Record<string, boolean> }>('features');
    return r?.keys ?? {};
  } catch (e) {
    if (!(e instanceof LoginRequired)) return null;
    const r = await postPublic<{ keys?: Record<string, boolean> }>('/api/community', { action: 'features' }).catch(() => null);
    return r?.keys ?? null;
  }
}

export function refreshFeatures(force = true): Promise<void> {
  init();
  if (!force && loaded && Date.now() - lastAt < 60_000) return Promise.resolve();
  inflight ??= (async () => {
    const k = await fetchKeys();
    // জানা না গেলে আগেরটাই (শেষ জানা চাবি এই ব্রাউজারে রাখা থাকে)
    if (k) {
      keys.value = { ...k };
      try {
        localStorage.setItem(STORE, JSON.stringify(k));
      } catch {
        /* নেই */
      }
    }
    loaded = true;
    lastAt = Date.now();
    inflight = null;
  })();
  return inflight;
}

function init() {
  if (started) return;
  started = true;
  if (!TEST_MODE) keys.value = saved();
  watch(authUid, (u, old) => u !== old && void refreshFeatures(), { flush: 'sync' });
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && void refreshFeatures(false));
  void refreshFeatures();
}

/** reactive: চাবি এলে যেখানে ব্যবহার সেখানে আবার আঁকে; জানা না গেলে বন্ধ */
export function featureOn(key: FeatureKey | string): boolean {
  if (TEST_MODE) {
    const t = (window as any).__lfTest?.features;
    if (t && typeof t[key] === 'boolean') return t[key];
  }
  init();
  return keys.value?.[key] === true;
}
/** আগের নাম (CallLayer) — এখন featureOn নিজেই শুরু করে */
export const initFeatures = init;

export function featureGuard(_key: FeatureKey) {
  return async () => {
    if (!loaded) await Promise.race([refreshFeatures(false), new Promise((r) => setTimeout(r, 3000))]);
    return true;
  };
}

/** ছোট পরীক্ষার জন্য: সার্ভার ছাড়া চাবি বসানো */
export function setFeaturesForTest(k: Record<string, boolean> | null) {
  started = true;
  loaded = true;
  keys.value = k;
}

export const comingSoon = () => tr('This feature is coming soon', 'এই সুবিধা শিগগির আসছে'); // bn-ok
