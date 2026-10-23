// সেবার পাঁচ তালিকার এক ছাঁচ (src/ui/listing/model.ts, sections/*/listing.ts): সার্ভারের নতুন ঘর (null-safe), মূল লাইন,
// মজুদ আর "পুরনো তথ্য", ডাক্তারের সিরিয়ালের ব্যাজ, সাজানো, খোঁজা; কার্ড মাউন্ট (ফোন নম্বর নেই, মূল বাটন + কল আইকন)।
import { afterEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { setLang } from '@/lib/i18n';
import {
  LT, distanceText, feeNumber, matchesSearch, nextSlotOf, ratingOf, ratingText, serialBadge, sortListings, stockOf, keyLineText, type Listing,
} from '@/ui/listing/model';
import { bloodBankListing, labListing, pharmacyListing, ambulanceListing } from '@/sections/services/listing';
import { doctorListing } from '@/sections/appointment/listing';
import { parseProvider } from '@/sections/appointment/model';
import ListingCard from '@/ui/listing/ListingCard.vue';
import StockChips from '@/ui/listing/StockChips.vue';

const DAY = 86400_000;
const T0 = Date.UTC(2026, 9, 23);

describe('listing model', () => {
  afterEach(() => setLang('bn'));

  it('রেটিং: নতুন ratingSummary আগে, পুরোনো rating + ratingCount, কেউ না দিলে null', () => {
    expect(ratingOf({ ratingSummary: { avg: 4.6, count: 31 }, rating: 4.6, ratingCount: 31 })).toEqual({ avg: 4.6, count: 31 });
    expect(ratingOf({ rating: 4.2, ratingCount: 12 })).toEqual({ avg: 4.2, count: 12 });
    expect(ratingOf({ ratingSummary: null, rating: 0, ratingCount: 0 })).toBeNull();
    expect(ratingOf({})).toBeNull();
    expect(ratingText({ avg: 4.6, count: 31 })).toBe('★৪.৬ (৩১)');
  });

  it('দূরত্ব: "১ কিমির কম", "৩ কিমি", আনুমানিক হলে "প্রায় ৩ কিমি"; বাংলা অঙ্ক', () => {
    expect(distanceText(0.6)).toBe('১ কিমির কম');
    expect(distanceText(2.6)).toBe('৩ কিমি');
    expect(distanceText(2.6, true)).toBe('প্রায় ৩ কিমি');
    expect(distanceText(0.2, true)).toBe('প্রায় ১ কিমি');
    expect(distanceText(null)).toBe('');
    setLang('en');
    expect(distanceText(2.6, true)).toBe('About 3 km');
  });

  it('মজুদ: জানা গ্রুপগুলো, হালনাগাদ, ৪৮ ঘণ্টার বেশি = পুরনো তথ্য; stock null = "স্টকের খবর এখনো দেওয়া হয়নি"; ঘরই নেই = কিছু না', () => {
    const now = Date.now();
    const s = stockOf({ stock: { 'A+': 'have', 'B+': 'low', 'O-': 'none', 'AB-': null }, stockUpdatedAt: now - 2 * 3600_000, stockStale: false }, now)!;
    expect(s.cells.map((c) => `${c.group} ${LT.stock(c.level)}`)).toEqual(['A+ আছে', 'B+ কম', 'O- নেই']);
    expect(s.updated).toBe('২ ঘণ্টা আগে');
    expect(s.stale).toBe(false);
    expect(stockOf({ stock: { 'A+': 'have' }, stockUpdatedAt: now - 49 * 3600_000 }, now)!.stale).toBe(true);
    expect(stockOf({ stock: null, stockUpdatedAt: null })).toEqual({ cells: [], updated: null, stale: false });
    expect(stockOf({ name: 'x' })).toBeNull();
    const w = mount(StockChips, { props: { stock: stockOf({ stock: null })! } });
    expect(w.text()).toBe('স্টকের খবর এখনো দেওয়া হয়নি');
  });

  it('ডাক্তার: পরের সিরিয়াল — আজ খালি থাকলে "আজ সিরিয়াল আছে", কাল "পরের সিরিয়াল: কাল", পরে তারিখ', () => {
    expect(serialBadge(nextSlotOf({ nextSlot: { date: T0, label: 'আজ', available: true } }), T0)).toEqual({ text: 'আজ সিরিয়াল আছে', tone: 'good' });
    expect(serialBadge(nextSlotOf({ nextSlot: { date: T0 + DAY, label: 'কাল', available: true } }), T0)!.text).toBe('পরের সিরিয়াল: কাল');
    expect(serialBadge(nextSlotOf({ nextSlot: { date: T0 + 2 * DAY, label: '২৫ অক্টোবর', available: true } }), T0)!.text).toBe('পরের সিরিয়াল: ২৫ অক্টোবর');
    expect(serialBadge(nextSlotOf({ nextSlot: null }), T0)).toBeNull();
    expect(serialBadge(nextSlotOf({}), T0)).toBeNull();
    // ইংরেজি সার্ভারের লেবেল (date ছাড়া)
    expect(serialBadge({ date: null, label: 'Today', available: true }, T0)!.text).toBe('আজ সিরিয়াল আছে');
  });

  it('ফি: feeAmount না এলে পুরোনো লেখা থেকে ("৫০০ টাকা", "৳1,200")', () => {
    expect(feeNumber('৫০০ টাকা')).toBe(500);
    expect(feeNumber('৳1,200')).toBe(1200);
    expect(feeNumber(700)).toBe(700);
    expect(feeNumber('আলোচনা সাপেক্ষে')).toBeNull();
    expect(LT.fee(500)).toBe('ফি ৳৫০০');
  });

  it('সাজানো: নিকটতম (অজানা শেষে), রেটিং (তারপর কয়জন), খোলা আগে — সমান হলে তালিকার নিজের ক্রম', () => {
    const L = (id: string, km: number | null, avg: number | null, open: boolean | null) => ({ id, distanceKm: km, rating: avg == null ? null : { avg, count: 3 }, openNow: open });
    const xs = [L('a', null, 4.1, false), L('b', 3, null, true), L('c', 1, 4.8, null), L('d', 3, 4.8, true)];
    expect(sortListings(xs, 'near').map((x) => x.id)).toEqual(['c', 'b', 'd', 'a']);
    expect(sortListings(xs, 'rating').map((x) => x.id)).toEqual(['c', 'd', 'a', 'b']);
    expect(sortListings(xs, 'open').map((x) => x.id)).toEqual(['b', 'd', 'c', 'a']);
    expect(sortListings(xs, null)).toBe(xs);
  });

  it('খোঁজা: সব শব্দ নাম/এলাকায়, বড়-ছোট হাতের অক্ষর আলাদা নয়', () => {
    expect(matchesSearch({ search: 'Life Care ফার্মেসি হাসপাতাল রোড' }, 'care রোড')).toBe(true);
    expect(matchesSearch({ search: 'Life Care' }, 'care x')).toBe(false);
    expect(matchesSearch({ search: 'x' }, '  ')).toBe(true);
  });
});

describe('পাঁচ ধরনের মূল লাইন', () => {
  it('ফার্মেসি: খোলা/বন্ধ · দূরত্ব · ডেলিভারি; ব্যাজ ২৪ ঘণ্টা, ছাড়', () => {
    const l = pharmacyListing({ id: 'p', name: 'X', phone: '01', address: '', district: '', upazilaId: null, openAt: '', closeAt: '', open24: true, openNow: true, closedUntil: 0, closedDays: [], delivery: false, deliveryNote: '', discount: 10, rating: 4.7, ratingCount: 31, km: null, distanceKm: 1.8, distanceApprox: false, is24h: true } as any);
    expect(keyLineText(l.keyLine)).toBe('খোলা · ২ কিমি · ডেলিভারি নেই');
    expect(l.badges.map((b) => b.text)).toEqual(['২৪ ঘণ্টা', '১০% ছাড়']);
    expect(ratingText(l.rating)).toBe('★৪.৭ (৩১)');
  });
  it('প্যাথলজি: openNow না জানলে বাদ; বাসা থেকে নমুনা', () => {
    const l = labListing({ id: 'l', name: 'L', phone: '', address: '', district: '', upazilaId: null, facilityId: null, homeCollection: true, hours: '', openNow: null, distanceKm: 0.4, distanceApprox: true } as any);
    expect(keyLineText(l.keyLine)).toBe('প্রায় ১ কিমি · বাসা থেকে নমুনা');
    expect(l.rating).toBeNull();
  });
  it('ব্লাড ব্যাংক: দূরত্ব · খোলা/বন্ধ, মজুদ', () => {
    const l = bloodBankListing({ id: 'b', name: 'B', kind: 'govt', phone: '', district: '', upazilaId: null, address: '', open24: false, openAt: '', closeAt: '', openNow: false, services: [], closedDays: [], distanceKm: 5, distanceApprox: true, stock: { 'O+': 'have' }, stockUpdatedAt: Date.now() } as any);
    expect(keyLineText(l.keyLine)).toBe('প্রায় ৫ কিমি · বন্ধ');
    expect(l.stock!.cells).toEqual([{ group: 'O+', level: 'have' }]);
  });
  it('অ্যাম্বুলেন্স: ধরন · দূরত্ব · রেটিং (রেটিং মূল লাইনে, আলাদা সারিতে নয়); খালি আছে / ফোনে জেনে নিন / ট্রিপে আছে', () => {
    const base = { id: 'a', name: 'A', phone: '01', types: ['ac', 'icu'], district: '', upazilaId: null, area: '', fare: '', availableAt: 1, rating: 4.6, ratingCount: 23, km: 0 };
    const l = ambulanceListing({ ...base, available: true } as any, null);
    expect(keyLineText(l.keyLine)).toBe('এসি/আইসিইউ · ১ কিমির কম · ★৪.৬ (২৩)');
    expect(l.ratingRow).toBe(false);
    expect(l.badges[0].text).toBe('খালি আছে');
    expect(ambulanceListing({ ...base, available: false } as any, null).badges[0].text).toBe('ফোনে জেনে নিন');
    expect(ambulanceListing({ ...base, available: false, busySince: Date.now() } as any, null).badges[0].text).toBe('ট্রিপে আছে');
  });
  it('ডাক্তার: বিশেষজ্ঞতা · ফি ৳৫০০ (feeAmount), সিরিয়ালের ব্যাজ; পুরোনো সার্ভারে চেম্বারের ফি থেকে', () => {
    const raw = { uid: 'd', type: 'doctor', name: 'ডা. ক', specialties: ['cardiology'], appointments: true, chambers: [{ hospitalName: 'H', appointments: true, fee: '৭০০ টাকা', days: [], start: '', end: '' }] };
    const today = Date.UTC(new Date(Date.now() + 6 * 3600_000).getUTCFullYear(), new Date(Date.now() + 6 * 3600_000).getUTCMonth(), new Date(Date.now() + 6 * 3600_000).getUTCDate());
    const l = doctorListing(parseProvider(raw), { ...raw, feeAmount: 500, specialtyLabel: 'হৃদরোগ', nextSlot: { date: today, label: 'আজ', available: true, chamberIndex: 0 } });
    expect(keyLineText(l.keyLine)).toBe('হৃদরোগ · ফি ৳৫০০');
    expect(l.badges[0].text).toBe('আজ সিরিয়াল আছে');
    const old = doctorListing(parseProvider(raw), raw);
    expect(keyLineText(old.keyLine)).toContain('ফি ৳৭০০');
    expect(doctorListing(parseProvider(raw), { ...raw, feeAmount: 0 }).keyLine).toContain('ফি নেই');
  });
});

describe('ListingCard', () => {
  const global = { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] as any };
  const l: Listing = {
    id: 'x', kind: 'bloodbank', name: 'জেলা ব্লাড ব্যাংক', icon: 'bloodtype', verified: true, keyLine: ['১ কিমি', 'খোলা'], badges: [], stock: null,
    rating: null, ratingRow: true, phone: '01711000101', openNow: true, distanceKm: 1, search: '', address: '',
  };
  it('মূল বাটন "ফোন করুন" (tel:), ফোন নম্বর লেখা নেই, যাচাই ব্যাজ, ⋮ মেনু', () => {
    const w = mount(ListingCard, { props: { l, callPrimary: true, onOpen: () => {}, onReport: () => {} }, global });
    expect(w.find('[data-testid="listing-primary"]').attributes('href')).toBe('tel:01711000101');
    expect(w.find('[data-testid="listing-primary"]').text()).toBe('ফোন করুন');
    expect(w.text()).not.toMatch(/০১৭১১|01711/);
    expect(w.find('.lst-verified').exists()).toBe(true);
    expect(w.find('[data-testid="key-line"]').text()).toBe('১ কিমি · খোলা');
    expect(w.find('[aria-haspopup="menu"]').exists()).toBe(true);
  });
  it('preview: মেনু আর বাটন নেই', () => {
    const w = mount(ListingCard, { props: { l, callPrimary: true, preview: true }, global });
    expect(w.find('[data-testid="listing-primary"]').exists()).toBe(false);
    expect(w.find('[aria-haspopup="menu"]').exists()).toBe(false);
  });
});
