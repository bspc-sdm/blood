// "আমার কাজ" — সব ব্যবসার সাধারণ অংশ (অ্যাপের Work.kt, Verify.kt, Photo.kt, Ambulance.kt-এর মালিকের অংশ)
// Vue: হিসাব এখানে; কম্পোনেন্ট (StatusTag, AreaField, DocPhotosField, BizPage …) আলাদা .vue ফাইলে
import { computed, onMounted, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue';
import { useQueryClient } from '@tanstack/vue-query';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { num, tr } from '@/lib/i18n';
import { areasReady, ensureAreas } from '@/lib/geo';
import { formatTimeHM } from '@/lib/format';
import { useLoc } from '@/lib/nav';

// ---------- ধরন ----------
export type Kind = 'ambulance' | 'pharmacy' | 'bloodbank' | 'lab';
export const KINDS: Kind[] = ['ambulance', 'pharmacy', 'bloodbank', 'lab'];
export const MAX_AMBULANCES = 10;
export const MAX_BLOODBANKS = 3;

export const businessName = (k: Kind) =>
  k === 'ambulance' ? tr('Ambulance', 'অ্যাম্বুলেন্স') : k === 'pharmacy' ? tr('Pharmacy', 'ফার্মেসি') : k === 'bloodbank' ? tr('Blood bank', 'ব্লাড ব্যাংক') : tr('Lab', 'প্যাথলজি');
export const businessIcon = (k: Kind) => (k === 'ambulance' ? 'ambulance' : k === 'pharmacy' ? 'storefront' : k === 'bloodbank' ? 'bloodtype' : 'local_hospital');
export const myBusinessTitle = (k: Kind) =>
  k === 'ambulance' ? tr('My ambulances', 'আমার অ্যাম্বুলেন্স') : k === 'pharmacy' ? tr('My pharmacy', 'আমার ফার্মেসি') : k === 'bloodbank' ? tr('My blood banks', 'আমার ব্লাড ব্যাংক') : tr('My lab', 'আমার প্যাথলজি');

export const badgeLabel = (t: string) =>
  t === 'doctor' ? tr('Doctor', 'ডাক্তার') : t === 'nurse' ? tr('Nurse', 'নার্স') : t === 'nutritionist' ? tr('Nutritionist', 'পুষ্টিবিদ') : t === 'trainer' ? tr('Fitness trainer', 'ফিটনেস ট্রেইনার') : tr('Health Expert', 'স্বাস্থ্য বিশেষজ্ঞ');
export const badgeIcon = (t: string) =>
  t === 'doctor' ? 'stethoscope' : t === 'nurse' ? 'medical_services' : t === 'nutritionist' ? 'restaurant' : t === 'trainer' ? 'fitness_center' : 'health_and_safety_filled';

// ---------- সার্ভারের আকার ----------
export interface DocFlags { docsMissing?: boolean; docsRequested?: boolean; docsRequestNote?: string }
export interface Ambulance extends DocFlags {
  id: string; name: string; phone: string; types: string[]; district: string; upazilaId: number | null; area: string; fare: string;
  available: boolean; availableAt: number; rating: number; ratingCount: number; vehicleNo: string; status: string; statusReason: string;
  /** খালি থাকলে কখন পর্যন্ত (১২ ঘণ্টা); গাড়ির জায়গা (GPS) দেওয়া আছে কি না */
  availableUntil?: number; hasBase?: boolean;
  /** ভাড়ার তালিকা (২.৩০.০) */
  fareChart?: import('../services/fare').FareChart | null;
}
export interface Pharmacy extends DocFlags {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; openAt: string; closeAt: string;
  open24: boolean; openNow: boolean; closedUntil: number; closedDays: number[]; delivery: boolean; deliveryNote: string; discount: number;
  rating: number; ratingCount: number; licenseNo: string; status: string; statusReason: string; hasLocation: boolean;
  /** ছাড়ের শর্ত (২.৩২.০) — ০/খালি = নেই */
  discountMin?: number; freeDeliveryOver?: number; discountNote?: string;
}
export interface BloodBank extends DocFlags {
  id: string; name: string; kind: string; phone: string; district: string; upazilaId: number | null; address: string; open24: boolean;
  openAt: string; closeAt: string; openNow: boolean; services: string[]; closedDays: number[]; licenseNo: string; status: string; statusReason: string;
}
export interface Lab extends DocFlags {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null;
  homeCollection: boolean; hours: string; licenseNo: string; status: string; statusReason: string;
}
export interface CallSummary { calls: { 7: number; 30: number; 90: number; total: number }; callers: number; daily: number[] }
export interface ListingStats extends CallSummary {
  id: string; name: string; status: string; reports: number;
  rating?: number; ratingCount?: number; comments?: { rating: number; comment: string; at: number }[];
}

// ---------- অবস্থা ----------
/** অ্যাপের statusText */
export const statusText = (s: string) =>
  s === 'approved' ? tr('Listed', 'তালিকায় আছে') : s === 'pending' ? tr('Waiting for approval', 'অনুমোদনের অপেক্ষায়') : s === 'rejected' ? tr('Not approved', 'অনুমোদন হয়নি') : s === 'suspended' ? tr('Suspended by the Lifolo team', 'Lifolo টিম বন্ধ করেছে') : s;
/** ছোট লেবেল (অ্যাপের statusNote; চালু থাকলে "তালিকায় আছে") */
export const statusShort = (s: string) =>
  s === 'approved' ? tr('Listed', 'তালিকায় আছে') : s === 'pending' ? tr('Under review', 'যাচাই চলছে') : s === 'rejected' ? tr('Not approved', 'অনুমোদন হয়নি') : s === 'suspended' ? tr('Suspended', 'বন্ধ করা হয়েছে') : s;

/** একাধিক থাকলে: কয়টা যাচাই চলছে / বাতিল / বন্ধ */
export function statusCounts(statuses: string[]): string[] {
  return (['pending', 'rejected', 'suspended'] as const).flatMap((s) => {
    const n = statuses.filter((x) => x === s).length;
    if (!n) return [];
    return [s === 'pending' ? tr(`${n} under review`, `${num(n)}টা যাচাই চলছে`) : s === 'rejected' ? tr(`${n} not approved`, `${num(n)}টা অনুমোদন হয়নি`) : tr(`${n} suspended`, `${num(n)}টা বন্ধ`)];
  });
}

// ---------- সময় ----------
export const WEEK_ORDER = [6, 0, 1, 2, 3, 4, 5];
export const weekdayShort = (d: number) =>
  [tr('Sun', 'রবি'), tr('Mon', 'সোম'), tr('Tue', 'মঙ্গল'), tr('Wed', 'বুধ'), tr('Thu', 'বৃহস্পতি'), tr('Fri', 'শুক্র'), tr('Sat', 'শনি')][d] ?? '';
export function hhmmText(hhmm: string): string {
  const [h, m] = hhmm.split(':').map((x) => parseInt(x, 10));
  return Number.isFinite(h) ? formatTimeHM(h, Number.isFinite(m) ? m : 0) : hhmm;
}
export const closedDaysText = (days: number[]) =>
  days.length ? tr('Closed every ', 'প্রতি ') + WEEK_ORDER.filter((d) => days.includes(d)).map(weekdayShort).join(', ') + tr('', ' বন্ধ') : '';
export function hoursText(x: { open24: boolean; openAt: string; closeAt: string }) {
  if (x.open24) return tr('Open 24 hours', '২৪ ঘণ্টা খোলা');
  return x.openAt ? `${hhmmText(x.openAt)} – ${hhmmText(x.closeAt)}` : '';
}

// ---------- ফোন ----------
/** মোবাইল, ল্যান্ডলাইন (02…, 031…) বা হটলাইন (16XXX) — সার্ভারের contactPhone-এর মতো */
export function isContactPhone(raw: string): boolean {
  const d = raw.replace(/[০-৯]/g, (c) => String('০১২৩৪৫৬৭৮৯'.indexOf(c))).replace(/\D/g, '');
  const local = d.startsWith('880') ? '0' + d.slice(3) : d;
  return /^0?(1[3-9]\d{8}|120000\d{4})$/.test(local) || /^1\d{4}$/.test(d) || /^0[2-9]\d{7,9}$/.test(local);
}
export const phoneInput = (v: string) => v.replace(/[০-৯]/g, (c) => String('০১২৩৪৫৬৭৮৯'.indexOf(c))).replace(/[^\d+]/g, '').slice(0, 15);

// ---------- ক্যাটালগ (অ্যাম্বুলেন্সের ধরন, ব্লাড ব্যাংকের সেবা) ----------
interface CatItem { code: string; en: string; bn: string; hidden?: boolean }
const BUILT_IN: Record<string, CatItem[]> = {
  ambulanceType: [
    { code: 'general', en: 'General', bn: 'সাধারণ' }, { code: 'ac', en: 'AC', bn: 'এসি' },
    { code: 'icu', en: 'ICU', bn: 'আইসিইউ' }, { code: 'freezer', en: 'Freezer van', bn: 'ফ্রিজার ভ্যান' },
  ],
  bloodBankService: [
    { code: 'whole_blood', en: 'Whole blood', bn: 'পূর্ণ রক্ত' }, { code: 'components', en: 'Plasma & components', bn: 'প্লাজমা ও অন্যান্য অংশ' },
    { code: 'platelets', en: 'Platelets', bn: 'প্লাটিলেট' }, { code: 'screening', en: 'Screening & cross-match', bn: 'স্ক্রিনিং ও ক্রস-ম্যাচ' },
    { code: 'donate_here', en: 'You can donate here', bn: 'এখানে রক্ত দেওয়া যায়' },
  ],
};
/** অ্যাডমিনের তালিকা (catalog_get); না এলে অ্যাপের নিজের তালিকা */
export function useCatalog(kind: 'ambulanceType' | 'bloodBankService') {
  const q = useCall<{ lists: Record<string, { all?: CatItem[] }> }>('catalog_get', {}, { staleTime: 10 * 60_000 });
  const list = computed(() => q.data?.lists?.[kind]?.all ?? BUILT_IN[kind]);
  const label = (code: string) => {
    const it = list.value.find((i) => i.code === code) ?? BUILT_IN[kind].find((i) => i.code === code);
    return it ? tr(it.en, it.bn) : code;
  };
  return { options: computed(() => list.value.filter((i) => !i.hidden).map((i) => ({ value: i.code, label: tr(i.en, i.bn) }))), label };
}

// ---------- কাগজের ছবি (Verify.kt) ----------
export interface DocSpec { type: string; label: string; hint: string; required: boolean }
export const ambulanceDocs = (): DocSpec[] => [
  { type: 'reg', label: tr('Registration certificate or smart card', 'রেজিস্ট্রেশন সনদ বা স্মার্ট কার্ড'), hint: tr('BRTA paper — vehicle number and type readable', 'BRTA-র কাগজ — গাড়ির নম্বর আর ধরন পরিষ্কার পড়া যায়'), required: true },
  { type: 'fitness', label: tr('Fitness certificate', 'ফিটনেস সনদ'), hint: tr('Expiry date readable', 'মেয়াদের তারিখ পড়া যায় এমন'), required: true },
  { type: 'front', label: tr('Photo of the vehicle (with number plate)', 'গাড়ির ছবি (নম্বর প্লেটসহ)'), hint: tr('From front or back, plate readable', 'সামনে বা পেছন থেকে, প্লেট পড়া যায়'), required: true },
];
export const pharmacyDocs = (): DocSpec[] => [
  { type: 'license', label: tr('Drug licence', 'ড্রাগ লাইসেন্স'), hint: tr('Whole paper, number and expiry readable', 'পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়'), required: true },
  { type: 'front', label: tr('Shop front (with signboard)', 'দোকানের সামনের ছবি (সাইনবোর্ডসহ)'), hint: tr('Shop name readable', 'দোকানের নাম পড়া যায়'), required: true },
  { type: 'pharmacist', label: tr('Pharmacist registration certificate', 'ফার্মাসিস্টের নিবন্ধন সনদ'), hint: tr('If you have it', 'থাকলে দিন'), required: false },
];
export const bloodBankDocs = (kind: string): DocSpec[] => [
  { type: 'front', label: tr('Photo of the blood bank (signboard or entrance)', 'ব্লাড ব্যাংকের ছবি (সাইনবোর্ড বা প্রবেশপথ)'), hint: tr('Name readable', 'নাম পড়া যায়'), required: true },
  { type: 'license', label: tr('DGHS blood bank licence', 'স্বাস্থ্য অধিদপ্তরের ব্লাড ব্যাংক লাইসেন্স'), hint: tr('Needed for private blood banks', 'বেসরকারি হলে লাগবে'), required: kind === 'private' },
  { type: 'authority', label: tr("Letter on the hospital's or organisation's pad", 'হাসপাতাল বা সংগঠনের প্যাডে প্রত্যয়ন'), hint: tr('For government hospitals or organisations', 'সরকারি হাসপাতাল বা সংগঠন হলে দিন'), required: false },
];
export const labDocs = (): DocSpec[] => [
  { type: 'license', label: tr('DGHS clinic/diagnostic licence', 'স্বাস্থ্য অধিদপ্তরের ক্লিনিক/ডায়াগনস্টিক লাইসেন্স'), hint: tr('Whole paper, number and expiry readable', 'পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়'), required: true },
  { type: 'front', label: tr('Centre front (with signboard)', 'সেন্টারের সামনের ছবি (সাইনবোর্ডসহ)'), hint: tr('Name readable', 'নাম পড়া যায়'), required: true },
];
export function missingDocs(specs: DocSpec[], photos: Record<string, string>): string | null {
  const miss = specs.filter((d) => d.required && !photos[d.type]);
  return miss.length ? tr('Add photos of: ', 'এই কাগজের ছবি দিন: ') + miss.map((d) => d.label).join(', ') : null;
}
export const PAPERS_CHANGED = () => tr('Add new photos only if the papers changed.', 'কাগজ বদলালে তবেই নতুন ছবি দিন।');

/** কাগজের ছবি: লম্বা দিক সর্বোচ্চ ১৪০০ পিক্সেল, JPEG; base64 ২৮০ KB-র মধ্যে না আসা পর্যন্ত মান কমানো (অ্যাপের Photo.toDocumentBase64) */
export async function compressDocument(file: File): Promise<string> {
  const fail = () => new Error(tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'));
  if (!file.type.startsWith('image/')) throw fail();
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((res, rej) => {
      const i = new Image();
      i.onload = () => res(i);
      i.onerror = () => rej(fail());
      i.src = url;
    });
    const w0 = img.naturalWidth;
    const h0 = img.naturalHeight;
    if (!w0 || !h0) throw fail();
    let maxSide = 1400;
    let quality = 0.75;
    for (;;) {
      const scale = Math.min(1, maxSide / Math.max(w0, h0));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(w0 * scale));
      canvas.height = Math.max(1, Math.round(h0 * scale));
      const ctx = canvas.getContext('2d');
      if (!ctx) throw fail();
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      const b64 = canvas.toDataURL('image/jpeg', quality).replace(/^data:image\/jpeg;base64,/, '');
      if (b64.length <= 280 * 1024 || maxSide <= 700) return b64;
      if (quality > 0.55) quality -= 0.1;
      else maxSide -= 200;
    }
  } finally {
    URL.revokeObjectURL(url);
  }
}

export const callsLine = (c?: CallSummary | null) =>
  c ? tr(`${c.calls[7]} in 7 days · ${c.calls[30]} in 30 days`, `৭ দিনে ${num(c.calls[7])} · ৩০ দিনে ${num(c.calls[30])}`) : '—';

export const sentForApproval = (kind: Kind) =>
  kind === 'pharmacy' || kind === 'lab'
    ? tr('Sent for approval. An admin will check the licence.', 'অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন।')
    : tr('Sent for approval. It will be listed after an admin checks it.', 'অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন যাচাই করলে তালিকায় আসবে।');

// ---------- ফার্মেসি আর প্যাথলজির দুই ট্যাব (২.২৭.০, অ্যাপের MyWorkTabs.kt) ----------
// ফার্মেসি (২.৩০.০ থেকে তিন ট্যাব): "অর্ডার" (/work/pharmacy/orders — শুরুতে) · "ওষুধ খোঁজা" (/work/pharmacy/finds) ·
//   "দোকান" — তথ্য (/work/pharmacy/shop) · বিক্রি (/shop/sales) · স্টক (/shop/stock, ?v=buy মাল কেনা) · এনালাইটিক্স (/shop/analytics)।
//   পুরোনো হিসাব-খাতার লিংক (/work/pharmacy/orders/stock|buy|sales|books) নতুন জায়গায় চলে যায় (rx/partner/pharmacy.tsx)।
// প্যাথলজি: "অনুরোধ" (/work/lab/orders) · "ল্যাব" (/work/lab/info, পরীক্ষা ও দাম, স্বাভাবিক মাত্রা)
// পুরোনো লিংক (/work/pharmacy, /work/lab, …/orders/online, নোটিফিকেশন) আগের মতোই খোলে — index.tsx।
export type PharmTab = 'orders' | 'finds' | 'info' | 'sales' | 'stock' | 'analytics';
export const PHARM_SHOP_TABS: PharmTab[] = ['info', 'sales', 'stock', 'analytics'];
/** দোকানের ভেতরের ভাগের ঠিকানা */
export const pharmTabPath = (k: PharmTab) => (k === 'orders' ? '/work/pharmacy/orders' : k === 'finds' ? '/work/pharmacy/finds' : k === 'info' ? '/work/pharmacy/shop' : `/work/pharmacy/shop/${k}`);

/** "ওষুধ খোঁজা"-র সংখ্যা: উত্তর না দেওয়া খোলা অনুরোধ (চালু থাকলে) */
export function useUnansweredFinds(enabled: MaybeRefOrGetter<boolean>) {
  const q = useCall<{ items: { answer: string | null }[]; findOff: boolean }>('med_find_requests', {}, () => ({ enabled: toValue(enabled), refetchInterval: 60_000 }));
  return computed(() => (q.data && !q.data.findOff ? q.data.items.filter((r) => !r.answer).length : 0));
}
export type LabTab = 'orders' | 'info' | 'prices' | 'ranges';

/** "নতুন" কয়টা — সার্ভারের counts (তালিকার পাতার একই চাবি, আবার আনা হয় না) */
export function useNewCount(action: 'rx_orders_pharmacy' | 'lab_orders', enabled: MaybeRefOrGetter<boolean>) {
  const q = useCall<{ items: { status: string; seg?: string }[]; counts?: Record<string, number> }>(action, {}, () => ({ enabled: toValue(enabled), refetchInterval: 60_000 }));
  return computed(() => q.data?.counts?.new ?? q.data?.items.filter((o) => (o.seg ? o.seg === 'new' : o.status === 'sent')).length ?? 0);
}

/** উপজেলার নাম দেখাতে তালিকা আনা (একবার) — geo.ts-এর areasTick দিয়ে নিজেই আবার আঁকে */
export function useAreas() {
  onMounted(() => {
    if (!areasReady()) ensureAreas().catch(() => {});
  });
}

/** সেভের পর আগের পাতায় বার্তা (navigation state) */
export function useFlash(): [Ref<string | null>, (m: string | null) => void] {
  const loc = useLoc();
  const msg = ref<string | null>((loc.value.state as { flash?: string } | null)?.flash ?? null);
  watch(
    () => [loc.value.state, loc.value.pathname] as const,
    ([st]) => {
      const f = (st as { flash?: string } | null)?.flash;
      if (f) {
        msg.value = f;
        // React: nav(pathname, { replace, state: null }) — Vue Router একই ঠিকানায় যায় না, তাই সরাসরি ইতিহাসের state থেকে মোছা
        const { flash: _f, ...rest } = window.history.state ?? {};
        window.history.replaceState(rest, '');
      }
    },
    { immediate: true },
  );
  return [msg, (m) => (msg.value = m)];
}

/** সেভ/মোছার পর: নিচের বার আর হিসাব নতুন করে */
export function useAfterChange() {
  const s = useSession();
  const qc = useQueryClient();
  return async () => {
    qc.invalidateQueries({ predicate: (q) => ['ambulance_stats', 'pharmacy_stats', 'bloodbank_stats', 'med_find_requests'].includes(String(q.queryKey[0])) });
    await s.refreshWork();
  };
}

// ---------- শর্ত ----------
export const TERMS_URL = 'https://lifolo.com/terms';

// ---------- ব্লাড ব্যাংকের ধরন ----------
export const BB_KINDS = ['govt', 'private', 'org'];
export const bbKindLabel = (k: string) => (k === 'govt' ? tr('Government hospital', 'সরকারি হাসপাতাল') : k === 'private' ? tr('Private', 'বেসরকারি') : tr('Volunteer organisation', 'স্বেচ্ছাসেবী সংগঠন'));
