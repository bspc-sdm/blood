// প্রেসক্রিপশনের ভেতরের কাজের ধরন আর লেখা (React-এর RxActivity.tsx থেকে হুবহু) — অ্যাপের RxNet.kt / RxNetText
import { ref, watch, type Ref } from 'vue';
import { num, tr } from '@/lib/i18n';
import { taka, type LabHome, type PriceSnap, type Slot } from '@/lib/labprice';
import { useSession } from '@/lib/session';
import type { AreaValue } from '@/ui/areaFilter';


export interface MedStat { i: number; name: string; form: string; dose: string; days: number; need: number | null; got: number; left: number | null; done: boolean }
export interface Dispensing { name: string; at: number; items: { i: number; qty: number }[] }
export interface RxOrder { id: string; pharmacyName: string; status: string; reason: string; createdAt: number; mode: string; price: string; eta: string; deliveryFee: string; missed: string[]; invoiceId?: string | null }
export interface LabResult { test: string; result: string; unit: string; range: string; flag: string; group?: string; code?: string; key?: string; long?: boolean }
export interface LabOrder {
  id: string; rxId: string; code: string; labName: string; patientName: string; patientAge: number | null; patientGender: string; doctorName: string;
  tests: string[]; status: string; createdAt: number; report: { items: LabResult[]; note: string; reportedAt: number; photoCount: number; photos?: string[] } | null;
  prices?: PriceSnap | null; home?: LabHome | null;
  invoiceId?: string | null;
}
export interface RxReview { id: string; labOrderId: string; doctorName: string; status: string; fee: string; noFee: boolean; payNumber: string; payMethods: string[]; rejectNote: string; comment: string }
export interface Activity { medicines: MedStat[]; dispensed: Dispensing[]; orders: RxOrder[]; labOrders: LabOrder[]; reviews: RxReview[]; nextOpen: number | null }
export interface PharmOption {
  id: string; name: string; phone: string; address: string; km: number | null; openNow: boolean; keepsStock: boolean; have: number; total: number; missing: string[];
  online: boolean; deliveryFee: string; onlineNote: string;
  /** আনুমানিক খরচ (দোকানের হিসাব-খাতার দাম): total পুরো টাকা, priced কয়টার দাম জানা, of কেনা বাকি কয়টা — একটারও দাম না জানলে null */
  estimate?: { total: number; priced: number; of: number } | null;
}
/** "আনুমানিক ৳৪৮০ (৫টার মধ্যে ৪টার দাম জানা)" / "আনুমানিক ৳৪৮০" / "দাম দোকানে জেনে নিন" */
export function pharmEstimateText(e: PharmOption['estimate']): string {
  if (!e) return tr('Ask the shop for the price', 'দাম দোকানে জেনে নিন');
  if (e.priced < e.of) return tr(`About ${taka(e.total)} (price known for ${e.priced} of ${e.of})`, `আনুমানিক ${taka(e.total)} (${num(e.of)}টার মধ্যে ${num(e.priced)}টার দাম জানা)`);
  return tr(`About ${taka(e.total)}`, `আনুমানিক ${taka(e.total)}`);
}
/** দোকানের পাতা থেকে: "এখানে আছে ৩/৫টা ওষুধ; নেই: X, Y" — স্টকের তালিকা না রাখলে "স্টকের তালিকা রাখে না — দোকানে জেনে নিন" */
export function pharmHereText(p: PharmOption): string {
  if (!p.keepsStock) return tr("Doesn't keep a stock list — ask at the shop", 'স্টকের তালিকা রাখে না — দোকানে জেনে নিন');
  return tr(`Has ${p.have}/${p.total} medicines`, `এখানে আছে ${num(p.have)}/${num(p.total)}টা ওষুধ`) + (p.missing.length ? tr('; missing: ', '; নেই: ') + p.missing.join(', ') : '');
}
/** rx_lab_options — এই প্রেসক্রিপশনের পরীক্ষা কোথায় কয়টা হয়, আনুমানিক কত */
export interface LabOption {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null; homeCollection: boolean; hours: string;
  suggested: boolean; priced: boolean; have: number | null; total: number; missing: string[]; items: { test: string; name: string; price: number; tat: string }[];
  subtotal: number | null; discountPct: number | null; estimate: number | null; homeFee: number | null; tat: string;
}
/** বাসা থেকে নমুনার অনুরোধ (rx_send_lab / lab_home_change-এর home): day ০ আজ, ১ কাল, ২ পরশু */
export interface HomeReq { address: string; phone: string; day: number; slot: Slot; note: string }

/** মান বদলানোর পর ms পর্যন্ত থেমে থাকলে তবেই নতুন মান (পরীক্ষার চিপ টিপতে টিপতে প্রতিবার সার্ভার ডাকা নয়) */
export function useDebounced<T>(v: () => T, ms: number): Ref<T> {
  const d = ref(v()) as Ref<T>;
  watch(v, (nv, _o, onCleanup) => {
    const t = setTimeout(() => (d.value = nv), ms);
    onCleanup(() => clearTimeout(t));
  });
  return d;
}

// ---------- লেখা (অ্যাপের RxNetText) ----------
export const payLabel = (m: string) => ({ bkash: 'bKash', nagad: 'Nagad', rocket: 'Rocket' })[m] ?? m;
export function orderStatus(s: string) {
  switch (s) {
    case 'sent': return tr('Sent — waiting', 'পাঠানো হয়েছে — অপেক্ষায়');
    case 'ready': return tr('Ready to collect', 'তৈরি — নিয়ে আসুন');
    case 'accepted': return tr('Order accepted', 'অর্ডার নিয়েছে');
    case 'out': return tr('On the way', 'পথে আছে');
    case 'done': return tr('Given', 'দেওয়া হয়েছে');
    case 'delivered': return tr('Delivered', 'পৌঁছে দেওয়া হয়েছে'); // bn-ok
    case 'cancelled': return tr("Can't provide", 'দিতে পারবে না');
    case 'withdrawn': return tr('Taken back', 'ফিরিয়ে নিয়েছেন');
    default: return s;
  }
}
export function labStatus(s: string) {
  switch (s) {
    case 'sent': return tr('Sent — waiting', 'পাঠানো হয়েছে — অপেক্ষায়');
    case 'received': return tr('Sample taken', 'নমুনা নেওয়া হয়েছে');
    case 'reported': return tr('Report ready', 'রিপোর্ট এসেছে');
    case 'cancelled': return tr("Can't do", 'করতে পারবে না');
    default: return s;
  }
}
export function reviewStatus(s: string) {
  switch (s) {
    case 'unpaid': return tr('Not paid', 'টাকা দেওয়া হয়নি');
    case 'claimed': return tr('Payment sent — doctor is checking', 'টাকা পাঠিয়েছেন — ডাক্তার মিলিয়ে দেখছেন');
    case 'paid': return tr('Doctor is checking the report', 'ডাক্তার রিপোর্ট দেখছেন');
    case 'done': return tr('Doctor checked', 'ডাক্তার দেখেছেন');
    default: return '';
  }
}
export const flagLabel = (f: string) => (f === 'high' ? tr('High', 'বেশি') : f === 'low' ? tr('Low', 'কম') : f === 'abnormal' ? tr('Abnormal', 'অস্বাভাবিক') : '');
export function boughtText(m: MedStat) {
  if (m.need != null) return tr(`Bought ${m.got}/${m.need}`, `কেনা ${num(m.got)}/${num(m.need)}`);
  if (m.got > 0) return tr(`Bought ${m.got}`, `কেনা ${num(m.got)}`) + (m.days === 0 ? tr(' (continue)', ' (চলবে)') : '');
  return tr('Not bought yet', 'এখনো কেনা হয়নি');
}
export const OPEN = ['sent', 'ready', 'accepted', 'out'];
export const callTel = (phone: string) => {
  if (phone) window.location.href = `tel:${phone}`;
};

/** এলাকা: প্রোফাইলের জেলা দিয়ে শুরু */
export function startArea(): AreaValue | null {
  const s = useSession();
  const d = s.profile?.placeName || s.place?.placeName || '';
  return d ? { district: d, upazilaId: null } : null;
}

/** পাঠানোর পর যা জানানো হয়: কোন প্রেসক্রিপশন (ডাক্তার, তারিখ), কোথায়, কীভাবে */
export type SentMode = 'shop' | 'online' | 'lab' | 'lab_home';
export interface RxSent { rxId: string; doctorName: string; date: number; place: string; mode: SentMode }

export function sentModeText(m: SentMode) {
  switch (m) {
    case 'online': return tr('Home delivery', 'বাসায় ডেলিভারি');
    case 'lab_home': return tr('Sample from home', 'বাসা থেকে নমুনা');
    case 'lab': return tr("You'll give the sample at the lab", 'প্যাথলজিতে গিয়ে নমুনা দেবেন');
    default: return tr("You'll collect at the shop", 'দোকানে গিয়ে নেবেন');
  }
}
/**
 * "এরপর কী" — সার্ভারের আসল নোটিফিকেশনের সাথে মিলিয়ে (server/lib/rxnet.js): দোকান — "আপনার ওষুধ তৈরি" (ready);
 * অনলাইন — "অর্ডার নিয়েছে" (দাম · সময়); প্যাথলজি — নমুনা নেওয়ায় নোটিফিকেশন যায় না, বাসা থেকে হলে "… আসবে",
 * আর রিপোর্ট এলে "আপনার পরীক্ষার রিপোর্ট এসেছে"।
 */
export function sentNextText(m: SentMode) {
  switch (m) {
    case 'online': return tr("You'll get a notification when the pharmacy tells you the price and time", 'ফার্মেসি দাম আর সময় জানালে নোটিফিকেশন পাবেন');
    case 'lab_home': return tr("You'll get a notification when the lab sets the time to come, and when the report is ready", 'প্যাথলজি বাসায় আসার সময় জানালে নোটিফিকেশন পাবেন; রিপোর্ট এলেও জানাবে');
    case 'lab': return tr("Go to the lab to give the sample; you'll get a notification when the report is ready", 'প্যাথলজিতে গিয়ে নমুনা দিন; রিপোর্ট এলে নোটিফিকেশন পাবেন');
    default: return tr("You'll get a notification when the pharmacy has your medicines ready", 'ফার্মেসি ওষুধ তৈরি করলে নোটিফিকেশন পাবেন');
  }
}
export const sentModeIcon = (m: SentMode) => (m === 'online' ? 'local_shipping' : m === 'lab_home' ? 'home_health' : m === 'lab' ? 'local_hospital' : 'storefront');

