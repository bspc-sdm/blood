// সেবার চার ধরনের (অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি) সার্ভারের উত্তর → এক ছাঁচের কার্ড/বিস্তারিত (src/ui/listing)।
// সার্ভারের নতুন ঘর (openNow, distanceKm, ratingSummary, delivery, homeCollection, stock …; backend/docs/LISTINGS.md) না এলে
// আগের ঘর থেকে, তাও না থাকলে সেই তথ্য দেখায় না।
import { tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { taka, discountText, homeFeeText, NO_PRICE, type PublicPrices } from '@/lib/labprice';
import { LT, distanceApproxOf, distanceOf, distanceText, openNowOf, openText, ratingOf, ratingText, stockOf, facts, type Badge, type Listing } from '@/ui/listing/model';
import type { DLine, DSection } from '@/ui/listing/ListingDetail.vue';
import {
  ambulanceKm, ambulanceTypeLabel, aspectsText, bloodBankKind, bloodBankService, closedDaysText, deliveryText, discountTermsText, flatDiscountText, hoursText,
  type Ambulance, type BloodBank, type Kind, type Lab, type Lists, type Pharmacy,
} from './data';
import { formatDate, timeAgo } from '@/lib/format';

const join = (xs: (string | null | undefined)[], sep: string) => xs.filter((x) => x && x.trim()).join(sep);
const vf = (o: any) => o?.verified === true;

export function ambulanceListing(a: Ambulance, lists: Lists | null): Listing {
  const km = distanceOf(a);
  const rating = ratingOf(a);
  const types = a.types.map((t) => ambulanceTypeLabel(lists, t)).join('/');
  const approx = a.kmApprox || (a as any).distanceApprox;
  const dist = ambulanceKm({ km, kmApprox: !!approx }) ?? '';
  const badge: Badge | null = (a.busySince ?? 0) > 0 ? { text: LT.onTrip() } : a.available ? { text: LT.available(), tone: 'good' } : { text: LT.callToCheck() }; // bn-ok
  return {
    id: a.id, kind: 'ambulance', name: a.name, icon: 'ambulance', verified: vf(a),
    keyLine: facts([types, dist, ratingText(rating)]),
    badges: badge ? [badge] : [], stock: null, rating, ratingRow: false, phone: a.phone,
    openNow: (a.busySince ?? 0) > 0 ? false : a.available ? true : null, distanceKm: km,
    search: join([a.name, a.area, areaLabel(a.district, a.upazilaId), types], ' '),
    address: join([a.area, areaLabel(a.district, a.upazilaId)], ', '),
  };
}
export function ambulanceDetail(a: Ambulance, lists: Lists | null): { facts: DLine[]; sections: DSection[] } {
  const now = (a.busySince ?? 0) > 0 ? tr('On a trip since ', 'ট্রিপে গেছে ') + timeAgo(a.busySince!)
    : a.available && a.availableAt > 0 ? tr('Marked available ', 'খালি জানিয়েছেন ') + timeAgo(a.availableAt) : tr('Call to check if it is free', 'খালি আছে কিনা ফোনে জেনে নিন');
  return {
    facts: [['schedule', now], ['payments', a.fare ? tr('Fare: ', 'ভাড়া: ') + a.fare : '']],
    sections: [{ title: tr('Vehicle', 'গাড়ি'), lines: a.types.map((t) => ['ambulance', ambulanceTypeLabel(lists, t)] as DLine) }],
  };
}

const closedUntilText = (ms: number) => tr('Closed until ', 'বন্ধ থাকবে ') + formatDate(ms) + tr('', ' পর্যন্ত');

export function pharmacyListing(p: Pharmacy): Listing {
  const open = openNowOf(p);
  const km = distanceOf(p);
  const delivery = typeof p.delivery === 'boolean' ? p.delivery || !!p.online : null;
  const badges: Badge[] = [];
  if ((p as any).is24h ?? p.open24) badges.push({ text: LT.h24(), tone: 'primary' });
  if (p.discount > 0) badges.push({ text: LT.discount(p.discount), tone: 'info' });
  return {
    id: p.id, kind: 'pharmacy', name: p.name, icon: 'storefront', verified: vf(p),
    keyLine: facts([openText(open), distanceText(km, distanceApproxOf(p)), delivery == null ? '' : LT.delivery(delivery)]),
    badges, stock: null, rating: ratingOf(p), ratingRow: true, phone: p.phone, openNow: open, distanceKm: km,
    search: join([p.name, p.address, areaLabel(p.district, p.upazilaId)], ' '),
    address: join([p.address, areaLabel(p.district, p.upazilaId)], ', '),
  };
}
export function pharmacyDetail(p: Pharmacy, online: boolean): { facts: DLine[]; sections: DSection[]; ratingNote: string } {
  const discount = join([flatDiscountText(p), discountTermsText(p)], ' · ');
  const delivery = deliveryText(p, online);
  return {
    facts: [['payments', discount || tr('No discount', 'ছাড় নেই')], ['local_shipping', delivery || tr('No home delivery', 'বাসায় ডেলিভারি নেই')]],
    sections: [{
      title: tr('Open hours', 'খোলার সময়'),
      lines: [['schedule', p.closedUntil > 0 ? closedUntilText(p.closedUntil) : hoursText(p)], ['calendar_month', closedDaysText(p.closedDays)]],
    }],
    ratingNote: aspectsText(p.aspects),
  };
}

export function bloodBankListing(b: BloodBank): Listing {
  const open = openNowOf(b);
  const km = distanceOf(b);
  return {
    id: b.id, kind: 'bloodbank', name: b.name, icon: 'bloodtype', tint: 'error', verified: vf(b),
    keyLine: facts([distanceText(km, distanceApproxOf(b)), openText(open)]),
    badges: (b as any).is24h ?? b.open24 ? [{ text: LT.h24(), tone: 'primary' }] : [],
    stock: stockOf(b), rating: ratingOf(b), ratingRow: true, phone: b.phone, openNow: open, distanceKm: km,
    search: join([b.name, b.address, areaLabel(b.district, b.upazilaId)], ' '),
    address: join([b.address, areaLabel(b.district, b.upazilaId)], ', '),
  };
}
export function bloodBankDetail(b: BloodBank, lists: Lists | null): { facts: DLine[]; sections: DSection[] } {
  return {
    facts: [['info', bloodBankKind(b.kind)], ['info', tr('Buying or selling blood is illegal; only processing costs apply.', 'রক্ত কেনা-বেচা অবৈধ; শুধু পরীক্ষা ও প্রসেসিং খরচ লাগে।')]],
    sections: [
      { title: tr('Available here', 'যা পাওয়া যায়'), lines: b.services.map((s) => ['bloodtype', bloodBankService(lists, s)] as DLine) },
      { title: tr('Open hours', 'খোলার সময়'), lines: [['schedule', hoursText(b)], ['calendar_month', closedDaysText(b.closedDays)]] },
    ],
  };
}

export function labListing(l: Lab): Listing {
  const open = openNowOf(l);
  const km = distanceOf(l);
  const p = l.prices ?? null;
  return {
    id: l.id, kind: 'lab', name: l.name, icon: 'local_hospital', verified: vf(l),
    keyLine: facts([openText(open), distanceText(km, distanceApproxOf(l)), l.homeCollection ? LT.homeSample() : '']),
    badges: p?.discountPct ? [{ text: LT.discount(p.discountPct), tone: 'info' }] : [],
    stock: null, rating: ratingOf(l), ratingRow: true, phone: l.phone, openNow: open, distanceKm: km,
    search: join([l.name, l.address, areaLabel(l.district, l.upazilaId)], ' '),
    address: join([l.address, areaLabel(l.district, l.upazilaId)], ', '),
  };
}
/** full: lab_prices_public (undefined = আসছে, null = তালিকা নেই) */
export function labDetail(l: Lab, full: PublicPrices | null | undefined): { facts: DLine[]; sections: DSection[] } {
  const p = l.prices ?? null;
  return {
    facts: [['home_health', l.homeCollection ? tr('Collects samples from home', 'বাসা থেকে নমুনা নেয়') : ''], ['payments', p ? discountText(p.discountPct) : ''], ['home_health', l.homeCollection && p ? homeFeeText(p.homeFee) : '']],
    sections: [
      {
        title: tr('Tests & prices', 'পরীক্ষা ও দাম'),
        lines: p ? [['sync', full === undefined ? tr('Loading the price list…', 'দামের তালিকা আসছে…') : '']] : [['call', NO_PRICE()]],
        rows: p && full ? full.items.map((i) => ({ name: i.name, value: taka(i.price), sub: i.tat ? tr('Report: ', 'রিপোর্ট: ') + i.tat : '' })) : undefined,
      },
      { title: tr('Hours', 'সময়'), lines: [['schedule', l.hours]] },
    ],
  };
}

export function listingOf(kind: Kind, x: any, lists: Lists | null): Listing {
  return kind === 'ambulance' ? ambulanceListing(x, lists) : kind === 'pharmacy' ? pharmacyListing(x) : kind === 'bloodbank' ? bloodBankListing(x) : labListing(x);
}
