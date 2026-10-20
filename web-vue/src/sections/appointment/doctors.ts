// ডাক্তারের তালিকার হিসাব: শেষবারের ছাঁকনি, লেবেল, কার্ডের অবস্থা, সামনের খোলা দিন
import { ref, watch, type Ref } from 'vue';
import { store } from '@/lib/store';
import { dhakaHourMinute, formatDate } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { DAY_MS, FEE_STEPS, NO_DOCTOR_FILTERS, chamberOpenOn, chamberState, dayShort, shortTime, slotsOf, today, type Chamber, type DoctorFilters, type Hospital } from './model';

export const TAB_ICON: Record<string, string> = { doctor: 'stethoscope', nutritionist: 'restaurant', trainer: 'fitness_center' };
export const tabLabel = (t: string) => (t === 'nutritionist' ? tr('Nutritionist', 'পুষ্টিবিদ') : t === 'trainer' ? tr('Fitness trainer', 'ফিটনেস ট্রেইনার') : tr('Doctor', 'ডাক্তার'));

/** শেষবারের ছাঁকনি (ধরনপ্রতি, এই ব্রাউজারে) — এলাকা useFilterArea-তে আলাদা, আগের মতো */
export interface SavedFilters extends DoctorFilters { hospital: Hospital | null; homeOnly: boolean }
export const NO_FILTERS: SavedFilters = { ...NO_DOCTOR_FILTERS, hospital: null, homeOnly: false };
export const filterKey = (t: string) => `lf.doctorFilters.${t}`;
export function loadFilters(t: string): SavedFilters {
  const v = store.getJson<Partial<SavedFilters>>(filterKey(t));
  if (!v || typeof v !== 'object') return NO_FILTERS;
  return {
    day: v.day === 'today' || v.day === 'tomorrow' ? v.day : null,
    openNow: v.openNow === true,
    specialty: typeof v.specialty === 'string' && v.specialty ? v.specialty : null,
    maxFee: typeof v.maxFee === 'number' && FEE_STEPS.includes(v.maxFee) ? v.maxFee : null,
    hospitalQ: typeof v.hospitalQ === 'string' ? v.hospitalQ.slice(0, 60) : '',
    gender: v.gender === 'male' || v.gender === 'female' ? v.gender : null,
    hospital: v.hospital && typeof v.hospital === 'object' && typeof v.hospital.id === 'string' ? v.hospital : null,
    homeOnly: v.homeOnly === true && t === 'doctor',
  };
}
export const feeLabel = (n: number) => tr(`Up to ৳${n.toLocaleString('en-IN')}`, `৳${num(n.toLocaleString('en-IN'))} পর্যন্ত`);
export const genderLabel = (g: string) => (g === 'female' ? tr('Female doctor', 'নারী ডাক্তার') : tr('Male doctor', 'পুরুষ ডাক্তার'));
export const dayLabel = (d: string) => (d === 'tomorrow' ? tr('Tomorrow', 'আগামীকাল') : tr('Sitting today', 'আজ বসছেন'));

/** লেখা থামার পরে (হাসপাতালের নাম লিখতে লিখতে প্রতিটা অক্ষরে সার্ভারে নয়) */
export function useDebounced<T>(src: () => T, ms: number): Ref<T> {
  const d = ref(src()) as Ref<T>;
  let t: ReturnType<typeof setTimeout> | undefined;
  watch(src, (v) => {
    clearTimeout(t);
    t = setTimeout(() => (d.value = v), ms);
  });
  return d;
}

/** তালিকার কার্ডের অবস্থা: এখন চেম্বারে / আজ ৪টা (সবুজ) — অন্য দিনে কিছু নয় */
export function cardStatus(c: Chamber | undefined): { text: string; good: boolean } | null {
  if (!c) return null;
  const s = chamberState(c);
  if (s.kind === 'now') return { text: tr('In chamber now', 'এখন চেম্বারে'), good: true };
  if (s.kind === 'today') return { text: s.start ? tr('Today ', 'আজ ') + shortTime(s.start) : tr('Today', 'আজ'), good: true };
  return null;
}

/** সামনের যে দিনগুলোতে চেম্বার খোলা (আজ থেকে ৬০ দিন) */
export function nextOpenDays(c: Chamber, count: number): number[] {
  const out: number[] = [];
  const t0 = today();
  // আজকের চেম্বারের সময় শেষ হয়ে গেলে আজ নয় (রাত পেরোনো চেম্বার বাদে) — সার্ভারের নিয়মের মতো
  const [h, m] = dhakaHourMinute(Date.now());
  const now = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  const todayOver = () => {
    const sl = slotsOf(c).filter((t) => t.days.includes(new Date(t0).getUTCDay()));
    return sl.length > 0 && sl.every((t) => t.start && t.end && t.start < t.end && now >= t.end);
  };
  for (let i = 0; i <= 60 && out.length < count; i++) {
    const d = t0 + i * DAY_MS;
    if (chamberOpenOn(c, d) && !(i === 0 && todayOver())) out.push(d);
  }
  return out;
}
/** "শনি, ১৭ অক্টোবর" */
export const dayChip = (d: number) => dayShort(new Date(d).getUTCDay()) + ', ' + formatDate(d).replace(/\s+\S+$/, '');
