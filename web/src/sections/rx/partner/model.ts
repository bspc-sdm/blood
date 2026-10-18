// ফার্মেসি আর প্যাথলজির কাজ (/work/pharmacy/orders, /work/lab/orders) — সার্ভারের আকার (server/lib/rxnet.js)
// আর অ্যাপের লেখা (RxNet.kt-এর RxNetText, Prescriptions.kt-এর formShort)।
import { num, tr } from '@/lib/i18n';
import type { LabHome, PriceSnap } from '@/lib/labprice';

// ---------- ফার্মেসি ----------
export interface PharmMed {
  i: number; name: string; form: string; strength: string; dose: string; days: number;
  need: number | null; got: number; left: number | null; done: boolean; timing: string; note: string;
}
export interface Dispensing { name: string; at: number; items: { i: number; qty: number }[] }
/** rx_pharmacy_open / rx_dispense — ফার্মেসির দেখা প্রেসক্রিপশন */
export interface PharmRx {
  id: string; code: string; status: string; createdAt: number;
  doctor: { name: string; degrees: string; regNo: string };
  patient: { name: string; age: number | null };
  medicines: PharmMed[];
  dispensed: Dispensing[];
}
/** rx_orders_pharmacy */
export interface PharmOrder {
  id: string; rxId: string; code: string; patientName: string; note: string; status: string; createdAt: number;
  mode: 'shop' | 'online'; price: string; eta: string; deliveryFee: string; missed: string[]; address?: string; phone?: string;
}
export interface OnlineSettings { enabled: boolean; radiusKm: number; fee: string; note: string }
export interface Missed { k: string; name: string; count: number; lastAt: number }
/** rx_pharmacy_home */
export interface PharmacyHome { shopCode: string; online: OnlineSettings; hasLocation: boolean; missed: Missed[] }
export interface Drug { i: number; name: string; generic: string; form: string; company: string }

/** চালু অর্ডার (সার্ভারের OPEN_ORDER) */
export const OPEN_ORDER = ['sent', 'ready', 'accepted', 'out'];
export const isOpenOrder = (o: PharmOrder) => OPEN_ORDER.includes(o.status);

/** অ্যাপের RxNetText.orderStatus (রোগীর দিক থেকে লেখা) — দোকানের দিক থেকে প্যানেলের OS */
export const orderStatusShop = (s: string) =>
  s === 'sent' ? tr('New', 'নতুন')
    : s === 'ready' ? tr('Ready', 'তৈরি')
      : s === 'accepted' ? tr('Accepted', 'নেওয়া হয়েছে')
        : s === 'out' ? tr('On the way', 'পথে')
          : s === 'done' ? tr('Given', 'দেওয়া হয়েছে')
            : s === 'cancelled' ? tr("Couldn't", 'পারেননি')
              : s === 'withdrawn' ? tr('Taken back', 'ফিরিয়ে নিয়েছেন') : s;
export const orderTone = (s: string): 'primary' | 'good' | 'error' | 'info' | undefined =>
  s === 'sent' ? 'primary' : s === 'done' ? 'good' : s === 'cancelled' ? 'error' : s === 'accepted' || s === 'out' || s === 'ready' ? 'info' : undefined;

/** অ্যাপের RxText.formShort */
export function formShort(f: string): string {
  const l = (f || '').toLowerCase();
  if (!l.trim()) return '';
  if (l.includes('tablet')) return 'Tab.';
  if (l.includes('capsule')) return 'Cap.';
  if (l.includes('syrup') || l.includes('suspension') || l.includes('oral solution')) return 'Syr.';
  if (l.includes('injection') || l.includes('infusion')) return 'Inj.';
  if (l.includes('drop')) return 'Drop';
  if (l.includes('cream')) return 'Cream';
  if (l.includes('ointment')) return 'Oint.';
  if (l.includes('inhal')) return 'Inh.';
  return f;
}
export const medTitle = (m: { form: string; name: string }) => [formShort(m.form), m.name].filter(Boolean).join(' ');

/** অ্যাপের MedStat.boughtText */
export function boughtText(m: PharmMed): string {
  if (m.need != null) return tr(`Bought ${m.got}/${m.need}`, `কেনা ${num(m.got)}/${num(m.need)}`);
  if (m.got > 0) return tr(`Bought ${m.got}`, `কেনা ${num(m.got)}`) + (m.days === 0 ? tr(' (continue)', ' (চলবে)') : '');
  return tr('Not bought yet', 'এখনো কেনা হয়নি');
}
export const doseText = (m: { dose: string; days: number }) =>
  [m.dose ? num(m.dose) : '', m.days > 0 ? tr(`${m.days} days`, `${num(m.days)} দিন`) : tr('Continue', 'চলবে')].filter(Boolean).join(' · ');
export const ageText = (age: number | null | undefined) => (age != null ? tr(`${age} yrs`, `${num(age)} বছর`) : null);
export const genderText = (g: string) => (g === 'female' ? tr('Female', 'নারী') : g === 'male' ? tr('Male', 'পুরুষ') : null);

// ---------- প্রেসক্রিপশনের কোড ----------
const CODE_RE = /^[A-HJ-NP-Z2-9]{10}$/;
/** লেখা/পেস্ট করা কোড: বড় হাতের, ফাঁকা-ড্যাশ বাদ; লিংক বা লম্বা লেখা পেস্ট করলে ভেতরের ১০ অক্ষরের কোড */
export function cleanCode(raw: string): string {
  const s = raw.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (s.length <= 10) return s;
  const m = raw.toUpperCase().match(/[A-HJ-NP-Z2-9]{10}(?![A-Z0-9])/g);
  return m ? m[m.length - 1] : s.slice(0, 10);
}
export const validCode = (c: string) => CODE_RE.test(c);

// ---------- প্যাথলজি ----------
export interface LabResult {
  test: string; result: string; unit: string; range: string; flag: string;
  group?: string; code?: string; key?: string; low?: number; high?: number; long?: boolean;
}
export interface LabOrder {
  id: string; rxId: string; code: string; labId: string; labName: string; patientName: string; patientAge: number | null; patientGender: string;
  doctorName: string; tests: string[]; status: string; createdAt: number;
  report: { items: LabResult[]; note: string; reportedAt: number; photoCount: number; photos?: string[] } | null;
  /** পাঠানোর সময়ের আনুমানিক দাম (দামের তালিকা থাকলে) */
  prices?: PriceSnap | null;
  /** বাসা থেকে নমুনা (চাইলে) */
  home?: LabHome | null;
}
/** lab_prices_get — নিজের তালিকা (বন্ধগুলোসহ) */
/** hidden: ছকে এখন লুকানো পরীক্ষা — রোগীরা দেখেন না; আবার সেভ করলে নাম দিয়ে নিজে লেখা হিসেবে থাকে */
export interface LabPriceItem { code: string | null; name: string; price: number; tat: string; on: boolean; hidden?: boolean }
/** kept: সেভের সময় লুকানো পরীক্ষা যেগুলো নিজে লেখা হিসেবে রাখা হলো (নাম) */
export interface LabPriceList { items: LabPriceItem[]; discountPct: number | null; homeFee: number | null; updatedAt: number; homeCollection: boolean; kept?: string[] }
export interface HistoryPoint { result: string; unit?: string; flag: string; at: number; lab?: string }
export interface FormParam { key: string; name: string; unit: string; low: number | null; high: number | null; range: string; text: string; options: string[]; long: boolean }
export interface LabForm { test: string; code: string | null; name: string; params: FormParam[] }
/** labtests_get — ছকের তালিকা */
export interface TestDef {
  code: string; name: string; bn?: string; aliases?: string[]; hidden?: boolean;
  params: { key: string; name: string; unit?: string; r?: [number | null, number | null]; m?: [number | null, number | null]; f?: [number | null, number | null]; text?: string; options?: string[]; long?: boolean }[];
}

/** অ্যাপের RxNetText.labStatus — ল্যাবের দিক থেকে প্যানেলের LS */
export const labStatusText = (s: string) =>
  s === 'sent' ? tr('New', 'নতুন')
    : s === 'received' ? tr('Sample taken', 'নমুনা নেওয়া হয়েছে')
      : s === 'reported' ? tr('Report given', 'রিপোর্ট দেওয়া হয়েছে')
        : s === 'cancelled' ? tr('Cancelled', 'বাতিল') : s;
export const labTone = (s: string): 'primary' | 'good' | 'error' | 'info' | undefined =>
  s === 'sent' ? 'primary' : s === 'received' ? 'info' : s === 'reported' ? 'good' : s === 'cancelled' ? 'error' : undefined;

export const flagText = (f: string) => (f === 'high' ? tr('High', 'বেশি') : f === 'low' ? tr('Low', 'কম') : f === 'abnormal' ? tr('Abnormal', 'অস্বাভাবিক') : '');
export const flagArrow = (f: string) => (f === 'high' ? '↑' : f === 'low' ? '↓' : f ? '*' : '');

/** সংখ্যার ফল মাত্রার সাথে মিলিয়ে বেশি/কম (অ্যাপের autoFlag); সংখ্যা না হলে null */
export function autoFlag(result: string, low: number | null | undefined, high: number | null | undefined): string | null {
  if (low == null && high == null) return null;
  const t = String(result || '').replace(/,/g, '').trim();
  if (!t) return null;
  const v = Number(t);
  if (!Number.isFinite(v)) return null;
  if (high != null && v > high) return 'high';
  if (low != null && v < low) return 'low';
  return '';
}

export function rangeText(low: number | null | undefined, high: number | null | undefined): string {
  if (low != null && high != null) return `${low} – ${high}`;
  if (high != null) return `< ${high}`;
  if (low != null) return `> ${low}`;
  return '';
}

/** ছকের মাত্রা লেখায় (নারী/পুরুষ আলাদা হলে দুটোই) */
export function defRangeText(p: TestDef['params'][number]): string {
  const t = (r?: [number | null, number | null]) => (r ? rangeText(r[0], r[1]) : '');
  if (p.r) return t(p.r);
  if (p.m && p.f) return `${tr('M', 'পু')}: ${t(p.m)}, ${tr('F', 'না')}: ${t(p.f)}`;
  if (p.m) return t(p.m);
  if (p.f) return t(p.f);
  return p.text || '';
}
export const isNumericParam = (p: TestDef['params'][number]) => !!(p.r || p.m || p.f) && !p.long && !(p.options && p.options.length);

/** লেখা সংখ্যা (বাংলা অঙ্কও) → number; খালি হলে null; ভুল হলে NaN */
export function parseNum(s: string): number | null {
  const t = s.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d))).replace(/,/g, '').trim();
  if (!t) return null;
  const v = Number(t);
  return Number.isFinite(v) ? v : NaN;
}
