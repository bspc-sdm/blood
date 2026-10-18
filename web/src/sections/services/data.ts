// সেবার তালিকার ডেটা: অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি — সার্ভারের উত্তরের ধরন আর লেখা
// (অ্যাপের Ambulance.kt / Pharmacy.kt / BloodBank.kt / RxNet.kt-এর মতো)
import { call } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDate, formatKm, formatTimeHM, timeAgo } from '@/lib/format';
import { areaLabel } from '@/lib/geo';
import { NO_PRICE, discountText, homeFeeText, taka, type PriceSummary, type PublicPrices } from '@/lib/labprice';

export type Kind = 'ambulance' | 'pharmacy' | 'bloodbank' | 'lab';
export const KINDS: Kind[] = ['ambulance', 'pharmacy', 'bloodbank', 'lab'];
export const isKind = (k: string | undefined): k is Kind => !!k && (KINDS as string[]).includes(k);

export interface Ambulance {
  id: string; name: string; phone: string; types: string[]; district: string; upazilaId: number | null; area: string; fare: string;
  available: boolean; availableAt: number; rating: number; ratingCount: number; canRate?: boolean; busy?: boolean; busySince?: number;
  /** দূরত্ব (কিমি, এক দশমিক) — তালিকায় lat/lng পাঠালে; kmApprox: গাড়ির জায়গা দেওয়া নেই, জেলার কেন্দ্র ধরে */
  km?: number | null; kmApprox?: boolean;
  /** খালি থাকলে কখন পর্যন্ত (চালকের কার্ডে "আর ৪৫ মিনিট") */
  availableUntil?: number;
}
export interface Pharmacy {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; openAt: string; closeAt: string;
  open24: boolean; openNow: boolean; closedUntil: number; closedDays: number[]; delivery: boolean; deliveryNote: string; discount: number;
  rating: number; ratingCount: number; km: number | null;
  /** রেটিং দেওয়া যায় (অ্যাপ থেকে ফোনের ১ ঘণ্টা পর, বা এই দোকান থেকে ওষুধ নেওয়ার পর; সার্ভার মনে রাখে) */
  canRate?: boolean;
  /** কোন দিকে রেটিং দেওয়া যায় — ব্যবহার সবসময়; দাম কিনলে; ডেলিভারি অনলাইন অর্ডার পৌঁছালে (সার্ভার ঠিক করে) */
  rateAspects?: RateAspect[];
  /** দিকভিত্তিক গড় (যেগুলোতে অন্তত একটা রেটিং) */
  aspects?: Partial<Record<RateAspect, { rating: number; count: number }>>;
}
/** ফার্মেসির রেটিং দোকানের সেবার — ওষুধ কেমন কাজ করল তার নয় */
export type RateAspect = 'behaviour' | 'price' | 'delivery';
export const RATE_ASPECTS: RateAspect[] = ['behaviour', 'price', 'delivery'];
/** রেটিংয়ের জানালার পুরো নাম */
export const aspectLabel = (a: RateAspect) =>
  a === 'behaviour' ? tr('Behaviour', 'ব্যবহার') : a === 'price' ? tr('Right medicine & fair price', 'ঠিক ওষুধ ও ন্যায্য দাম') : tr('Delivery speed', 'ডেলিভারির দ্রুততা');
/** বিস্তারিতের ছোট লাইনের নাম */
const aspectShort = (a: RateAspect) =>
  a === 'behaviour' ? tr('Behaviour', 'ব্যবহার') : a === 'price' ? tr('Medicine & price', 'ঠিক ওষুধ ও দাম') : tr('Delivery', 'ডেলিভারি');
/** "ব্যবহার ★৪.৬ · ডেলিভারি ★৪.২" (কোনো দিকে রেটিং না থাকলে খালি) */
export const aspectsText = (x?: Pharmacy['aspects']) =>
  RATE_ASPECTS.filter((a) => (x?.[a]?.count ?? 0) > 0).map((a) => `${aspectShort(a)} ★${num(x![a]!.rating.toFixed(1))}`).join(' · ');
export interface BloodBank {
  id: string; name: string; kind: string; phone: string; district: string; upazilaId: number | null; address: string; open24: boolean;
  openAt: string; closeAt: string; openNow: boolean; services: string[]; closedDays: number[];
}
export interface Lab {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null;
  homeCollection: boolean; hours: string;
  /** পরীক্ষা ও দামের সারাংশ (কয়টা, সবচেয়ে কম, ছাড়, বাসার চার্জ) — তালিকা না থাকলে null; পুরো তালিকা lab_prices_public */
  prices?: PriceSummary | null;
}
export interface ListResult<T> { items: T[]; busy?: T[]; busyCount?: number; nearestId?: string | null }

// ---------- লেবেল ----------
export const kindLabel = (k: Kind) =>
  k === 'ambulance' ? tr('Ambulance', 'অ্যাম্বুলেন্স') : k === 'pharmacy' ? tr('Pharmacy', 'ফার্মেসি') : k === 'bloodbank' ? tr('Blood bank', 'ব্লাড ব্যাংক') : tr('Lab', 'প্যাথলজি');
export const kindIcon = (k: Kind) => (k === 'ambulance' ? 'ambulance' : k === 'pharmacy' ? 'medication' : k === 'bloodbank' ? 'bloodtype' : 'local_hospital');

interface CatItem { code: string; en: string; bn: string; hidden?: boolean }
type Lists = Record<string, Record<string, CatItem[]>>;

/** অ্যাডমিনের তালিকা (অ্যাম্বুলেন্সের ধরন, ব্লাড ব্যাংকের সেবা); না এলে অ্যাপের ভেতরেরটা */
export function useCatalog(): Lists | null {
  const q = useCall<{ lists: Lists }>('catalog_get', {}, { staleTime: 5 * 60_000 });
  return q.data?.lists ?? null;
}
function catLabel(lists: Lists | null, kind: string, code: string): string | null {
  const it = lists?.[kind]?.all?.find((i) => i.code === code);
  return it ? tr(it.en, it.bn) : null;
}

const AMB_BUILT_IN = ['general', 'ac', 'icu', 'freezer'];
export const ambulanceTypes = (lists: Lists | null) => lists?.ambulanceType?.all?.filter((i) => !i.hidden).map((i) => i.code) ?? AMB_BUILT_IN;
export function ambulanceTypeLabel(lists: Lists | null, t: string): string {
  return catLabel(lists, 'ambulanceType', t) ?? (t === 'general' ? tr('General', 'সাধারণ') : t === 'ac' ? tr('AC', 'এসি') : t === 'icu' ? tr('ICU', 'আইসিইউ') : tr('Freezer van', 'ফ্রিজার ভ্যান'));
}

export const bloodBankKind = (k: string) =>
  k === 'govt' ? tr('Government hospital', 'সরকারি হাসপাতাল') : k === 'private' ? tr('Private', 'বেসরকারি') : tr('Volunteer organisation', 'স্বেচ্ছাসেবী সংগঠন');
export function bloodBankService(lists: Lists | null, s: string): string {
  return (
    catLabel(lists, 'bloodBankService', s) ??
    (s === 'whole_blood' ? tr('Whole blood', 'পূর্ণ রক্ত')
      : s === 'components' ? tr('Plasma & components', 'প্লাজমা ও অন্যান্য অংশ')
        : s === 'platelets' ? tr('Platelets', 'প্লাটিলেট')
          : s === 'screening' ? tr('Screening & cross-match', 'স্ক্রিনিং ও ক্রস-ম্যাচ')
            : tr('You can donate here', 'এখানে রক্ত দেওয়া যায়'))
  );
}

// ---------- সময় ----------
const ORDER = [6, 0, 1, 2, 3, 4, 5];
const short = (d: number) =>
  [tr('Sun', 'রবি'), tr('Mon', 'সোম'), tr('Tue', 'মঙ্গল'), tr('Wed', 'বুধ'), tr('Thu', 'বৃহস্পতি'), tr('Fri', 'শুক্র'), tr('Sat', 'শনি')][d] ?? '';
/** "17:00" → "বিকাল ৫:০০" */
export function hhmm(s: string): string {
  const [h, m] = s.split(':').map((x) => parseInt(x, 10));
  return Number.isFinite(h) ? formatTimeHM(h, Number.isFinite(m) ? m : 0) : s;
}
export const hoursText = (x: { open24: boolean; openAt: string; closeAt: string }) =>
  x.open24 ? tr('Open 24 hours', '২৪ ঘণ্টা খোলা') : x.openAt ? `${hhmm(x.openAt)} – ${hhmm(x.closeAt)}` : '';
export const closedDaysText = (days: number[]) =>
  days?.length ? tr('Closed every ', 'প্রতি ') + ORDER.filter((d) => days.includes(d)).map(short).join(', ') + tr('', ' বন্ধ') : '';
const closedUntilText = (ms: number) => tr('Closed until ', 'বন্ধ থাকবে ') + formatDate(ms) + tr('', ' পর্যন্ত');

/** "★ ৪.৫ (১২)  ·  ৩ কিমি" */
export function metaLine(rating?: number, count?: number, meta?: string | null): string | null {
  const parts = [count ? `★ ${num((rating ?? 0).toFixed(1))} (${num(count)})` : null, meta || null].filter(Boolean);
  return parts.length ? parts.join('  ·  ') : null;
}
const join = (xs: (string | null | undefined)[], sep: string) => xs.filter((x) => x && x.trim()).join(sep);

// ---------- কার্ড আর বিস্তারিতের এক রূপ ----------
export type Line = [icon: string, text: string];
/** ছোট সারণির সারি: নাম, ডানে মান (দাম), নিচে ছোট লেখা (রিপোর্ট কতক্ষণে) */
export interface PriceRow { name: string; value: string; sub?: string }
export interface Section { title: string; lines: Line[]; link?: { to: string; label: string; icon?: string }; rows?: PriceRow[]; note?: string }
export interface Entry {
  id: string; kind: Kind; title: string; icon: string; tint?: 'error'; subtitle?: string | null;
  status?: { text: string; good: boolean } | null; meta?: string | null; details: Line[]; note?: string | null;
  sections: Section[]; phone: string; canRate?: boolean; reportable: boolean;
  /** শুধু ফার্মেসি: রেটিংয়ের কোন দিকগুলো দেওয়া যায় */
  rateAspects?: RateAspect[];
}

/** অ্যাম্বুলেন্সের দূরত্ব: "২.৩ কিমি" (১০-এর বেশি হলে পূর্ণ সংখ্যা); জায়গা দেওয়া না থাকলে "প্রায় ২ কিমি" */
export function ambulanceKm(a: Pick<Ambulance, 'km' | 'kmApprox'>): string | null {
  if (a.km == null || !Number.isFinite(a.km)) return null;
  if (a.kmApprox) return tr(`about ${Math.max(1, Math.round(a.km))} km`, `প্রায় ${num(Math.max(1, Math.round(a.km)))} কিমি`);
  const v = a.km < 10 ? a.km.toFixed(1) : String(Math.round(a.km));
  return num(v) + tr(' km', ' কিমি');
}

export function ambulanceEntry(a: Ambulance, lists: Lists | null): Entry {
  const loc = join([areaLabel(a.district, a.upazilaId), a.area], ' · ');
  const fare = a.fare ? tr('Fare: ', 'ভাড়া: ') + a.fare : '';
  const now =
    (a.busySince ?? 0) > 0 ? tr('On a trip since ', 'ট্রিপে গেছে ') + timeAgo(a.busySince!)
      : a.available && a.availableAt > 0 ? tr('Marked available ', 'খালি জানিয়েছেন ') + timeAgo(a.availableAt) : null;
  return {
    id: a.id, kind: 'ambulance', title: a.name, icon: 'ambulance',
    subtitle: a.types.map((t) => ambulanceTypeLabel(lists, t)).join(' · '),
    status: a.available ? { text: tr('Available', 'খালি আছে'), good: true } : a.availableAt > 0 ? { text: tr('Call to check', 'ফোনে জেনে নিন'), good: false } : { text: tr('Busy', 'ব্যস্ত'), good: false },
    meta: metaLine(a.rating, a.ratingCount, ambulanceKm(a)),
    details: [['location_on', loc], ['payments', fare]],
    note: now,
    sections: [
      { title: tr('Vehicle', 'গাড়ি'), lines: a.types.map((t) => ['ambulance', ambulanceTypeLabel(lists, t)] as Line) },
      { title: tr('Area and fare', 'এলাকা ও ভাড়া'), lines: [['location_on', join([a.area, areaLabel(a.district, a.upazilaId)], ', ')], ['payments', fare]] },
      {
        title: tr('Right now', 'এখনকার অবস্থা'),
        lines: [['schedule', now ?? (a.available ? tr('Available', 'খালি আছে') : tr('Call to check if it is free', 'খালি আছে কিনা ফোনে জেনে নিন'))]],
      },
    ],
    phone: a.phone, canRate: !!a.canRate, reportable: true,
  };
}

export function pharmacyEntry(p: Pharmacy): Entry {
  const hours = p.closedUntil > 0 ? closedUntilText(p.closedUntil) : join([hoursText(p), closedDaysText(p.closedDays)], ' · ');
  const discount = p.discount > 0 ? tr(`${p.discount}% off MRP`, `MRP থেকে ${num(p.discount)}% ছাড়`) : '';
  const delivery = p.delivery ? tr('Home delivery', 'বাসায় ডেলিভারি') + (p.deliveryNote ? ' · ' + p.deliveryNote : '') : '';
  return {
    id: p.id, kind: 'pharmacy', title: p.name, icon: 'storefront',
    status: p.openNow ? { text: tr('Open', 'খোলা'), good: true } : { text: tr('Closed', 'বন্ধ'), good: false },
    meta: metaLine(p.rating, p.ratingCount, p.km != null ? formatKm(p.km) : null),
    details: [['location_on', p.address], ['schedule', hours], ['payments', discount], ['local_shipping', delivery]],
    sections: [
      { title: tr('Address', 'ঠিকানা'), lines: [['location_on', join([p.address, areaLabel(p.district, p.upazilaId)], ', ')]] },
      {
        title: tr('Open hours', 'খোলার সময়'),
        lines: [['schedule', p.closedUntil > 0 ? closedUntilText(p.closedUntil) : hoursText(p)], ['calendar_month', closedDaysText(p.closedDays)]],
      },
      {
        title: tr('Discount and delivery', 'ছাড় ও ডেলিভারি'),
        lines: [['payments', discount || tr('No discount', 'ছাড় নেই')], ['local_shipping', delivery || tr('No home delivery', 'বাসায় ডেলিভারি নেই')]],
      },
      // দোকানের সেবার দিকভিত্তিক রেটিং (কোনো রেটিং না থাকলে ভাগটা দেখায় না)
      { title: tr('Service rating', 'সেবার রেটিং'), lines: [['star', aspectsText(p.aspects)]] },
    ],
    phone: p.phone, canRate: !!p.canRate, reportable: true, rateAspects: p.rateAspects ?? [],
  };
}

export function bloodBankEntry(b: BloodBank, lists: Lists | null): Entry {
  const addr = join([b.address, areaLabel(b.district, b.upazilaId)], ' · ');
  return {
    id: b.id, kind: 'bloodbank', title: b.name, icon: 'bloodtype', tint: 'error', subtitle: bloodBankKind(b.kind),
    status: b.openNow ? { text: tr('Open', 'খোলা'), good: true } : { text: tr('Closed', 'বন্ধ'), good: false },
    details: [
      ['bloodtype', b.services.map((s) => bloodBankService(lists, s)).join(' · ')],
      ['location_on', addr],
      ['schedule', join([hoursText(b), closedDaysText(b.closedDays)], ' · ')],
    ],
    sections: [
      { title: tr('Available here', 'যা পাওয়া যায়'), lines: b.services.map((s) => ['bloodtype', bloodBankService(lists, s)] as Line) },
      { title: tr('Address', 'ঠিকানা'), lines: [['location_on', join([b.address, areaLabel(b.district, b.upazilaId)], ', ')]] },
      { title: tr('Open hours', 'খোলার সময়'), lines: [['schedule', hoursText(b)], ['calendar_month', closedDaysText(b.closedDays)]] },
    ],
    phone: b.phone, reportable: true,
  };
}

/**
 * প্যাথলজি: কার্ডে সারাংশ ("১২টা পরীক্ষার দাম · ৳১৫০ থেকে"); বিস্তারিতে পুরো তালিকা — full: lab_prices_public-এর উত্তর
 * (undefined = এখনো আসছে, null = তালিকা নেই)।
 */
export function labEntry(l: Lab, full?: PublicPrices | null): Entry {
  const p = l.prices ?? null;
  const priceLines: Line[] = p
    ? [['payments', discountText(p.discountPct)], ['home_health', l.homeCollection ? homeFeeText(p.homeFee) : ''], ['sync', full === undefined ? tr('Loading the price list…', 'দামের তালিকা আসছে…') : '']]
    : [['call', NO_PRICE()]];
  return {
    id: l.id, kind: 'lab', title: l.name, icon: 'local_hospital',
    subtitle: l.homeCollection ? tr('Sample from home', 'বাসা থেকে নমুনা নেয়') : null,
    details: [
      ['location_on', l.address], ['schedule', l.hours],
      ['payments', p ? tr(`${p.count} tests · from ${taka(p.from)}`, `${num(p.count)}টা পরীক্ষার দাম · ${taka(p.from)} থেকে`) + (p.discountPct ? ` · ${discountText(p.discountPct)}` : '') : ''],
    ],
    sections: [
      { title: tr('Address', 'ঠিকানা'), lines: [['location_on', join([l.address, areaLabel(l.district, l.upazilaId)], ', ')]] },
      { title: tr('Hours', 'সময়'), lines: [['schedule', l.hours], ['home_health', l.homeCollection ? tr('Collects samples from home', 'বাসা থেকে নমুনা নেয়') : '']] },
      {
        title: tr('Tests & prices', 'পরীক্ষা ও দাম'),
        lines: priceLines,
        rows: p && full ? full.items.map((i) => ({ name: i.name, value: taka(i.price), sub: i.tat ? tr('Report: ', 'রিপোর্ট: ') + i.tat : '' })) : undefined,
      },
      {
        title: tr('Tests from a prescription', 'প্রেসক্রিপশনের পরীক্ষা'),
        lines: [['medication', tr('Open your prescription and tap "Send tests to a lab".', 'আপনার প্রেসক্রিপশন খুলে "পরীক্ষা করাতে পাঠান" চাপুন।')]],
        link: { to: '/prescriptions', label: tr('My prescriptions', 'আমার প্রেসক্রিপশন'), icon: 'list_alt' },
      },
    ],
    phone: l.phone, reportable: false,
  };
}

// ---------- কাজ ----------
const CALLED: Partial<Record<Kind, string>> = { ambulance: 'ambulance_called', pharmacy: 'pharmacy_called', bloodbank: 'bloodbank_called' };
/** অ্যাপ থেকে ফোন করলে লিখে রাখা (মালিকের হিসাব; রেটিং/রিপোর্ট এর পরেই দেওয়া যায়) — অতিথি হলে বাদ */
export function recordCall(kind: Kind, id: string, guest: boolean) {
  const action = CALLED[kind];
  if (!action || guest) return;
  call(action, { id }).catch(() => {});
}
export const reportKind = (k: Kind) => (k === 'bloodbank' ? 'bloodbank' : k);

// ---------- এই সেশনে দেখা জিনিস (এলাকা বদলালেও বাছা জিনিসের বিস্তারিত থাকে) ----------
const seen = new Map<string, unknown>();
export function remember(kind: Kind, items: { id: string }[] | undefined) {
  items?.forEach((x) => seen.set(`${kind}/${x.id}`, x));
}
export const recall = <T,>(kind: Kind, id: string) => seen.get(`${kind}/${id}`) as T | undefined;
