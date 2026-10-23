// সেবার পাঁচটা তালিকার এক ছাঁচ (ডাক্তার, ফার্মেসি, প্যাথলজি/ল্যাব, ব্লাড ব্যাংক, অ্যাম্বুলেন্স) — চুক্তি: listings CONTRACT.md §১,
// সার্ভারের বাড়তি তথ্য backend/docs/LISTINGS.md। প্রতিটা কার্ড আর বিস্তারিত একই গঠনে: নাম, "মূল লাইন" (৩টা তথ্য, " · " দিয়ে),
// ব্যাজ, রেটিং, মূল বাটন + যোগাযোগ (ContactActions)। সার্ভারের নতুন ঘর না এলে (পুরোনো সার্ভার) আগের ঘর থেকে, না থাকলে কিছুই নয়।
import { num, tr } from '@/lib/i18n';
import { formatDate, formatKm, timeAgo, todayUtcMidnight } from '@/lib/format';

export type ListingKind = 'ambulance' | 'doctor' | 'pharmacy' | 'bloodbank' | 'lab';
/** সেবার ট্যাবের ক্রম (চুক্তি): অ্যাম্বুলেন্স / ডাক্তার / ফার্মেসি / ব্লাড ব্যাংক / ল্যাব */
export const LISTING_KINDS: ListingKind[] = ['ambulance', 'doctor', 'pharmacy', 'bloodbank', 'lab'];
export const isListingKind = (k: string | null | undefined): k is ListingKind => !!k && (LISTING_KINDS as string[]).includes(k);

export type SortKey = 'near' | 'rating' | 'open';
export const SORTS: SortKey[] = ['near', 'rating', 'open'];
export const isSort = (v: string | null | undefined): v is SortKey => !!v && (SORTS as string[]).includes(v);

export type StockLevel = 'have' | 'low' | 'none';
export const BLOOD_GROUPS = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'] as const;

export interface Rating { avg: number; count: number }
export interface Badge { text: string; tone?: 'good' | 'primary' | 'info' | 'error' }
export interface StockCell { group: string; level: StockLevel }
export interface Stock { cells: StockCell[]; updated: string | null; stale: boolean }

/** কার্ড আর বিস্তারিতের এক রূপ — পাতা (Directory, DoctorsPage) সার্ভারের উত্তর থেকে বানায় */
export interface Listing {
  id: string;
  kind: ListingKind;
  name: string;
  icon: string;
  photo?: string | null;
  tint?: 'error';
  verified: boolean;
  /** মূল লাইনের তথ্য (খালিগুলো বাদ) */
  keyLine: string[];
  badges: Badge[];
  stock: Stock | null;
  /** কার্ডের ৩য় সারিতে রেটিং — অ্যাম্বুলেন্সে মূল লাইনেই, তাই সেখানে নয় */
  rating: Rating | null;
  ratingRow: boolean;
  phone: string;
  openNow: boolean | null;
  distanceKm: number | null;
  /** খোঁজার লেখা (নাম, এলাকা, ঠিকানা …) */
  search: string;
  address: string;
}

// ---------- লেখা (অ্যান্ড্রয়েডের সাথে হুবহু; চুক্তির শব্দ) ----------
export const LT = {
  open: () => tr('Open', 'খোলা'),
  closed: () => tr('Closed', 'বন্ধ'),
  h24: () => tr('24 hours', '২৪ ঘণ্টা'),
  delivery: (on: boolean) => (on ? tr('Delivery', 'ডেলিভারি আছে') : tr('No delivery', 'ডেলিভারি নেই')), // bn-ok
  homeSample: () => tr('Sample from home', 'বাসা থেকে নমুনা'), // bn-ok
  discount: (pct: number) => tr(`${pct}% off`, `${num(pct)}% ছাড়`), // bn-ok
  fee: (n: number) => tr(`Fee ৳${n.toLocaleString('en-IN')}`, `ফি ৳${num(n.toLocaleString('en-IN'))}`), // bn-ok
  serialToday: () => tr('Serial today', 'আজ সিরিয়াল আছে'), // bn-ok
  nextSerial: (label: string) => tr('Next serial: ', 'পরের সিরিয়াল: ') + label, // bn-ok
  tomorrow: () => tr('tomorrow', 'কাল'),
  available: () => tr('Available', 'খালি আছে'),
  callToCheck: () => tr('Call to check', 'ফোনে জেনে নিন'),
  stock: (l: StockLevel) => (l === 'have' ? tr('Have', 'আছে') : l === 'low' ? tr('Low', 'কম') : tr('None', 'নেই')),
  updated: (ago: string) => tr('Updated: ', 'হালনাগাদ: ') + ago, // bn-ok
  stale: () => tr('Old info', 'পুরনো তথ্য'), // bn-ok
  noStock: () => tr('No stock news yet', 'স্টকের খবর এখনো দেওয়া হয়নি'), // bn-ok
  details: () => tr('Details', 'বিস্তারিত'),
  onTrip: () => tr('On a trip', 'ট্রিপে আছে'),
  verified: () => tr('Verified', 'যাচাই করা'), // bn-ok
  // মূল বাটন
  book: () => tr('Book serial', 'সিরিয়াল নিন'), // bn-ok
  sendRx: () => tr('Send prescription', 'প্রেসক্রিপশন পাঠান'),
  sendTest: () => tr('Send tests', 'পরীক্ষা পাঠান'), // bn-ok
  call: () => tr('Call', 'ফোন করুন'),
  // উপরের অংশ
  sort: (k: SortKey) => (k === 'near' ? tr('Nearest', 'নিকটতম') : k === 'rating' ? tr('Rating', 'রেটিং') : tr('Open first', 'এখন খোলা আগে')), // bn-ok
  sortLabel: () => tr('Sort', 'সাজান'), // bn-ok
  clear: () => tr('Clear', 'মুছুন'), // bn-ok
  more: () => tr('More', 'আরও'),
  changeArea: () => tr('Change area', 'এলাকা বদলান'),
  search: (k: ListingKind) =>
    k === 'ambulance' ? tr('Ambulance name or area', 'অ্যাম্বুলেন্সের নাম বা এলাকা') // bn-ok
      : k === 'doctor' ? tr('Doctor name, specialty or chamber', 'ডাক্তারের নাম, বিশেষজ্ঞতা বা চেম্বার') // bn-ok
        : k === 'pharmacy' ? tr('Pharmacy name or area', 'ফার্মেসির নাম বা এলাকা') // bn-ok
          : k === 'bloodbank' ? tr('Blood bank name or area', 'ব্লাড ব্যাংকের নাম বা এলাকা') // bn-ok
            : tr('Lab name or area', 'ল্যাবের নাম বা এলাকা'), // bn-ok
  /** খালি অবস্থার এক লাইন */
  empty: (k: ListingKind) =>
    k === 'ambulance' ? tr('No ambulance in this area yet', 'এই এলাকায় এখনো কোনো অ্যাম্বুলেন্স নেই') // bn-ok
      : k === 'doctor' ? tr('No doctor in this area yet', 'এই এলাকায় এখনো কোনো ডাক্তার নেই') // bn-ok
        : k === 'pharmacy' ? tr('No pharmacy in this area yet', 'এই এলাকায় এখনো কোনো ফার্মেসি নেই') // bn-ok
          : k === 'bloodbank' ? tr('No blood bank in this area yet', 'এই এলাকায় এখনো কোনো ব্লাড ব্যাংক নেই') // bn-ok
            : tr('No lab in this area yet', 'এই এলাকায় এখনো কোনো ল্যাব নেই'), // bn-ok
  /** ছাঁকনিতে কিছু না মিললে */
  noMatch: () => tr('Nothing matches this search', 'এই খোঁজে কিছু মেলেনি'), // bn-ok
  tab: (k: ListingKind) =>
    k === 'ambulance' ? tr('Ambulance', 'অ্যাম্বুলেন্স') : k === 'doctor' ? tr('Doctor', 'ডাক্তার') : k === 'pharmacy' ? tr('Pharmacy', 'ফার্মেসি')
      : k === 'bloodbank' ? tr('Blood bank', 'ব্লাড ব্যাংক') : tr('Lab', 'ল্যাব'), // bn-ok
  // বিস্তারিতের ভাগ
  keyFacts: () => tr('Key facts', 'মূল তথ্য'), // bn-ok
  addressPhone: () => tr('Address and phone', 'ঠিকানা ও ফোন'), // bn-ok
  ratings: () => tr('Ratings', 'রেটিং'),
  noRatings: () => tr('No ratings yet', 'এখনো রেটিং নেই'), // bn-ok
  stockTitle: () => tr('Blood stock', 'রক্তের মজুদ'), // bn-ok
  copyLink: () => tr('Copy link', 'লিংক কপি করুন'), // bn-ok
  linkCopied: () => tr('Link copied', 'লিংক কপি হয়েছে'), // bn-ok
  previewTitle: () => tr('How patients see you', 'রোগীরা আপনাকে যেভাবে দেখেন'), // bn-ok
  chooseArea: () => tr('Choose an area to see the list', 'তালিকা দেখতে এলাকা বেছে নিন'), // bn-ok
  // "আমার কাজ"-এর এক চাপের সুইচ
  openNowSw: () => tr('Open now', 'এখন খোলা'),
  closedNowSw: () => tr('Closed now', 'এখন বন্ধ'), // bn-ok
  followHours: () => tr('Follow the hours', 'সময়সূচি মেনে চলুন'), // bn-ok
  overrideNote: () => tr('Until the next opening/closing time', 'পরের খোলা/বন্ধের সময় পর্যন্ত'), // bn-ok
  deliveryOn: () => tr('Delivery on', 'ডেলিভারি চালু'), // bn-ok
  deliveryOff: () => tr('Delivery off', 'ডেলিভারি বন্ধ'), // bn-ok
  homeOn: () => tr('Home sample on', 'বাসা থেকে নমুনা চালু'), // bn-ok
  homeOff: () => tr('Home sample off', 'বাসা থেকে নমুনা বন্ধ'), // bn-ok
  ambFree: () => tr('I am free', 'খালি আছি'), // bn-ok
  ambTrip: () => tr('On a trip', 'ট্রিপে আছি'), // bn-ok
  serialOn: () => tr('Serial running today', 'আজ সিরিয়াল চলছে'), // bn-ok
  serialOff: () => tr('Closed today', 'আজ বন্ধ'), // bn-ok
  stockAllOk: () => tr('All correct — update now', 'সব ঠিক আছে — এখন হালনাগাদ'), // bn-ok
  stockSaved: () => tr('Stock saved', 'মজুদ সেভ হয়েছে'), // bn-ok
  // কাজের সারি
  segNew: () => tr('New', 'নতুন'),
  segActive: () => tr('In progress', 'চলছে'), // bn-ok
  segDone: () => tr('Done', 'শেষ'),
  emergency999: () => tr('Emergency: call 999', 'জরুরি হলে ৯৯৯'), // bn-ok
};

// ---------- সার্ভারের ঘর (নতুন বা পুরোনো) ----------
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

/** রেটিং: নতুন `ratingSummary` / `rating: {avg, count}`; না হলে পুরোনো `rating` (সংখ্যা) + `ratingCount` */
export function ratingOf(o: any): Rating | null {
  const r = o?.ratingSummary ?? (o?.rating && typeof o.rating === 'object' ? o.rating : null);
  if (r && isNum(r.avg) && isNum(r.count)) return r.count > 0 ? { avg: r.avg, count: r.count } : null;
  if (isNum(o?.rating) && isNum(o?.ratingCount) && o.ratingCount > 0) return { avg: o.rating, count: o.ratingCount };
  return null;
}
/** দূরত্ব (কিমি): নতুন `distanceKm`, পুরোনো `km` */
export const distanceOf = (o: any): number | null => (isNum(o?.distanceKm) ? o.distanceKm : isNum(o?.km) ? o.km : null);
/** এখন খোলা: true/false, জানা না থাকলে null */
export const openNowOf = (o: any): boolean | null => (typeof o?.openNow === 'boolean' ? o.openNow : null);

/** "★৪.৬ (৩১)" */
export const ratingText = (r: Rating | null) => (r ? `★${num(r.avg.toFixed(1))} (${num(r.count)})` : '');
/** "১ কিমির কম" / "৩ কিমি"; আনুমানিক (এলাকার কেন্দ্র ধরে) হলে "প্রায় ৩ কিমি" */
export const distanceText = (km: number | null, approx = false) =>
  km == null ? '' : approx ? tr(`about ${Math.max(1, Math.round(km))} km`, `প্রায় ${num(Math.max(1, Math.round(km)))} কিমি`) : formatKm(km);
export const distanceApproxOf = (o: any): boolean => o?.distanceApprox === true || (o?.distanceKm == null && o?.kmApprox === true);
export const openText = (o: boolean | null) => (o == null ? '' : o ? LT.open() : LT.closed());

/** ব্লাড ব্যাংকের মজুদ: শুধু জানা গ্রুপ (আছে/কম/নেই); একটাও না জানলে null */
export function stockOf(o: any, now = Date.now()): Stock | null {
  const st = o?.stock;
  // সার্ভার নতুন (stock ঘর আছে) কিন্তু মালিক এখনো জানাননি → "স্টকের খবর এখনো দেওয়া হয়নি"
  if (st === null && 'stock' in (o ?? {})) return { cells: [], updated: null, stale: false };
  if (!st || typeof st !== 'object') return null;
  const cells: StockCell[] = [];
  for (const g of BLOOD_GROUPS) {
    const v = st[g];
    if (v === 'have' || v === 'low' || v === 'none') cells.push({ group: g, level: v });
  }
  const at = isNum(o?.stockUpdatedAt) ? o.stockUpdatedAt : null;
  const stale = typeof o?.stockStale === 'boolean' ? o.stockStale : at != null ? now - at > 48 * 3600_000 : false;
  return { cells, updated: at != null ? timeAgo(at) : null, stale };
}

/** "৫০০ টাকা" / "৳৫০০" / 500 → 500 (না বুঝলে null) */
export function feeNumber(v: unknown): number | null {
  if (isNum(v)) return v > 0 ? v : null;
  if (typeof v !== 'string') return null;
  const ascii = v.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d))).replace(/,/g, '');
  const m = /\d+/.exec(ascii);
  return m ? +m[0] || null : null;
}

/** ডাক্তারের পরের সিরিয়াল: {date (UTC মধ্যরাত, ঢাকার দিন), label, available} → ব্যাজ */
export interface NextSlot { date: number | null; label: string; available: boolean }
export function nextSlotOf(o: any): NextSlot | null {
  const s = o?.nextSlot;
  if (!s || typeof s !== 'object') return null;
  return { date: isNum(s.date) ? s.date : null, label: typeof s.label === 'string' ? s.label : '', available: s.available !== false };
}
export function serialBadge(s: NextSlot | null, today = todayUtcMidnight()): Badge | null {
  if (!s) return null;
  const DAY = 86400_000;
  const isToday = s.date != null ? s.date === today : s.label === 'আজ' || s.label === 'Today'; // bn-ok
  const isTomorrow = s.date != null ? s.date === today + DAY : s.label === 'কাল' || s.label === 'Tomorrow'; // bn-ok
  if (isToday) return s.available ? { text: LT.serialToday(), tone: 'good' } : null;
  const label = isTomorrow ? LT.tomorrow() : s.date != null ? formatDate(s.date).replace(/\s+\S+$/, '') : s.label;
  return label ? { text: LT.nextSerial(label), tone: 'info' } : null;
}

/** খোঁজা: সব শব্দ নাম/এলাকা/ঠিকানার কোথাও আছে (বড়-ছোট হাতের অক্ষর আলাদা নয়) */
export function matchesSearch(l: Pick<Listing, 'search'>, q: string): boolean {
  const words = q.trim().toLowerCase().normalize('NFC').split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const hay = l.search.toLowerCase().normalize('NFC');
  return words.every((w) => hay.includes(w));
}

/**
 * সাজানো (সার্ভারও `sort` পেলে সাজায়; পুরোনো সার্ভারে এখানেই) — সমান হলে তালিকার নিজের ক্রম:
 * near = দূরত্ব (অজানা শেষে), rating = গড় তারপর কয়জন (রেটিং নেই শেষে), open = খোলা/খালি আগে, তারপর অজানা, তারপর বন্ধ; তারপর দূরত্ব।
 */
export function sortListings<T extends Pick<Listing, 'distanceKm' | 'rating' | 'openNow'>>(items: T[], sort: SortKey | null): T[] {
  if (!sort) return items;
  const km = (x: T) => x.distanceKm ?? Number.POSITIVE_INFINITY;
  const open = (x: T) => (x.openNow === true ? 0 : x.openNow == null ? 1 : 2);
  const cmp =
    sort === 'near' ? (a: T, b: T) => km(a) - km(b)
      : sort === 'rating' ? (a: T, b: T) => (b.rating?.avg ?? -1) - (a.rating?.avg ?? -1) || (b.rating?.count ?? 0) - (a.rating?.count ?? 0)
        : (a: T, b: T) => open(a) - open(b) || km(a) - km(b);
  return items.map((x, i) => [x, i] as const).sort((a, b) => cmp(a[0], b[0]) || a[1] - b[1]).map((x) => x[0]);
}

/** মূল লাইন: খালি অংশ বাদ, " · " দিয়ে */
export const keyLineText = (parts: (string | null | undefined)[]) => parts.filter((p): p is string => !!p && !!p.trim()).join(' · ');
export const facts = (parts: (string | null | undefined)[]) => parts.filter((p): p is string => !!p && !!p.trim());
