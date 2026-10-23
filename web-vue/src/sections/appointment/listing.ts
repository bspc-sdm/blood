// ডাক্তারের তালিকা → সেবার এক ছাঁচের কার্ড (src/ui/listing): মূল লাইন "বিশেষজ্ঞতা · চিকিৎসা পদ্ধতি · ফি ৳৫০০",
// ব্যাজ "আজ সিরিয়াল আছে" / "পরের সিরিয়াল: কাল", রেটিং। সার্ভারের নতুন ঘর (nextSlot, feeAmount, specialtyLabel, systemLabel,
// ratingSummary, distanceKm — backend/docs/LISTINGS.md) না এলে চেম্বার থেকে হিসাব।
import { tr } from '@/lib/i18n';
import { systemTag, systemsOn } from '@/lib/practice';
import { LT, distanceOf, facts, feeNumber, nextSlotOf, ratingOf, serialBadge, type Badge, type Listing, type NextSlot } from '@/ui/listing/model';
import { onlineOn } from './online/flags';
import { T as OT } from './online';
import { badgeIcon, chamberLabel, specialtyLabel, today, type Chamber, type Provider } from './model';
import { nextOpenDays } from './doctors';

/** অ্যাপয়েন্টমেন্ট নেওয়া যায় এমন চেম্বারগুলোর (সূচি) */
export const bookableOf = (p: Provider) => (p.appointments ? p.chambers.map((c, i) => (c.appointments ? i : -1)).filter((i) => i >= 0) : []);
/** সিরিয়ালের ফোন — সব চেম্বারে একই হলে সেটা, নইলে প্রথমটা */
export function serialPhoneOf(p: Provider): string {
  const ph = [...new Set(p.chambers.map((c) => c.serialPhone).filter(Boolean))];
  return ph[0] ?? '';
}

/** পুরোনো সার্ভার: অ্যাপয়েন্টমেন্টের চেম্বারের সামনের খোলা দিন থেকে (খালি আছে ধরে) */
function slotFromChambers(p: Provider): (NextSlot & { chamberIndex: number }) | null {
  let best: (NextSlot & { chamberIndex: number }) | null = null;
  for (const i of bookableOf(p)) {
    const d = nextOpenDays(p.chambers[i], 1)[0];
    if (d != null && (!best || d < best.date!)) best = { date: d, label: '', available: true, chamberIndex: i };
  }
  return best;
}

export function doctorListing(p: Provider, raw: any): Listing & { slotChamber: number | null } {
  const ns = nextSlotOf(raw);
  const slot = ns ?? slotFromChambers(p);
  const chIdx = typeof raw?.nextSlot?.chamberIndex === 'number' ? raw.nextSlot.chamberIndex : (slot as any)?.chamberIndex ?? bookableOf(p)[0] ?? 0;
  const ch: Chamber | undefined = p.chambers[chIdx] ?? p.chambers[0];
  const specialty = typeof raw?.specialtyLabel === 'string' && raw.specialtyLabel ? raw.specialtyLabel : p.specialties[0] ? specialtyLabel(p.specialties[0]) : '';
  const system = systemsOn() ? (typeof raw?.systemLabel === 'string' && raw.systemLabel ? raw.systemLabel : systemTag(p.type, p.system)) : null;
  const amount = typeof raw?.feeAmount === 'number' ? raw.feeAmount : ch?.noFee ? 0 : feeNumber(ch?.fee);
  const fee = amount == null ? '' : amount === 0 ? tr('No fee', 'ফি নেই') : LT.fee(amount);
  const badges: Badge[] = [];
  const sb = serialBadge(slot);
  if (sb) badges.push(sb);
  if (onlineOn() && p.chambers.some((c) => c.mode === 'online')) badges.push({ text: OT.online(), tone: 'primary' });
  if (p.homeVisit?.enabled) badges.push({ text: tr('Home visit', 'বাসায় যান'), tone: 'primary' });
  const isToday = !!slot && slot.available && (slot.date === today() || slot.label === 'আজ' || slot.label === 'Today'); // bn-ok
  return {
    id: p.uid, kind: 'doctor', name: p.name, icon: badgeIcon(p.type), photo: p.photo || null, verified: raw?.verified === true,
    keyLine: facts([specialty, system ?? '', fee]),
    badges, stock: null, rating: ratingOf(raw), ratingRow: true, phone: serialPhoneOf(p),
    openNow: isToday ? true : slot ? null : false, distanceKm: distanceOf(raw),
    search: [p.name, p.degrees, ...p.specialties.map(specialtyLabel), ...p.chambers.map((c) => [chamberLabel(c), c.address].join(' '))].join(' '),
    address: '',
    slotChamber: slot ? chIdx : null,
  };
}
