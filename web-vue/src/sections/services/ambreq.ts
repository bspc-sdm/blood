// অ্যাম্বুলেন্স এক চাপে ডাকুন (২.২৮.০) — অ্যাপের AmbulanceRequest.kt-এর ওয়েব রূপ। সার্ভার: server/lib/ambreq.js
// রোগী: তালিকার উপরে "অ্যাম্বুলেন্স ডাকুন" + "ফোন" → নিশ্চিত করার ঘর → অপেক্ষার পাতা (/services/ambulance/call/:id)।
// অপেক্ষার সময় ফোন করলে অনুরোধে ওঠে; পাতায় ফিরলে একবার "ফোনে কোনো গাড়ি পেয়েছেন?"
// ভাড়া (২.৩০.০): ঘরে "কোথায় যাবেন" (বাধ্যতামূলক), অক্সিজেন, "ভাড়া সর্বোচ্চ ৳…" (চিপ + নিজে লেখা), সাথে সাথে
// "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে" (ambreq_count); অপেক্ষার পাতায় চালকদের প্রস্তাব (ভাড়া কম আগে) — "রাজি" চাপলে নিশ্চিত।
import { computed, onBeforeUnmount, onMounted, ref, watch, type ComputedRef } from 'vue';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import type { Dest } from './fare';

export interface AmbCar { id: string; name: string; phone: string; km: number | null; kmApprox?: boolean }
export interface AmbReq {
  id: string;
  status: 'open' | 'accepted' | 'arrived' | 'done' | 'byPhone' | 'cancelled' | 'expired';
  createdAt: number;
  widenAt: number;
  expiresAt: number;
  wave: number;
  notified: number;
  pickup: string;
  note: string;
  type: string | null;
  district: string;
  upazilaId: number | null;
  unionId?: number | null;
  gps?: boolean;
  ambulance?: AmbCar;
  cancelledBy: string | null;
  cancelReason: string | null;
  fallback: AmbCar[];
  called: AmbCar[];
  canRate: boolean;
  /** ভাড়ার অনুরোধ (২.৩০.০); পুরোনো অ্যাপে ডাকা হলে নেই */
  quote?: boolean;
  dest?: Dest | null;
  destLabel?: string;
  maxFare?: number | null;
  includeUnknown?: boolean;
  oxygen?: boolean;
  night?: boolean;
  quotes?: AmbQuote[];
  agreedFare?: number | null;
  raiseTo?: number | null;
  unknownFree?: number;
}
export interface AmbQuote { id: string; name: string; types: string[]; km: number | null; kmApprox?: boolean; fare: number; rating: number; ratingCount: number; at: number; expiresAt: number }
export interface FareCount { count: number; total: number; unknown: number; levels: Record<string, number>; raiseTo: number | null; hospitals: string[]; night: boolean; upazilaId?: number }
export const isActive = (r: AmbReq | null | undefined) => !!r && ['open', 'accepted', 'arrived'].includes(r.status);

export const LAST_PICKUP = 'lf.amb.pickup';
/** শেষবারের এলাকা (একই জেলায় উপজেলা না বাছা থাকলে আগে থেকে) */
export const LAST_AREA = 'lf.amb.area';
/** শেষবারের গন্তব্য আর বাজেট (২.৩০.০) */
export const LAST_DEST = 'lf.amb.dest';
export const LAST_BUDGET = 'lf.amb.budget';

/**
 * ডাকার সময় গাড়ির ধরন (২.২৯.০): "যেকোনো" (ফ্রিজার ছাড়া) আগে থেকে; সার্ভারের ধরনের চাবি (server/lib/ambulance.js TYPES)।
 * নন-এসি = general। ফ্রিজার শুধু এখানে নিজে বাছলে।
 */
export const REQ_TYPES = () => [
  { value: 'any', label: tr('Any', 'যেকোনো') },
  { value: 'ac', label: tr('AC', 'এসি') },
  { value: 'general', label: tr('Non-AC', 'নন-এসি') },
  { value: 'icu', label: tr('ICU', 'আইসিইউ') },
  { value: 'freezer', label: tr('Freezer (deceased)', 'ফ্রিজার (মরদেহ)') },
];
export const reqTypeLabel = (t: string | null | undefined) =>
  t ? REQ_TYPES().find((x) => x.value === t)?.label ?? t : tr('Any (not freezer)', 'যেকোনো (ফ্রিজার ছাড়া)');
/** অনুরোধ চলার সময় ফোন করা হয়েছে — পাতায় ফিরলে একবার জিজ্ঞেস (এই ব্রাউজারেই) */
export const ASK_KEY = 'lf.amb.askAfterCall';
export const markCalled = (reqId: string | null | undefined) => reqId && store.set(ASK_KEY, reqId);

export const kmText = (km: number | null | undefined, approx?: boolean) =>
  km == null ? null : approx ? tr(`about ${Math.max(1, km)} km`, `প্রায় ${num(Math.max(1, km))} কিমি`) : km < 1 ? tr('< 1 km', '১ কিমির কম') : `${num(km)}${tr(' km', ' কিমি')}`;

/** নিজের চালু অনুরোধ (লগইন থাকলে) — তালিকার ব্যানার, ফোনকে অনুরোধে তোলা */
export function useActiveRequest(): ComputedRef<AmbReq | null> {
  const s = useSession();
  const q = useCall<{ request: AmbReq | null }>('ambreq_status', {}, () => ({ enabled: !s.guest, staleTime: 5_000, refetchOnWindowFocus: true }));
  return computed(() => {
    const r = q.data?.request;
    return isActive(r) ? r! : null;
  });
}

/** পাতায় ফিরলে (ট্যাব আবার দেখা গেলে) */
export function useResumeTick() {
  const tick = ref(0);
  const f = () => {
    if (document.visibilityState === 'visible') tick.value++;
  };
  onMounted(() => {
    document.addEventListener('visibilitychange', f);
    window.addEventListener('focus', f);
  });
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', f);
    window.removeEventListener('focus', f);
  });
  return tick;
}

/** তালিকা থেকে ফোন করে ফিরলেন আর অনুরোধ চলছে: অপেক্ষার পাতায় (সেখানে একবার জিজ্ঞেস) */
export function useBackFromCall(active: () => AmbReq | null) {
  const nav = useNav();
  const tick = useResumeTick();
  watch(
    () => [tick.value, active()] as const,
    ([, a]) => {
      if (a && store.get(ASK_KEY) === a.id) nav(`/services/ambulance/call/${a.id}`);
    },
    { immediate: true },
  );
}

// ---------- অপেক্ষার পাতা ----------
export const PATIENT_REASONS = () => [
  { value: 'found_other', label: tr('Got another ambulance', 'অন্য গাড়ি পেয়েছি') },
  { value: 'not_needed', label: tr('No longer needed', 'আর দরকার নেই') },
  { value: 'too_slow', label: tr('Taking too long', 'অনেক দেরি হচ্ছে') },
  { value: 'wrong', label: tr('Sent by mistake', 'ভুল করে পাঠিয়েছি') },
  { value: 'other', label: tr('Other', 'অন্য কারণ') },
];
export const DRIVER_REASONS = () => [
  { value: 'vehicle_problem', label: tr('Vehicle problem', 'গাড়িতে সমস্যা') },
  { value: 'cant_reach', label: tr("Can't reach in time", 'সময়মতো পৌঁছাতে পারব না') },
  { value: 'patient_unreachable', label: tr('Patient not reachable', 'রোগীকে পাওয়া যাচ্ছে না') },
  { value: 'other', label: tr('Other', 'অন্য কারণ') },
];
export const reasonText = (r: string | null) =>
  [...PATIENT_REASONS(), ...DRIVER_REASONS()].find((x) => x.value === r)?.label ?? (r === 'patient_found_other' ? tr('Patient found another ambulance', 'রোগী অন্য গাড়ি পেয়েছেন') : '');
