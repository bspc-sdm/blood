// ডেস্কটপ অ্যাপের অফলাইন ইঞ্জিনের সাথে সংযোগ — ইঞ্জিন `@/offline` (ডেস্কটপের এজেন্টের; এখানে বদলানো হয় না)।
// সাধারণ ওয়েবে offline.isDesktop() মিথ্যা — পাতা সরাসরি API ডাকে।
// পরীক্ষায়: setOfflineStore(নকল) (Vitest) বা পরীক্ষার বিল্ডে window.__lfOffline (Playwright) — installOffline দিয়ে বসে।
import { onBeforeUnmount, reactive, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { installOffline, offline, type BizKind, type OfflineStatus, type OfflineStore } from '@/offline';
import { TEST_MODE } from '@/lib/testmode';

declare global {
  interface Window {
    /** শুধু পরীক্ষার বিল্ডে: Playwright নকল OfflineStore বসায় */
    __lfOffline?: OfflineStore;
  }
}

/** পরীক্ষায় নকল; null দিলে আবার "ডেস্কটপ নয়" (ওয়েবের মতো) */
export function setOfflineStore(s: OfflineStore | null) {
  installOffline(s ?? notDesktop);
}
const no = () => Promise.reject(new Error('not desktop'));
const notDesktop: OfflineStore = {
  isDesktop: () => false,
  ready: no,
  items: () => Promise.resolve([]),
  labPrices: () => Promise.resolve({ items: [], fees: [] }),
  chamberDay: (date) => Promise.resolve({ date, appointments: [], fees: {} }),
  record: no,
  pending: () => 0,
  syncNow: () => Promise.resolve(),
  onChange: () => () => {},
  print: no,
};
if (TEST_MODE && typeof window !== 'undefined' && window.__lfOffline) installOffline(window.__lfOffline);

/** ডেস্কটপে স্টোর, নইলে null */
export function offlineStore(): OfflineStore | null {
  try {
    return offline.isDesktop() ? offline : null;
  } catch {
    return null;
  }
}
export const isDesktop = () => offlineStore() != null;

/**
 * এক ব্যবসার ডেস্কটপ অবস্থা: চালু কি না, সিরিজ (D1…), কয়টা মেলানোর অপেক্ষায়, বাতিল করা কম্পিউটার।
 * desktop মিথ্যা হলে (সাধারণ ওয়েব) পাতা সরাসরি API ডাকে।
 */
export function useOffline(kind: BizKind, bizId: MaybeRefOrGetter<string | null | undefined>) {
  const st = reactive({ desktop: isDesktop(), ready: false, series: '', deviceId: '', asOf: 0, pending: 0, revoked: false, error: '' });
  let off: (() => void) | undefined;
  const onStatus = (x: OfflineStatus) => {
    st.pending = x.pending;
    st.revoked = x.revoked;
    st.error = x.lastError ?? '';
  };
  watch(
    () => toValue(bizId),
    async (id) => {
      const s = offlineStore();
      st.desktop = !!s;
      st.ready = false;
      if (!s || !id) return;
      try {
        const r = await s.ready(kind, id);
        Object.assign(st, { ready: true, series: r.series, deviceId: r.deviceId, asOf: r.asOf, pending: r.pending, error: '' });
      } catch (e) {
        st.error = e instanceof Error ? e.message : String(e);
      }
      off?.();
      off = s.onChange(onStatus);
    },
    { immediate: true },
  );
  onBeforeUnmount(() => off?.());
  return {
    st,
    store: () => offlineStore(),
    async syncNow() {
      const s = offlineStore();
      if (!s) return;
      await s.syncNow();
      st.pending = s.pending();
    },
  };
}

// ---------- মালিক বনাম কাউন্টার (নিরাপত্তা পর্যালোচনা I7) ----------
// ডেস্কটপে দুই পিন: কাউন্টার (বিক্রি) আর মালিক (বাতিল, দাম বদল, ১০%-এর বেশি ছাড়, স্টক সমন্বয়, রিপোর্ট)।
// ওয়েবে role() null (কোনো সীমা নেই), requireOwner() সবসময় true। পুরোনো স্টাবে এই দুটো না থাকলেও চলে।
type WithRole = { role?: () => 'owner' | 'counter' | null; requireOwner?: () => Promise<boolean> };
/** 'counter' হলে রিপোর্ট আর "দিনের শেষ" লুকানো */
export function deskRole(): 'owner' | 'counter' | null {
  const s = offlineStore() as unknown as WithRole | null;
  try {
    return s?.role?.() ?? null;
  } catch {
    return null;
  }
}
/** মালিকের পিন চাওয়া (ডেস্কটপে); ওয়েবে সবসময় true */
export async function requireOwner(): Promise<boolean> {
  const s = offline as unknown as WithRole;
  if (!isDesktop() || typeof s.requireOwner !== 'function') return true;
  return s.requireOwner();
}
/** বাতিল/দাম বদল/বড় ছাড়/স্টক সমন্বয়ের আগে: মালিক না হলে পিন — না দিলে Error('owner_only') */
export async function ownerGate(): Promise<void> {
  if (!(await requireOwner())) throw Object.assign(new Error('owner_only'), { code: 'owner_only' });
}
