// ফিচার সুইচ (মালিকের নিয়ম ২০২৬-১০-২৩: নতুন সব সুবিধা বন্ধ অবস্থায় আসে; flags CONTRACT / backend/docs/FEATURES.md)।
// রসিদের চাবি: inv_pharmacy, inv_lab, inv_chamber, inv_ambulance, my_expenses। যাচাই পাতা /v/:code সবসময় খোলা।
// আসল মডিউল web-vue/src/lib/features.ts (flags এজেন্টের) — থাকলে তার featureOn; না থাকলে (এখনো main-এ আসেনি)
// নিজে `features` কাজ থেকে একবার আনা (না পেলে বন্ধ)। পরীক্ষার বিল্ডে সব চালু (CONTRACT: Test/CI profile),
// window.__lfTest.features দিয়ে কোনোটা বন্ধ করা যায়।
import { shallowRef } from 'vue';
import { call } from '@/lib/api';
import { TEST_MODE } from '@/lib/testmode';
import { authUid } from '@/lib/uid';
import { watch } from 'vue';

export type InvFeature = 'inv_pharmacy' | 'inv_lab' | 'inv_chamber' | 'inv_ambulance' | 'my_expenses';

const real = Object.values(import.meta.glob('../../lib/features.ts', { eager: true }))[0] as { featureOn?: (k: string) => boolean } | undefined;

const keys = shallowRef<Record<string, boolean>>({});
let loadedFor: string | null | undefined;
function load(uid: string | null) {
  if (loadedFor === uid) return;
  loadedFor = uid;
  if (!uid) {
    keys.value = {};
    return;
  }
  call<{ keys?: Record<string, boolean> }>('features', {})
    .then((r) => (keys.value = r.keys ?? {}))
    .catch(() => (keys.value = {}));
}
if (!real?.featureOn && !TEST_MODE) watch(authUid, (u) => load(u), { immediate: true });

/** চাবি চালু কি না (reactive — টেমপ্লেটে/computed-এ পড়লে বদলালে আবার আঁকে) */
export function invFeature(key: InvFeature): boolean {
  if (TEST_MODE) {
    const t = (window.__lfTest as { features?: Record<string, boolean> } | undefined)?.features;
    return t?.[key] ?? true;
  }
  if (real?.featureOn) return real.featureOn(key);
  return keys.value[key] === true;
}
