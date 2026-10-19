// অ্যাপয়েন্টমেন্ট অংশের ছোট সাহায্যকারী (React-এর shared.tsx-এর কম্পোনেন্ট ছাড়া অংশ)
import { computed, onMounted, type ComputedRef } from 'vue';
import { num } from '@/lib/i18n';
import { areasReady, ensureAreas } from '@/lib/geo';
import type { HomeStats } from './model';

/** এলাকার তালিকা (উপজেলা/ইউনিয়নের নাম) আনা হলে আবার আঁকা */
export function useAreasReady(): ComputedRef<boolean> {
  const ready = computed(() => areasReady());
  onMounted(() => {
    if (!areasReady()) ensureAreas().catch(() => {});
  });
  return ready;
}

export interface AreaSel { district: string | null; upazilaId: number | null; unionId: number | null }

/** রেটিং লেখা: "৪.৫ (১২)" বা "—" */
export function ratingValue(st: Pick<HomeStats, 'rating' | 'ratingCount'>) {
  return st.ratingCount > 0 ? `${num(st.rating.toFixed(1))} (${num(st.ratingCount)})` : '—';
}
