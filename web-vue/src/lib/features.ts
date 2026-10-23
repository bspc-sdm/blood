// ফিচার সুইচ (scratchpad flags CONTRACT / backend/docs/FEATURES.md): নতুন সুবিধা বন্ধ অবস্থায় আসে, মালিক অ্যাডমিন থেকে চালু করেন।
// সাময়িক ছোট রূপ (ফিচার-সুইচের কাজ main-এ এলে তার features.ts এটার জায়গা নেবে — একই API):
//   featureOn(key) — reactive; refreshFeatures() — সার্ভারের `features` কাজ ({keys, v}); featureGuard(key) — পথের পাহারা।
// পরীক্ষার বিল্ডে (TEST_MODE) সব চালু, যদি না window.__lfTest.features-এ কোনোটা false দেওয়া থাকে।
import { reactive, watch } from 'vue';
import { call } from './api';
import { TEST_MODE } from './testmode';
import { authUid } from './uid';
import { tr } from './i18n';

export type FeatureKey =
  | 'calls_audio' | 'calls_video' | 'appt_video' | 'chat' | 'chat_media' | 'online_chamber'
  | 'inv_pharmacy' | 'inv_lab' | 'inv_chamber' | 'inv_ambulance' | 'my_expenses' | 'desktop';

const state = reactive<{ keys: Record<string, boolean>; v: number; loaded: boolean }>({ keys: {}, v: 0, loaded: false });
const STORE = 'lf.features';
try {
  const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
  if (saved && typeof saved === 'object') state.keys = saved;
} catch {
  /* নেই */
}

export function featureOn(key: FeatureKey): boolean {
  if (TEST_MODE) {
    const t = (window as any).__lfTest?.features;
    return !(t && t[key] === false);
  }
  return state.keys[key] === true;
}

let inflight: Promise<void> | null = null;
let lastAt = 0;
export function refreshFeatures(force = true): Promise<void> {
  if (!force && Date.now() - lastAt < 60_000 && state.loaded) return Promise.resolve();
  inflight ??= (async () => {
    try {
      const r = await call<{ keys?: Record<string, boolean>; v?: number }>('features');
      state.keys = { ...(r?.keys ?? {}) };
      state.v = Number(r?.v) || 0;
      try {
        localStorage.setItem(STORE, JSON.stringify(state.keys));
      } catch {
        /* নেই */
      }
    } catch {
      /* আগেরটাই থাকে */
    } finally {
      state.loaded = true;
      lastAt = Date.now();
      inflight = null;
    }
  })();
  return inflight;
}

/** লগইন বদলালে আর ট্যাব আবার সামনে এলে (মিনিটে একবারের বেশি নয়) */
let started = false;
export function initFeatures() {
  if (started || TEST_MODE) return;
  started = true;
  watch(authUid, () => void refreshFeatures(), { immediate: true });
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && void refreshFeatures(false));
}

/** পথের পাহারা: প্রথমবার সুইচগুলো জেনে তবে পাতা খোলে (বন্ধ থাকলে পাতা নিজেই "শিগগির আসছে" দেখায়) */
export function featureGuard(_key: FeatureKey) {
  return async () => {
    if (!TEST_MODE && !state.loaded) await refreshFeatures(false);
    return true;
  };
}
export const comingSoon = () => tr('This feature is coming soon', 'এই সুবিধা শিগগির আসছে'); // bn-ok
