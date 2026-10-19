// ফার্মেসির তালিকা (২.৩২.০, পারভেজ): আগে "দোকানে গিয়ে কিনব" / "অনলাইনে অর্ডার" বাছাই (মনে থাকে), উপরে প্রেসক্রিপশন বাছাই,
// আর প্রতিটা কার্ডে প্রেসক্রিপশনের হিসাব — কত % ওষুধ আছে, মোট ছাড়, আনুমানিক দাম, ছাড়ের শর্ত (অ্যাপের Pharmacy.kt-এর মতো)
import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { call } from '@/lib/api';
import { tr, useLang } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useQ } from '@/lib/query';
import type { Rx } from '@/sections/health/rx';
import type { BuyMode } from './data';

// ---------- কীভাবে কিনবেন (শেষ বাছাই মনে থাকে) ----------
const MODE_KEY = 'lf.pharmacy.buyMode';
export function useBuyMode(): [Ref<BuyMode | null>, (m: BuyMode) => void] {
  const v = store.get(MODE_KEY);
  const mode = ref<BuyMode | null>(v === 'shop' || v === 'online' ? v : null);
  return [mode, (m) => {
    store.set(MODE_KEY, m);
    mode.value = m;
  }];
}

// ---------- প্রেসক্রিপশন বাছাই ----------
/** নিজের (কেয়ারগিভার মোডে রোগীর) চালু, ওষুধ লেখা প্রেসক্রিপশন — নতুনটা আগে; অতিথি হলে খালি। RxPickDialog-এর একই চাবি। */
export function useMyMedRx(): ComputedRef<{ list: Rx[]; ready: boolean }> {
  const lang = useLang();
  const care = useCareMode();
  const s = useSession();
  const q = useQ<Rx[]>(() => ({
    queryKey: ['rx_mine', care.value?.uid ?? null, lang.value, s.uid],
    queryFn: () => call<{ items?: Rx[] }>('rx_mine').then((r) => r.items ?? []),
    enabled: !s.guest,
  }));
  return computed(() => {
    if (s.guest) return { list: [], ready: true };
    const list = (q.data ?? []).filter((r) => r.status === 'active' && (r.medicines ?? []).length > 0).sort((a, b) => b.createdAt - a.createdAt);
    return { list, ready: !q.isPending };
  });
}

export const rxLabel = (r: Rx) => [r.doctor?.name || tr('Doctor', 'ডাক্তার'), formatDate(r.createdAt)].join(' · ');
