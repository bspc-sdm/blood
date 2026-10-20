// সেবা/ডাক্তারের তালিকার এলাকা (এই ব্রাউজারে মনে থাকে); প্রথমবার নিজের এলাকার জেলা
import { computed, ref, watch, type ComputedRef } from 'vue';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { DISTRICTS, district as findDistrict, ensureAreas } from '@/lib/geo';

export interface AreaValue { district: string; upazilaId: number | null; unionId?: number | null }

const AREA_KEY = 'lf.filterArea';
export function useFilterArea(): [ComputedRef<AreaValue | null>, (v: AreaValue) => void] {
  const s = useSession();
  const v = ref<AreaValue | null>(store.getJson<AreaValue>(AREA_KEY));
  watch(
    () => [v.value, s.place] as const,
    () => {
      if (!v.value && s.place?.placeName && findDistrict(s.place.placeName)) v.value = { district: s.place.placeName, upazilaId: null, unionId: null };
    },
    { immediate: true },
  );
  watch(
    v,
    (nv) => {
      if (nv?.upazilaId != null) ensureAreas().catch(() => {});
    },
    { immediate: true },
  );
  const set = (nv: AreaValue) => {
    store.setJson(AREA_KEY, nv);
    v.value = nv;
  };
  return [computed(() => (v.value && DISTRICTS.some((d) => d.bn === v.value!.district) ? v.value : null)), set];
}
