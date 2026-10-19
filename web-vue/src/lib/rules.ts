// অ্যাপের নিয়ম (Rules.kt / server/lib/rules.js-এর সাথে মেলানো)
import { formatDate, todayUtcMidnight } from './format';
import { num, tr } from './i18n';

export const DAY_MS = 24 * 60 * 60 * 1000;
export const DHAKA_OFFSET_MS = 6 * 60 * 60 * 1000;
export const ELIGIBILITY_DAYS = 120;
export const MAX_OPEN_REQUESTS = 2;
export const DEFAULT_RADIUS_KM = 10;
export const RADIUS_CHOICES = [5, 10, 25, 50];
export const FARE_AFTER_CHOICES = [5, 10, 25];
export const DEFAULT_PUSH_PER_DAY = 3;
export const MAX_PUSH_PER_DAY_LIMIT = 10;
export const PUSH_PER_DAY_CHOICES = [1, 2, 3, 5, 10];
export const MIN_AGE = 18;
export const MAX_AGE = 60;
export const MIN_WEIGHT_KG = 50;
export const UNKNOWN_GROUP = 'unknown';
export const BLOOD_GROUPS = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];

const DONORS_FOR: Record<string, string[]> = {
  'O-': ['O-'],
  'O+': ['O+', 'O-'],
  'A-': ['A-', 'O-'],
  'A+': ['A+', 'A-', 'O+', 'O-'],
  'B-': ['B-', 'O-'],
  'B+': ['B+', 'B-', 'O+', 'O-'],
  'AB-': ['AB-', 'A-', 'B-', 'O-'],
  'AB+': BLOOD_GROUPS,
};
export const canDonate = (donorGroup: string, patientGroup: string) => !!DONORS_FOR[patientGroup]?.includes(donorGroup);
/** এই গ্রুপের ডোনার যেসব রোগীর গ্রুপকে দিতে পারেন */
export const patientsFor = (donorGroup: string) => BLOOD_GROUPS.filter((g) => canDonate(donorGroup, g));

export function ageYears(birthDateMillis?: number | null): number | null {
  if (birthDateMillis == null) return null;
  const b = new Date(birthDateMillis);
  const n = new Date(todayUtcMidnight());
  let age = n.getUTCFullYear() - b.getUTCFullYear();
  if (n.getUTCMonth() < b.getUTCMonth() || (n.getUTCMonth() === b.getUTCMonth() && n.getUTCDate() < b.getUTCDate())) age--;
  return age;
}

export const nextEligibleMillis = (last?: number | null) => (last == null ? null : last + ELIGIBILITY_DAYS * DAY_MS);
export function isEligible(last?: number | null, now = Date.now()) {
  const next = nextEligibleMillis(last);
  return next == null || now >= next;
}

/** বাছা তারিখ (UTC মধ্যরাত) + ঢাকার ঘণ্টা-মিনিট → আসল মুহূর্ত */
export const dhakaMillis = (dateUtcMidnight: number, hour: number, minute: number) => dateUtcMidnight + (hour * 60 + minute) * 60000 - DHAKA_OFFSET_MS;

export interface DonorLike { bloodGroup?: string; birthDateMillis?: number | null; weightKg?: number | null; lastDonationMillis?: number | null }

/** এখন রক্ত দিতে পারবেন কি না; না পারলে কারণ */
export function notEligibleReason(p: DonorLike): string | null {
  const age = ageYears(p.birthDateMillis);
  if (age == null || p.weightKg == null) return tr('Add your birth date and weight', 'জন্ম তারিখ আর ওজন দিন');
  if (age < MIN_AGE) return tr(`You must be at least ${MIN_AGE} to donate`, `রক্ত দিতে বয়স কমপক্ষে ${num(MIN_AGE)} বছর হতে হয়`);
  if (age > MAX_AGE) return tr(`Donation is usually not advised after ${MAX_AGE}`, `${num(MAX_AGE)} বছরের বেশি বয়সে রক্তদান সাধারণত করা হয় না`);
  if (p.weightKg < MIN_WEIGHT_KG) return tr(`You must weigh at least ${MIN_WEIGHT_KG} kg`, `ওজন কমপক্ষে ${num(MIN_WEIGHT_KG)} কেজি হতে হয়`);
  if (!isEligible(p.lastDonationMillis)) {
    const d = formatDate(nextEligibleMillis(p.lastDonationMillis) ?? 0);
    return tr(`You can donate again from ${d}`, `${d} থেকে আবার রক্ত দিতে পারবেন`);
  }
  return null;
}

/** রক্তের গ্রুপ দেখানো ("জানি না" হলে "?") */
export const groupLabel = (g: string) => (g === UNKNOWN_GROUP ? tr("Don't know", 'জানি না') : g);
