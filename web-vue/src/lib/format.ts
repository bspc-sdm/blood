// তারিখ, সময়, দূরত্ব — অ্যাপের Rules.kt-এর মতো (সময় ঢাকার, তারিখ UTC মধ্যরাত ধরে)
import { getLang, num, tr } from './i18n';

const BN_MONTHS = ['জানুয়ারি', 'ফেব্রুয়ারি', 'মার্চ', 'এপ্রিল', 'মে', 'জুন', 'জুলাই', 'আগস্ট', 'সেপ্টেম্বর', 'অক্টোবর', 'নভেম্বর', 'ডিসেম্বর'];
const EN_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DHAKA_MS = 6 * 3600 * 1000;

/** "১৭ অক্টোবর ২০২৬" — তারিখ UTC মধ্যরাত হিসেবে রাখা (অ্যাপের মতো) */
export function formatDate(millis: number): string {
  if (!millis) return '';
  const d = new Date(millis);
  const day = d.getUTCDate();
  const m = d.getUTCMonth();
  const y = d.getUTCFullYear();
  return getLang() === 'bn' ? `${num(day)} ${BN_MONTHS[m]} ${num(y)}` : `${day} ${EN_MONTHS[m]} ${y}`;
}

/** আসল সময়ের (মিলিসেকেন্ড) ঢাকার তারিখ */
export function formatDayOf(millis: number): string {
  return formatDate(millis + DHAKA_MS);
}

export function dhakaHourMinute(millis: number): [number, number] {
  const d = new Date(millis + DHAKA_MS);
  return [d.getUTCHours(), d.getUTCMinutes()];
}

/** "বিকাল ৪:০০" / "4:00 PM" */
export function formatTimeHM(hour: number, minute: number): string {
  const h12 = hour % 12 === 0 ? 12 : hour % 12;
  const mm = String(minute).padStart(2, '0');
  if (getLang() !== 'bn') return `${h12}:${mm} ${hour < 12 ? 'AM' : 'PM'}`;
  const part = hour < 5 ? 'রাত' : hour < 12 ? 'সকাল' : hour < 15 ? 'দুপুর' : hour < 18 ? 'বিকাল' : hour < 20 ? 'সন্ধ্যা' : 'রাত';
  return `${part} ${num(h12)}:${num(mm)}`;
}

export function formatTime(millis: number): string {
  const [h, m] = dhakaHourMinute(millis);
  return formatTimeHM(h, m);
}

/** "১৭ অক্টোবর ২০২৬, বিকাল ৪:০০" */
export function formatDateTime(millis: number): string {
  return `${formatDayOf(millis)}, ${formatTime(millis)}`;
}

/** রক্ত কবে লাগবে: তারিখ (+ সময় থাকলে) */
export function formatNeeded(neededByMillis: number, neededAtMillis?: number | null): string {
  return formatDate(neededByMillis) + (neededAtMillis ? `, ${formatTime(neededAtMillis)}` : '');
}

export function formatKm(km: number | null | undefined): string {
  if (km == null || !Number.isFinite(km)) return '';
  return km < 1 ? tr('< 1 km', '১ কিমির কম') : num(Math.round(km)) + tr(' km', ' কিমি');
}

/** বাকি সময়: "০৫ ঘণ্টা ৫৯ মিনিট ৫৯ সেকেন্ড" */
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const two = (v: number) => num(String(v).padStart(2, '0'));
  const parts: string[] = [];
  if (d > 0) parts.push(num(d) + tr('d', ' দিন'));
  if (d > 0 || h > 0) parts.push(two(h) + tr('h', ' ঘণ্টা'));
  parts.push(two(m) + tr('m', ' মিনিট'));
  parts.push(two(s) + tr('s', ' সেকেন্ড'));
  return parts.join(' ');
}

export function timeAgo(millis: number): string {
  const diff = Math.max(0, Date.now() - millis);
  const min = Math.floor(diff / 60000);
  if (min < 1) return tr('just now', 'এইমাত্র');
  if (min < 60) return tr(`${num(min)}m ago`, `${num(min)} মিনিট আগে`);
  if (min < 1440) return tr(`${num(Math.floor(min / 60))}h ago`, `${num(Math.floor(min / 60))} ঘণ্টা আগে`);
  if (min < 1440 * 7) return tr(`${num(Math.floor(min / 1440))}d ago`, `${num(Math.floor(min / 1440))} দিন আগে`);
  return formatDayOf(millis);
}

/** আজকের তারিখ (ঢাকা) UTC মধ্যরাত হিসেবে — তারিখ বাছাইয়ের সাথে মেলাতে */
export function todayUtcMidnight(): number {
  const d = new Date(Date.now() + DHAKA_MS);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}

/** <input type="date">-এর মান ↔ UTC মধ্যরাত */
export function dateInputValue(millis: number | null | undefined): string {
  if (!millis) return '';
  return new Date(millis).toISOString().slice(0, 10);
}
export function fromDateInput(v: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return null;
  const t = Date.parse(v + 'T00:00:00Z');
  return Number.isFinite(t) ? t : null;
}

/** বাংলাদেশি মোবাইল নম্বর: 01XXXXXXXXX */
export function normalizePhone(input: string): string | null {
  let digits = input.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d))).replace(/\D/g, '');
  if (digits.startsWith('88') && digits.length === 13) digits = digits.slice(2);
  return digits.length === 11 && digits.startsWith('01') ? digits : null;
}
