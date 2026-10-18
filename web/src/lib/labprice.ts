// প্যাথলজির পরীক্ষা ও দাম, আর বাসা থেকে নমুনা — সার্ভারের আকার (server/lib/labprices.js) আর লেখা
// (রোগীর দিক health/RxActivity, সেবার তালিকা services, প্যাথলজির ডেস্ক rx/partner — সবাই এটাই ব্যবহার করে; অ্যাপের LabPrice.kt-এর মতো)
import { num, tr } from './i18n';
import { formatDate, formatTimeHM, todayUtcMidnight } from './format';

/** এলাকার তালিকার সারাংশ (labs-এর prices): কয়টা পরীক্ষার দাম, সবচেয়ে কম দাম, ছাড়, বাসার চার্জ */
export interface PriceSummary { count: number; from: number; discountPct: number | null; homeFee: number | null; updatedAt: number }
/** একটা প্যাথলজির পুরো তালিকা (lab_prices_public, বিস্তারিত খুললে) — শুধু চালু পরীক্ষা */
export interface PublicPrices {
  items: { code: string | null; name: string; price: number; tat: string }[];
  discountPct: number | null;
  homeFee: number | null;
  updatedAt: number;
}
/** অর্ডারে রাখা পাঠানোর সময়ের দাম */
export interface PriceSnap {
  items: { test: string; name: string; price: number; tat: string }[];
  missing: string[];
  subtotal: number;
  discountPct: number | null;
  estimate: number;
}
export type Slot = 'early' | 'morning' | 'afternoon';
/**
 * অর্ডারের "বাসা থেকে" অংশ — ঠিকানা-ফোন শুধু রোগী আর (অর্ডার চালু থাকলে) সেই প্যাথলজি পান।
 * expired: চাওয়া দিন-সময় পেরিয়ে গেছে, প্যাথলজি এখনো সময় জানায়নি (সার্ভার দেখানোর সময় হিসাব করে)।
 */
export interface LabHome {
  status: 'requested' | 'confirmed' | 'declined' | 'expired';
  date: number; slot: Slot | ''; note: string; fee: number | null;
  visitDate: number; time: string; at: number; confirmNote: string; declineNote: string;
  address?: string; phone?: string;
}

const DAY = 24 * 60 * 60 * 1000;
/** সময়ের ভাগ: [শুরু, শেষ] ঘণ্টা (ঢাকা) — সার্ভারের SLOTS */
export const SLOTS: Record<Slot, [number, number]> = { early: [7, 9], morning: [9, 12], afternoon: [15, 18] };
export const SLOT_KEYS: Slot[] = ['early', 'morning', 'afternoon'];

/** ৳১,২৫০ (বাংলায় বাংলা অঙ্ক) */
export function taka(v: number | null | undefined): string {
  if (v == null) return '—';
  return '৳' + num(Math.round(v).toLocaleString('en-IN'));
}

export const slotText = (s: string) =>
  s === 'early' ? tr('7–9 AM', 'সকাল ৭–৯') : s === 'morning' ? tr('9 AM–12 PM', 'সকাল ৯–১২') : s === 'afternoon' ? tr('3–6 PM', 'বিকেল ৩–৬') : '';

/** আজ / কাল / পরশু / তারিখ (date: UTC মধ্যরাত) */
export function dayWord(date: number): string {
  const k = Math.round((date - todayUtcMidnight()) / DAY);
  if (k === 0) return tr('Today', 'আজ');
  if (k === 1) return tr('Tomorrow', 'কাল');
  if (k === 2) return tr('Day after', 'পরশু');
  return formatDate(date);
}
/** "08:30" → "সকাল ৮:৩০" */
export function hhmmText(t: string): string {
  const [h, m] = t.split(':').map((x) => parseInt(x, 10));
  return Number.isFinite(h) ? formatTimeHM(h, Number.isFinite(m) ? m : 0) : t;
}
/** চাওয়া সময়: "কাল · সকাল ৯–১২" */
export const wantedText = (h: LabHome) => [dayWord(h.date), slotText(h.slot)].filter(Boolean).join(' · ');
/** নিশ্চিত সময়: "কাল সকাল ৮:০০" */
export const visitText = (h: LabHome) => (h.time ? `${dayWord(h.visitDate || h.date)} ${hhmmText(h.time)}` : '');

/** রোগীর দিক থেকে বাসা থেকে নমুনার অবস্থা */
export function homeStatusPatient(h: LabHome): string {
  if (h.status === 'confirmed') return tr(`Coming ${visitText(h)}`, `${visitText(h)} আসবে`);
  if (h.status === 'declined') return tr("Can't come home — give the sample at the lab", 'বাসায় আসবে না — প্যাথলজিতে গিয়ে নমুনা দিন');
  if (h.status === 'expired') return tr(`Sample from home · ${wantedText(h)} — time has passed`, `বাসা থেকে নমুনা · ${wantedText(h)} — সময় পেরিয়ে গেছে`);
  return tr(`Sample from home · ${wantedText(h)} — waiting for the time`, `বাসা থেকে নমুনা · ${wantedText(h)} — সময় জানাবে`);
}

/** "৩টার ৩টা" */
export const coverText = (have: number, total: number) => tr(`${have} of ${total} tests`, `${num(total)}টার ${num(have)}টা পরীক্ষা`);
/** "আনুমানিক ৳১,২৫০" */
export const estimateText = (v: number) => tr(`About ${taka(v)}`, `আনুমানিক ${taka(v)}`);
/** প্যাথলজির পাতা থেকে পাঠানোর আগে: "এখানে হয় ৩/৪টা পরীক্ষা; হয় না: X" */
export const hereText = (have: number, total: number, missing: string[]) =>
  tr(`${have}/${total} tests done here`, `এখানে হয় ${num(have)}/${num(total)}টা পরীক্ষা`) + (missing.length ? tr('; not done: ', '; হয় না: ') + missing.join(', ') : '');
/** "দাম ৳১,৪০০ − ১০% ছাড় = আনুমানিক ৳১,২৬০" (ছাড় না থাকলে শুধু "আনুমানিক ৳১,৪০০"); একটাও না হলে খালি */
export function breakdownText(subtotal: number | null, discountPct: number | null, est: number | null): string {
  if (est == null) return '';
  if (subtotal != null && discountPct) return tr(`Price ${taka(subtotal)} − ${discountPct}% off = about ${taka(est)}`, `দাম ${taka(subtotal)} − ${num(discountPct)}% ছাড় = আনুমানিক ${taka(est)}`);
  return estimateText(est);
}
export const discountText = (d: number | null | undefined) => (d ? tr(`${num(d)}% off all tests`, `সব পরীক্ষায় ${num(d)}% ছাড়`) : '');
export const homeFeeText = (fee: number | null | undefined) =>
  fee == null ? '' : fee === 0 ? tr('Sample from home: free', 'বাসা থেকে নমুনা: ফ্রি') : tr(`Sample from home: ${taka(fee)}`, `বাসা থেকে নমুনা: ${taka(fee)}`);
export const NO_PRICE = () => tr('Price not known — ask on the phone', 'দাম জানা নেই — ফোনে জেনে নিন');
/** "ECG দাম জানা নেই" — দামের তালিকায় না থাকা পরীক্ষা (হিসাবে ধরা হয়নি) */
export const missingText = (m: string[]) => (m.length ? tr(`No price for ${m.join(', ')}`, `${m.join(', ')} দাম জানা নেই`) : '');
/** প্যাথলজি বাছাইয়ের কার্ডে: "৩টার ২টা পরীক্ষা · আনুমানিক ৳১,২৫০" — একটাও না হলে দাম নয় (৳০ নয়) */
export const coverEstimateText = (have: number, total: number, estimate: number | null) =>
  [coverText(have, total), estimate != null ? estimateText(estimate) : ''].filter(Boolean).join(' · ');
/** অর্ডারে রাখা দামের লাইন (রোগীর দিক): "আনুমানিক ৳২,৩৪০ (সব পরীক্ষায় ১০% ছাড়) + বাসা থেকে নমুনা: ৳১৫০ · ECG দাম জানা নেই" */
export function snapText(p: PriceSnap, home?: LabHome | null): string {
  return estimateText(p.estimate) + (p.discountPct ? ` (${discountText(p.discountPct)})` : '')
    + (home?.fee != null && home.status !== 'declined' ? ` + ${homeFeeText(home.fee)}` : '')
    + (p.missing.length ? ` · ${missingText(p.missing)}` : '');
}
/** বাসা থেকে নমুনার অনুরোধ রোগী বদলাতে/বাতিল করতে পারেন — প্যাথলজি সময় জানানোর আগে (সময় পেরিয়ে গেলেও) */
export const homeEditable = (h: LabHome | null | undefined, orderStatus: string) => !!h && orderStatus === 'sent' && (h.status === 'requested' || h.status === 'expired');

/** আজকের যে সময় পেরিয়ে গেছে (ঢাকার ঘড়িতে) */
export function slotPassed(day: number, s: Slot): boolean {
  if (day !== 0) return false;
  const d = new Date(Date.now() + 6 * 3600 * 1000);
  return d.getUTCHours() * 60 + d.getUTCMinutes() >= SLOTS[s][1] * 60;
}
/** নিশ্চিত করার সময়ের বাছাই: ভাগের ভেতরে আধা ঘণ্টা পরপর ("07:00", "07:30", …) */
export function slotTimes(s: Slot): string[] {
  const [a, b] = SLOTS[s];
  const out: string[] = [];
  for (let m = a * 60; m < b * 60; m += 30) out.push(`${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`);
  return out;
}
