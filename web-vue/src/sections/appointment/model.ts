// অ্যাপয়েন্টমেন্টের তথ্যের ধরন আর নিয়ম — অ্যাপের Care.kt / Appointment.kt / HomeCare.kt / Catalog.kt-এর মতো।
import { computed, shallowRef, watch, type ComputedRef } from 'vue';
import { useCall } from '@/lib/query';
import { formatTimeHM, todayUtcMidnight } from '@/lib/format';
import { isBangla, num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';

/** যাঁরা "আমার চেম্বার/বাসায় সেবা" পান */
export const PROVIDER_TYPES = ['doctor', 'nurse', 'nutritionist', 'trainer'];
/** চেম্বার আর অ্যাপয়েন্টমেন্ট: ডাক্তার, পুষ্টিবিদ, ফিটনেস ট্রেইনার (নার্স শুধু বাসায় সেবা) */
export const CHAMBER_TYPES = ['doctor', 'nutritionist', 'trainer'];
/** বাসায় সেবা: ডাক্তার আর নার্স */
export const HOME_TYPES = ['doctor', 'nurse'];
export const MAX_CHAMBERS = 3;
export const MAX_CHAMBER_TIMES = 4;
export const HOME_RADII = [3, 5, 10, 20];
export const DAY_MS = 24 * 60 * 60 * 1000;

// ---------- ধরন ----------
export interface ChamberTime { days: number[]; start: string; end: string }
export interface Chamber {
  hospitalId: string | null;
  hospitalName: string;
  hospitalNameBn: string;
  district: string;
  upazilaId: number | null;
  unionId: number | null;
  room: string;
  schedule: string;
  fee: string;
  appointments: boolean;
  serialPhone: string;
  days: number[];
  start: string;
  end: string;
  noFee: boolean;
  custom: boolean;
  address: string;
  times: ChamberTime[];
  /** রোগীপ্রতি গড় সময় (মিনিট) — সিরিয়ালের আনুমানিক সময়ে, আগের হিসাব কম থাকলে */
  patientMin: number | null;
}
export interface HomeVisit {
  enabled: boolean;
  radiusKm: number;
  upazilaIds: number[];
  services: string[];
  schedule: string;
  fee: string;
  phone: string;
  noFee: boolean;
  times: ChamberTime[];
}
export interface Provider {
  uid: string;
  type: string;
  name: string;
  specialties: string[];
  degrees: string;
  gender: string;
  listed: boolean;
  appointments: boolean;
  chambers: Chamber[];
  homeVisit: HomeVisit | null;
  rating: number;
  ratingCount: number;
  photo: string;
}
export interface HomePro {
  uid: string;
  type: string;
  name: string;
  specialties: string[];
  degrees: string;
  gender: string;
  services: string[];
  radiusKm: number;
  upazilaIds: number[];
  schedule: string;
  times: ChamberTime[];
  fee: string;
  noFee: boolean;
  phone: string;
  km: number | null;
  rating: number;
  ratingCount: number;
  called: boolean;
  myRating: number;
  canRate: boolean;
  photo: string;
}
export interface Appointment {
  id: string;
  role: 'patient' | 'provider';
  otherName: string;
  type: string;
  chamber: string;
  chamberBn: string;
  room: string;
  start: string;
  serial: number;
  schedule: string;
  date: number;
  note: string;
  status: string;
  providerNote: string;
  otherPhone: string;
  otherKey: string;
  createdAt: number;
}
export interface Hospital {
  id: string;
  nameEn: string;
  nameBn: string;
  type: string;
  district: string;
  upazilaId: number | null;
  unionId: number | null;
  address: string;
  phone: string;
}
export interface FamilyMember { id: string; name: string; age?: number | null; gender?: string }
export interface HomeStats {
  enabled: boolean;
  blocked: boolean;
  calls: { 7: number; 30: number; 90?: number; total?: number };
  callers: number;
  daily: number[];
  rating: number;
  ratingCount: number;
  comments: { rating: number; comment?: string; at?: number }[];
  reports: number;
}

// ---------- সার্ভারের উত্তর থেকে ----------
const arr = <T,>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);
const s = (v: unknown) => (typeof v === 'string' ? v : '');
const n = (v: unknown, d = 0) => (typeof v === 'number' && Number.isFinite(v) ? v : d);
const intOrNull = (v: unknown) => (Number.isInteger(v) ? (v as number) : null);

export function parseTimes(v: unknown): ChamberTime[] {
  return arr<any>(v).filter((t) => t && typeof t === 'object').map((t) => ({ days: arr<number>(t.days).filter(Number.isInteger), start: s(t.start), end: s(t.end) }));
}
export function parseChamber(o: any): Chamber {
  const times = parseTimes(o?.times);
  return {
    hospitalId: o?.hospitalId == null ? null : String(o.hospitalId),
    hospitalName: s(o?.hospitalName),
    hospitalNameBn: s(o?.hospitalNameBn),
    district: s(o?.district),
    upazilaId: intOrNull(o?.upazilaId),
    unionId: intOrNull(o?.unionId),
    room: s(o?.room),
    schedule: s(o?.schedule),
    fee: s(o?.fee),
    appointments: o?.appointments === true,
    serialPhone: s(o?.serialPhone),
    days: arr<number>(o?.days).filter(Number.isInteger),
    start: s(o?.start),
    end: s(o?.end),
    noFee: o?.noFee === true,
    custom: o?.custom === true,
    address: s(o?.address),
    times: times.length > 1 ? times : [],
    patientMin: intOrNull(o?.patientMin),
  };
}
export function parseHomeVisit(o: any): HomeVisit | null {
  if (!o || typeof o !== 'object') return null;
  return {
    enabled: o.enabled === true,
    radiusKm: n(o.radiusKm, 5) || 5,
    upazilaIds: arr<number>(o.upazilaIds).filter(Number.isInteger),
    services: arr<string>(o.services).filter((x) => typeof x === 'string'),
    schedule: s(o.schedule),
    fee: s(o.fee),
    phone: s(o.phone),
    noFee: o.noFee === true,
    times: parseTimes(o.times),
  };
}
export function parseProvider(o: any): Provider {
  return {
    uid: s(o?.uid),
    type: s(o?.type),
    name: s(o?.name),
    specialties: arr<string>(o?.specialties),
    degrees: s(o?.degrees),
    gender: s(o?.gender),
    listed: o?.listed !== false,
    appointments: o?.appointments === true,
    chambers: arr<any>(o?.chambers).map(parseChamber),
    homeVisit: parseHomeVisit(o?.homeVisit),
    rating: n(o?.rating),
    ratingCount: n(o?.ratingCount),
    photo: s(o?.photo),
  };
}
export function parseHomePro(o: any): HomePro {
  return {
    uid: s(o?.uid),
    type: s(o?.type),
    name: s(o?.name),
    specialties: arr<string>(o?.specialties),
    degrees: s(o?.degrees),
    gender: s(o?.gender),
    services: arr<string>(o?.services),
    radiusKm: n(o?.radiusKm, 5) || 5,
    upazilaIds: arr<number>(o?.upazilaIds).filter(Number.isInteger),
    schedule: s(o?.schedule),
    times: parseTimes(o?.times),
    fee: s(o?.fee),
    noFee: o?.noFee === true,
    phone: s(o?.phone),
    km: typeof o?.km === 'number' ? o.km : null,
    rating: n(o?.rating),
    ratingCount: n(o?.ratingCount),
    called: o?.called === true,
    myRating: n(o?.myRating),
    canRate: o?.canRate === true,
    photo: s(o?.photo),
  };
}
export function parseAppointment(o: any): Appointment {
  return {
    id: s(o?.id),
    role: o?.role === 'provider' ? 'provider' : 'patient',
    otherName: s(o?.otherName),
    type: s(o?.type),
    chamber: s(o?.chamber),
    chamberBn: s(o?.chamberBn),
    room: s(o?.room),
    start: s(o?.start),
    serial: n(o?.serial),
    schedule: s(o?.schedule),
    date: n(o?.date),
    note: s(o?.note),
    status: s(o?.status),
    providerNote: s(o?.providerNote),
    otherPhone: s(o?.otherPhone),
    otherKey: s(o?.otherKey),
    createdAt: n(o?.createdAt),
  };
}

// ---------- বার আর সময় ----------
/** বাংলাদেশে সপ্তাহ শুরু শনিবার (০ = রবি … ৬ = শনি) */
export const WEEK_ORDER = [6, 0, 1, 2, 3, 4, 5];
export function dayShort(d: number): string {
  switch (d) {
    case 0: return tr('Sun', 'রবি');
    case 1: return tr('Mon', 'সোম');
    case 2: return tr('Tue', 'মঙ্গল');
    case 3: return tr('Wed', 'বুধ');
    case 4: return tr('Thu', 'বৃহস্পতি');
    case 5: return tr('Fri', 'শুক্র');
    default: return tr('Sat', 'শনি');
  }
}
/** "17:00" → "বিকাল ৫:০০" */
export function hhmmText(hhmm: string): string {
  const [h, m] = hhmm.split(':').map((x) => parseInt(x, 10));
  if (!Number.isFinite(h)) return hhmm;
  return formatTimeHM(h, Number.isFinite(m) ? m : 0);
}
export function slotText(t: ChamberTime): string {
  return [
    WEEK_ORDER.filter((d) => t.days.includes(d)).map(dayShort).join(', '),
    t.start && t.end ? `${hhmmText(t.start)} – ${hhmmText(t.end)}` : '',
  ].filter(Boolean).join(' · ');
}
export const slotsOf = (c: Pick<Chamber, 'times' | 'days' | 'start' | 'end'>): ChamberTime[] => (c.times.length ? c.times : [{ days: c.days, start: c.start, end: c.end }]);
/** "শনি, রবি · বিকাল ৫:০০ – রাত ৯:০০" — প্রতিটা ভাগ আলাদা লাইনে (+ নোট) */
export const chamberTimeText = (c: Chamber) => [...slotsOf(c).map(slotText), c.schedule].filter(Boolean).join('\n');
export const timesText = (times: ChamberTime[], schedule: string) => [...times.map(slotText), schedule].filter(Boolean).join('\n');
export const feeText = (noFee: boolean, fee: string) => (noFee ? tr('No fee', 'ফি নেই') : fee ? tr('Fee: ', 'ফি: ') + fee : '');
export const chamberLabel = (c: Pick<Chamber, 'hospitalName' | 'hospitalNameBn'>) => (isBangla() && c.hospitalNameBn ? c.hospitalNameBn : c.hospitalName);
export const chamberArea = (c: Chamber) => [c.address, areaLabel(c.district, c.upazilaId, c.unionId)].filter(Boolean).join(', ');
/** এই তারিখে (UTC মধ্যরাত) বসেন কি না */
export function chamberOpenOn(c: Chamber, dateUtcMidnight: number): boolean {
  const days = slotsOf(c).flatMap((t) => t.days);
  if (!days.length) return true;
  return days.includes(new Date(dateUtcMidnight).getUTCDay());
}
/** ভাগ বদলালে উপরের days (সব বার) আর start/end (প্রথম ভাগ) মিলিয়ে রাখা */
export function withSlots(c: Chamber, list: ChamberTime[]): Chamber {
  const l = list.length ? list : [{ days: [], start: '', end: '' }];
  return { ...c, times: l.length > 1 ? l : [], days: [...new Set(l.flatMap((t) => t.days))].sort(), start: l[0].start, end: l[0].end };
}
export const canBook = (p: Provider) => p.appointments && p.chambers.some((c) => c.appointments);

const toMin = (t: string) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(t);
  return m ? +m[1] * 60 + +m[2] : null;
};
const WEEK = 7 * 1440;
function intervals(t: ChamberTime): [number, number][] {
  const st = toMin(t.start);
  let e = toMin(t.end);
  if (st == null || e == null) return [];
  if (e <= st) e += 1440;
  return t.days.flatMap((d) => {
    const a = d * 1440 + st;
    const b = d * 1440 + e!;
    return b <= WEEK ? [[a, b] as [number, number]] : [[a, WEEK] as [number, number], [0, b - WEEK] as [number, number]];
  });
}
/** একজন একই সময়ে দুই জায়গায় বসতে পারেন না — সময় মিলে যাওয়া দুই চেম্বার (ক্রম), নইলে null */
export function clash(chambers: { slots: ChamberTime[] }[]): [number, number] | null {
  const all = chambers.flatMap((c, i) => c.slots.flatMap((t) => intervals(t).map((iv) => ({ i, iv })))).sort((x, y) => x.iv[0] - y.iv[0]);
  for (let i = 1; i < all.length; i++) {
    for (let j = i - 1; j >= 0; j--) if (all[j].iv[1] > all[i].iv[0]) return [all[j].i, all[i].i];
  }
  return null;
}

// ---------- নাম: ব্যাজ, বিশেষজ্ঞতা, সেবা, হাসপাতালের ধরন ----------
export function badgeLabel(type: string): string {
  switch (type) {
    case 'doctor': return tr('Doctor', 'ডাক্তার');
    case 'nurse': return tr('Nurse', 'নার্স');
    case 'nutritionist': return tr('Nutritionist', 'পুষ্টিবিদ');
    case 'trainer': return tr('Fitness trainer', 'ফিটনেস ট্রেইনার');
    default: return tr('Health Expert', 'স্বাস্থ্য বিশেষজ্ঞ');
  }
}
export function badgeIcon(type: string): string {
  switch (type) {
    case 'doctor': return 'stethoscope';
    case 'nurse': return 'medical_services';
    case 'nutritionist': return 'restaurant';
    case 'trainer': return 'fitness_center';
    default: return 'health_and_safety_filled';
  }
}

interface CatItem { code: string; en: string; bn: string; hidden?: boolean }
type CatLists = Record<string, Record<string, CatItem[]>>;
// Vue: shallowRef — তালিকা এলে নাম/বাছাই যেখানে দেখানো সেখানেও নতুন করে আঁকে (React-এ useCatalog-এর পাতা আবার আঁকত)
const catalog = shallowRef<CatLists | null>(null);

/** বাছাইয়ের তালিকা (অ্যাডমিন প্যানেল থেকে বদলায়) — একবার আনা; না পেলে অ্যাপের ভেতরেরটা */
export function useCatalog(): ComputedRef<CatLists | null> {
  const q = useCall<{ lists?: CatLists }>('catalog_get', {}, { staleTime: 12 * 60 * 60 * 1000 });
  watch(
    () => q.data?.lists,
    (l) => {
      if (l) catalog.value = l;
    },
    { immediate: true },
  );
  return computed(() => q.data?.lists ?? null);
}
function catVisible(kind: string, type: string): string[] | null {
  const l = catalog.value?.[kind]?.[type];
  const v = l?.filter((i) => i.code && !i.hidden).map((i) => i.code);
  return v && v.length ? v : null;
}
function catLabel(kind: string, code: string): string | null {
  const k = catalog.value?.[kind];
  if (!k) return null;
  for (const t of Object.keys(k)) {
    const it = k[t]?.find((i) => i.code === code);
    if (it) return (isBangla() ? it.bn : it.en) || null;
  }
  return null;
}

const SPEC: Record<string, string[]> = {
  doctor: ['medicine', 'pediatrics', 'gynecology', 'cardiology', 'orthopedics', 'ent', 'eye', 'dermatology', 'neurology', 'psychiatry', 'surgery', 'nephrology', 'urology', 'gastro', 'oncology', 'dental', 'endocrinology', 'chest', 'physical_medicine', 'general'],
  nurse: ['general_nursing', 'icu', 'midwife', 'home_care', 'pediatric_nursing', 'psychiatric_nursing'],
  nutritionist: ['clinical_nutrition', 'weight_diet', 'diabetes_diet', 'child_nutrition', 'pregnancy_nutrition', 'kidney_diet', 'sports_nutrition'],
  trainer: ['weight_loss', 'strength', 'yoga', 'rehab_exercise', 'elderly_fitness', 'women_fitness', 'sports_training'],
};
const SPEC_LABEL: Record<string, [string, string]> = {
  medicine: ['Medicine', 'মেডিসিন'],
  pediatrics: ['Child (Pediatrics)', 'শিশু রোগ'],
  gynecology: ['Gynae & Obstetrics', 'গাইনি ও প্রসূতি'],
  cardiology: ['Heart (Cardiology)', 'হৃদরোগ'],
  orthopedics: ['Bone (Orthopedics)', 'হাড় ও জোড়া'],
  ent: ['ENT', 'নাক-কান-গলা'],
  eye: ['Eye', 'চক্ষু'],
  dermatology: ['Skin (Dermatology)', 'চর্ম ও যৌন'],
  neurology: ['Neurology', 'স্নায়ুরোগ'],
  psychiatry: ['Psychiatry', 'মানসিক রোগ'],
  surgery: ['Surgery', 'সার্জারি'],
  nephrology: ['Kidney (Nephrology)', 'কিডনি রোগ'],
  urology: ['Urology', 'মূত্ররোগ'],
  gastro: ['Gastroenterology', 'পরিপাকতন্ত্র ও লিভার'],
  oncology: ['Cancer (Oncology)', 'ক্যান্সার'],
  dental: ['Dental', 'দন্ত'],
  endocrinology: ['Diabetes & Hormone', 'ডায়াবেটিস ও হরমোন'],
  chest: ['Chest & Lung', 'বক্ষব্যাধি'],
  physical_medicine: ['Physical medicine', 'ফিজিক্যাল মেডিসিন'],
  general: ['General practitioner', 'সাধারণ চিকিৎসক'],
  general_nursing: ['General nursing', 'সাধারণ নার্সিং'],
  icu: ['ICU / Critical care', 'আইসিইউ'],
  midwife: ['Midwife', 'মিডওয়াইফ'],
  home_care: ['Home care', 'হোম কেয়ার'],
  pediatric_nursing: ['Child nursing', 'শিশু নার্সিং'],
  psychiatric_nursing: ['Mental health nursing', 'মানসিক স্বাস্থ্য নার্সিং'],
  clinical_nutrition: ['Clinical nutrition', 'ক্লিনিক্যাল পুষ্টি'],
  weight_diet: ['Weight control diet', 'ওজন নিয়ন্ত্রণের খাদ্য'],
  diabetes_diet: ['Diabetes diet', 'ডায়াবেটিসের খাদ্য'],
  child_nutrition: ['Child nutrition', 'শিশুর পুষ্টি'],
  pregnancy_nutrition: ['Pregnancy nutrition', 'গর্ভকালীন পুষ্টি'],
  kidney_diet: ['Kidney diet', 'কিডনি রোগের খাদ্য'],
  sports_nutrition: ['Sports nutrition', 'খেলাধুলার পুষ্টি'],
  weight_loss: ['Weight loss', 'ওজন কমানো'],
  strength: ['Strength training', 'শক্তি বাড়ানো'],
  yoga: ['Yoga', 'যোগব্যায়াম'],
  rehab_exercise: ['Rehab exercise', 'রিহ্যাব ব্যায়াম'],
  elderly_fitness: ['Elderly fitness', 'বয়স্কদের ব্যায়াম'],
  women_fitness: ["Women's fitness", 'নারীদের ফিটনেস'],
  sports_training: ['Sports training', 'খেলাধুলার প্রশিক্ষণ'],
};
export function specialtiesOf(type: string): string[] {
  const t = ['nurse', 'nutritionist', 'trainer'].includes(type) ? type : 'doctor';
  return catVisible('specialty', t) ?? SPEC[t];
}
export function specialtyLabel(code: string): string {
  const c = catLabel('specialty', code);
  if (c) return c;
  const l = SPEC_LABEL[code];
  return l ? tr(l[0], l[1]) : code;
}

const HOME_NURSE = ['injection', 'saline', 'cannula', 'dressing', 'catheter', 'nebulizer', 'bp_sugar', 'elderly_care', 'newborn_care', 'post_op'];
const HOME_DOCTOR = ['checkup', 'follow_up', 'elderly_care', 'bedridden', 'child', 'palliative', 'post_op'];
const HOME_LABEL: Record<string, [string, string]> = {
  injection: ['Injection', 'ইনজেকশন'],
  saline: ['Saline', 'স্যালাইন'],
  cannula: ['Cannula', 'ক্যানুলা'],
  dressing: ['Wound dressing', 'ক্ষত ড্রেসিং'],
  catheter: ['Catheter', 'ক্যাথেটার'],
  nebulizer: ['Nebulizer', 'নেবুলাইজার'],
  bp_sugar: ['BP / sugar check', 'প্রেসার / সুগার মাপা'],
  elderly_care: ['Elderly care', 'বয়স্ক রোগী'],
  newborn_care: ['Newborn care', 'নবজাতকের যত্ন'],
  post_op: ['After surgery', 'অপারেশনের পরে'],
  checkup: ['Check-up at home', 'বাসায় রোগী দেখা'],
  follow_up: ['Follow-up', 'ফলো-আপ'],
  bedridden: ['Bedridden patient', 'শয্যাশায়ী রোগী'],
  child: ['Child patient', 'শিশু রোগী'],
  palliative: ['Palliative care', 'প্যালিয়েটিভ কেয়ার'],
};
export function homeServicesOf(type: string): string[] {
  const t = type === 'doctor' ? 'doctor' : 'nurse';
  return catVisible('homeService', t) ?? (t === 'doctor' ? HOME_DOCTOR : HOME_NURSE);
}
export function homeServiceLabel(code: string): string {
  const c = catLabel('homeService', code);
  if (c) return c;
  const l = HOME_LABEL[code];
  return l ? tr(l[0], l[1]) : code;
}

export const HOSPITAL_TYPES = ['govt_mch', 'govt_specialized', 'govt_district', 'govt_uhc', 'govt_hospital', 'private_hospital', 'clinic', 'diagnostic'];
export function hospitalTypeLabel(t: string): string {
  switch (t) {
    case 'govt_mch': return tr('Govt. medical college hospital', 'সরকারি মেডিকেল কলেজ হাসপাতাল');
    case 'govt_specialized': return tr('Govt. specialized hospital', 'সরকারি বিশেষায়িত হাসপাতাল');
    case 'govt_district': return tr('District / Sadar hospital', 'জেলা / সদর হাসপাতাল');
    case 'govt_uhc': return tr('Upazila Health Complex', 'উপজেলা স্বাস্থ্য কমপ্লেক্স');
    case 'govt_hospital': return tr('Other govt. hospital', 'অন্যান্য সরকারি হাসপাতাল');
    case 'private_hospital': return tr('Private hospital', 'বেসরকারি হাসপাতাল');
    case 'clinic': return tr('Clinic', 'ক্লিনিক');
    default: return tr('Diagnostic centre', 'ডায়াগনস্টিক সেন্টার');
  }
}
export const hospitalLabel = (h: Pick<Hospital, 'nameEn' | 'nameBn'>) => (isBangla() && h.nameBn ? h.nameBn : h.nameEn);

// ---------- হাসপাতালের নামের মিল (অ্যাপের Hospitals.similar) ----------
const STOP = new Set(['hospital', 'hospitals', 'clinic', 'clinics', 'ltd', 'limited', 'pvt', 'private', 'center', 'centre', 'and', 'the', 'of', 'diagnostic', 'diagnostics', 'medical', 'health', 'complex', 'general']);
const normName = (name: string) => name.toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9ঀ-৿]+/g, ' ').split(' ').filter((w) => w && !STOP.has(w)).join(' ');
function lev(a: string, b: string) {
  const prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  const cur = new Array<number>(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    for (let j = 0; j <= b.length; j++) prev[j] = cur[j];
  }
  return prev[b.length];
}
function similarity(a: string, b: string) {
  const x = normName(a);
  const y = normName(b);
  if (!x || !y) return 0;
  if (x === y) return 1;
  const l = 1 - lev(x, y) / Math.max(x.length, y.length);
  const wa = new Set(x.split(' '));
  const wb = new Set(y.split(' '));
  const inter = [...wa].filter((w) => wb.has(w)).length;
  const jac = inter / new Set([...wa, ...wb]).size;
  const contains = x.includes(y) || y.includes(x) ? 0.85 : 0;
  return Math.max(l, jac, contains);
}
export function similarHospitals(q: string, list: Hospital[], limit = 5): Hospital[] {
  const t = q.trim();
  if (t.length < 3) return [];
  return list
    .map((h) => [h, Math.max(similarity(t, h.nameEn), h.nameBn.includes(t) ? 0.9 : 0)] as const)
    .filter((x) => x[1] >= 0.6)
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map((x) => x[0]);
}

// ---------- অ্যাপয়েন্টমেন্টের অবস্থা ----------
export function statusLabel(st: string): string {
  switch (st) {
    case 'pending': return tr('Waiting', 'অপেক্ষায়');
    case 'confirmed': return tr('Confirmed', 'নিশ্চিত');
    case 'declined': return tr('Not accepted', 'নেওয়া হয়নি');
    case 'expired': return tr('No reply', 'উত্তর আসেনি');
    case 'completed': return tr('Done', 'সম্পন্ন');
    default: return tr('Cancelled', 'বাতিল');
  }
}
export const isActive = (st: string) => st === 'pending' || st === 'confirmed';
export const statusTone = (st: string): 'good' | 'primary' | undefined => (st === 'confirmed' ? 'good' : st === 'pending' ? 'primary' : undefined);
export const apptChamber = (a: Appointment) => (isBangla() && a.chamberBn ? a.chamberBn : a.chamber);
/** "১৭ অক্টোবর ২০২৬, বিকাল ৫:০০ থেকে" */
export function apptWhen(a: Appointment, formatDate: (m: number) => string): string {
  return formatDate(a.date) + (a.start ? ', ' + tr('from ', '') + hhmmText(a.start) + tr('', ' থেকে') : '');
}
export const serialText = (serial: number) => tr('Serial #', 'সিরিয়াল নং ') + num(serial);
export const today = () => todayUtcMidnight();

/** রেটিং: "★ ৪.৫ (১২)" */
export function ratingLine(rating: number, count: number, meta?: string | null): string | null {
  const parts = [count > 0 ? `★ ${num(rating.toFixed(1))} (${num(count)})` : null, meta || null].filter(Boolean);
  return parts.length ? parts.join('  ·  ') : null;
}
/** বাসায় সেবা: দূরত্ব একবারই (সার্ভার পুরো কিমিতে দেয়, কমপক্ষে ২) */
export function homeDistance(km: number | null): string | null {
  if (km == null) return null;
  const k = Math.max(2, Math.ceil(km));
  return k <= 2 ? tr('Within 2 km', '২ কিমির মধ্যে') : tr(`About ${k} km away`, `প্রায় ${num(k)} কিমি দূরে`);
}
export const coverageText = (radiusKm: number, upazilaLabels: string[]) => [tr(`Within ${radiusKm} km`, `${num(radiusKm)} কিমির মধ্যে`), ...upazilaLabels].join(' · ');

// ---------- চেম্বারের আজকের অবস্থা (২.৩৩.০, পারভেজ) ----------
// সার্ভারের care.js chamberNowOpen/chamberToday-এর মতো, ঢাকার সময়ে: আজকের ভাগ শেষ = শুরু < শেষ আর এখন ≥ শেষ;
// রাত পেরোনো ভাগ (১৬:০০–০১:০০) মাঝরাতের পরেও "এখন চেম্বারে"। অ্যাপের ChamberState (Care.kt) একই নিয়মে।
const HM = /^\d{2}:\d{2}$/;
/** কোন অবস্থা: এখন বসছেন / আজ পরে / পরের কোনো দিন / জানা নেই (সময় লেখা নেই) / সামনে নেই */
export type ChamberStateKind = 'now' | 'today' | 'later' | 'unknown' | 'none';
export interface ChamberState {
  kind: ChamberStateKind;
  /** ছোট লেখা: "আজ বসবেন ৪টা–৯টা" / "আজ বন্ধ · আগামীকাল ১০টা" */
  text: string;
  /** পরের বসার দিন (UTC মধ্যরাত) আর সময় — বুকিংয়ে আগে থেকে বাছা আর "কোন চেম্বারে?"-এ লম্বা লেখা */
  date: number | null;
  start: string;
  end: string;
}
/** "16:00" → "৪টা" / "4 PM"; "16:30" → "৪:৩০" / "4:30 PM" */
export function shortTime(hhmm: string): string {
  const [h, m] = hhmm.split(':').map((x) => parseInt(x, 10));
  if (!Number.isFinite(h)) return hhmm;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  if (isBangla()) return m ? `${num(h12)}:${num(String(m).padStart(2, '0'))}` : `${num(h12)}টা`;
  return `${h12}${m ? ':' + String(m).padStart(2, '0') : ''} ${h < 12 ? 'AM' : 'PM'}`;
}
const range = (t: ChamberTime) => `${shortTime(t.start)}–${shortTime(t.end)}`;
const dhakaHHMM = (now: number) => {
  const d = new Date(now + 6 * 3600 * 1000);
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
};
const dhakaToday = (now: number) => Math.floor((now + 6 * 3600 * 1000) / DAY_MS) * DAY_MS;
const slotsOn = (c: Chamber, date: number) => {
  const wd = new Date(date).getUTCDay();
  return slotsOf(c).filter((t) => !t.days.length || t.days.includes(wd)).slice().sort((a, b) => a.start.localeCompare(b.start));
};
export function chamberState(c: Chamber, now = Date.now()): ChamberState {
  const t0 = dhakaToday(now);
  const t = dhakaHHMM(now);
  const timed = (x: ChamberTime) => HM.test(x.start) && HM.test(x.end);
  const all = slotsOf(c);
  if (!all.some(timed) && !all.some((x) => x.days.length)) return { kind: 'unknown', text: '', date: null, start: '', end: '' };
  // এখন বসছেন (আজকের ভাগ, বা গতকালের রাত পেরোনো ভাগ এখনো চলছে)
  const nowSlot = chamberOpenOn(c, t0) ? slotsOn(c, t0).find((x) => timed(x) && (x.start < x.end ? t >= x.start && t < x.end : t >= x.start)) : undefined;
  const lastNight = chamberOpenOn(c, t0 - DAY_MS) ? slotsOn(c, t0 - DAY_MS).find((x) => timed(x) && x.start >= x.end && t < x.end) : undefined;
  const cur = nowSlot ?? lastNight;
  if (cur) return { kind: 'now', text: tr(`In chamber now · until ${shortTime(cur.end)}`, `এখন চেম্বারে · ${shortTime(cur.end)} পর্যন্ত`), date: nowSlot ? t0 : t0 - DAY_MS, start: cur.start, end: cur.end };
  const todayOpen = chamberOpenOn(c, t0);
  if (todayOpen) {
    const next = slotsOn(c, t0).find((x) => !timed(x) || x.start > t);
    if (next) return { kind: 'today', text: timed(next) ? tr(`Today ${range(next)}`, `আজ বসবেন ${range(next)}`) : tr('Sits today', 'আজ বসবেন'), date: t0, start: next.start, end: next.end };
  }
  for (let i = 1; i <= 7; i++) {
    const d = t0 + i * DAY_MS;
    if (!chamberOpenOn(c, d)) continue;
    const first = slotsOn(c, d)[0];
    const at = first && timed(first) ? ' ' + shortTime(first.start) : '';
    const when = i === 1 ? tr('Tomorrow', 'আগামীকাল') + at : dayShort(new Date(d).getUTCDay()) + at;
    const pre = todayOpen ? tr('Done today', 'আজ শেষ') : tr('Closed today', 'আজ বন্ধ');
    return { kind: 'later', text: `${pre} · ${when}`, date: d, start: first?.start ?? '', end: first?.end ?? '' };
  }
  return { kind: 'none', text: tr('No upcoming days', 'সামনে বসার দিন নেই'), date: null, start: '', end: '' };
}
/** "কোন চেম্বারে?"-এ পরের সময়: "আজ, বিকাল ৪:০০ – রাত ১:০০" / "আগামীকাল (মঙ্গল, ২০ অক্টোবর), সকাল ১০:০০" */
export function nextSlotText(s: ChamberState, now = Date.now()): string {
  if (s.date == null) return s.text;
  const t0 = dhakaToday(now);
  const time = s.start && s.end ? `${hhmmText(s.start)} – ${hhmmText(s.end)}` : '';
  if (s.kind === 'now') return [tr('In chamber now', 'এখন চেম্বারে'), time].filter(Boolean).join(' · ');
  const day = s.date === t0 ? tr('Today', 'আজ') : s.date === t0 + DAY_MS ? tr('Tomorrow', 'আগামীকাল') : dayShort(new Date(s.date).getUTCDay());
  const d = new Date(s.date);
  const dm = isBangla() ? `${num(d.getUTCDate())} ${['জানুয়ারি', 'ফেব্রুয়ারি', 'মার্চ', 'এপ্রিল', 'মে', 'জুন', 'জুলাই', 'আগস্ট', 'সেপ্টেম্বর', 'অক্টোবর', 'নভেম্বর', 'ডিসেম্বর'][d.getUTCMonth()]}` : `${d.getUTCDate()} ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getUTCMonth()]}`;
  return `${day} (${dm})` + (time ? ', ' + time : '');
}
/** তালিকা/শিটের ছোট নাম: বন্ধনীর লেখা বাদ */
export const chamberShort = (c: Pick<Chamber, 'hospitalName' | 'hospitalNameBn'>) => chamberLabel(c).replace(/\s*\([^)]*\)\s*/g, ' ').replace(/\s+/g, ' ').trim() || chamberLabel(c);
/** উপজেলা (না থাকলে জেলা) */
export const chamberPlace = (c: Chamber) => (c.upazilaId != null ? areaLabel(c.district, c.upazilaId).split(',')[0] : areaLabel(c.district)) || c.address;
/** ছোট ফি: "৳৫০০" / "ফি নেই"; বোঝা না গেলে লেখাটাই */
export function feeShort(c: Pick<Chamber, 'fee' | 'noFee'>): string {
  if (c.noFee) return tr('No fee', 'ফি নেই');
  const t = c.fee.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d))).replace(/(\d),(?=\d{3})/g, '$1');
  const m = /\d+/.exec(t);
  if (!m) return c.fee;
  return '৳' + num(Number(m[0]).toLocaleString('en-IN'));
}

// ---------- ডাক্তারের তালিকার ছাঁকনি (২.৩৩.০) — শেষবারেরটা মনে থাকে (ব্রাউজারে, ধরনপ্রতি) ----------
export interface DoctorFilters {
  day: 'today' | 'tomorrow' | null;
  openNow: boolean;
  specialty: string | null;
  maxFee: number | null;
  hospitalQ: string;
  gender: 'male' | 'female' | null;
}
export const NO_DOCTOR_FILTERS: DoctorFilters = { day: null, openNow: false, specialty: null, maxFee: null, hospitalQ: '', gender: null };
export const FEE_STEPS = [300, 500, 1000];
export interface SpecialtyFacet { code: string; n: number }
