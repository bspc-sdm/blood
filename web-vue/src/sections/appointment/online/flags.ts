// ফিচার সুইচ (মালিকের নিয়ম, ২৩ অক্টোবর ২০২৬): নতুন সব কিছু বন্ধ অবস্থায় যায়, অ্যাডমিন থেকে চালু হয়।
// online_chamber — অনলাইন চেম্বারের সেটআপ, ব্যাজ/ছাঁকনি, বুকিং, ফির অংশ; appt_video — "ভিডিও কলে যুক্ত হোন" (online_chamber-ও লাগে)।
// অস্থায়ী: flags শাখার web-vue/src/lib/features.ts (featureOn) main-এ এলে নিচের featureOn সেটা থেকে নেওয়া হবে —
// এই ফাইলের বাকিটা একই থাকবে। চালু না হলে (বা জানা না গেলে) বন্ধ — পাতা আগের মতো।
import { ref } from 'vue';
import { LoginRequired, call, postPublic } from '@/lib/api';

const keys = ref<Record<string, boolean> | null>(null);
let started = false;

async function fetchKeys(): Promise<Record<string, boolean>> {
  try {
    const r = await call<{ keys?: Record<string, boolean> }>('features');
    return r?.keys ?? {};
  } catch (e) {
    if (!(e instanceof LoginRequired)) return {};
    const r = await postPublic<{ keys?: Record<string, boolean> }>('/api/community', { action: 'features' }).catch(() => null);
    return r?.keys ?? {};
  }
}
export function refreshFeatures(): Promise<void> {
  started = true;
  return fetchKeys().then((k) => {
    keys.value = k;
  });
}
/** reactive: keys এলে যেখানে ব্যবহার সেখানে আবার আঁকে */
export function featureOn(key: string): boolean {
  if (!started) void refreshFeatures();
  return keys.value?.[key] === true;
}
/** পরীক্ষার জন্য: সার্ভার ছাড়া চাবি বসানো */
export function setFeaturesForTest(k: Record<string, boolean> | null) {
  started = true;
  keys.value = k;
}

export const ONLINE_CHAMBER = 'online_chamber';
export const APPT_VIDEO = 'appt_video';
export const onlineOn = () => featureOn(ONLINE_CHAMBER);
export const videoOn = () => onlineOn() && featureOn(APPT_VIDEO);
