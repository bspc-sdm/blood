// ফার্মেসির তালিকা: নিজের চালু প্রেসক্রিপশন (URL-এর rx থাকলে বিস্তারিতে তার হিসাব — কত % ওষুধ আছে, মোট ছাড়, আনুমানিক দাম)।
// "দোকানে গিয়ে / অনলাইন অর্ডার" এখন ছাঁকনির চিপ (তালিকা আটকায় না); প্রেসক্রিপশন বাছাই "প্রেসক্রিপশন পাঠান"-এর ভেতরে।
import { computed, type ComputedRef } from 'vue';
import { call } from '@/lib/api';
import { useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useQ } from '@/lib/query';
import type { Rx } from '@/sections/health/rx';

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
