// প্রোফাইল (users/{uid}) — অ্যাপের ProfileRepository-র মতো। সরাসরি Firestore নয়, সার্ভার দিয়ে
// (profile_get / profile_update — server/lib/clientdata.js; নিজের প্রোফাইলই, নিয়ম সেখানে)।
// uid প্যারামিটার আগের মতো থাকে (ডাকার জায়গা বদলায়নি); সার্ভার লগইন করা মানুষের প্রোফাইলই নেয়।
import { call } from './api';
import { roundCoord } from './geo';
import { BLOOD_GROUPS, DEFAULT_PUSH_PER_DAY, DEFAULT_RADIUS_KM, MAX_PUSH_PER_DAY_LIMIT, RADIUS_CHOICES, patientsFor } from './rules';

export interface Profile {
  name: string;
  phone: string;
  bloodGroup: string;
  lastDonationMillis: number | null;
  birthDateMillis: number | null;
  weightKg: number | null;
  lat: number | null;
  lng: number | null;
  placeName: string | null;
  locationSource: string | null;
  notify: boolean;
  maxPushPerDay: number;
  notifyGroups: string[] | null;
  viewGroups: string[] | null;
  commitments: string[];
  photo: string | null;
  maxKm: number;
  fareAfterKm: number | null;
}

export const emptyProfile = (): Profile => ({
  name: '', phone: '', bloodGroup: '', lastDonationMillis: null, birthDateMillis: null, weightKg: null,
  lat: null, lng: null, placeName: null, locationSource: null, notify: true, maxPushPerDay: DEFAULT_PUSH_PER_DAY,
  notifyGroups: null, viewGroups: null, commitments: [], photo: null, maxKm: DEFAULT_RADIUS_KM, fareAfterKm: null,
});

const groupList = (raw: unknown) => (Array.isArray(raw) ? raw.filter((g): g is string => typeof g === 'string' && BLOOD_GROUPS.includes(g)) : null);
const numOrNull = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

export async function loadProfile(_uid: string): Promise<Profile | null> {
  const { profile: x } = await call<{ profile: Record<string, any> | null }>('profile_get');
  if (!x) return null;
  const maxKm = numOrNull(x.maxKm);
  return {
    name: x.name ?? '',
    phone: x.phone ?? '',
    bloodGroup: x.bloodGroup ?? '',
    lastDonationMillis: numOrNull(x.lastDonationMillis),
    birthDateMillis: numOrNull(x.birthDateMillis),
    weightKg: numOrNull(x.weightKg),
    lat: numOrNull(x.lat),
    lng: numOrNull(x.lng),
    placeName: x.placeName ?? null,
    locationSource: x.locationSource ?? null,
    notify: x.notify !== false,
    maxPushPerDay: Math.min(Math.max(numOrNull(x.maxPushPerDay) ?? DEFAULT_PUSH_PER_DAY, 1), MAX_PUSH_PER_DAY_LIMIT),
    notifyGroups: groupList(x.notifyGroups),
    viewGroups: groupList(x.viewGroups),
    commitments: x.commitments && typeof x.commitments === 'object' ? Object.keys(x.commitments) : [],
    photo: x.photo ?? null,
    maxKm: maxKm != null && RADIUS_CHOICES.includes(maxKm) ? maxKm : DEFAULT_RADIUS_KM,
    fareAfterKm: numOrNull(x.fareAfterKm),
  };
}

// ---------- হিসাব ----------
export const compatibleGroups = (p: Profile) => patientsFor(p.bloodGroup);
export const isComplete = (p?: Profile | null) => !!p && !!p.name.trim() && !!p.phone;
export const isDonor = (p?: Profile | null) => !!p && BLOOD_GROUPS.includes(p.bloodGroup) && p.birthDateMillis != null && p.weightKg != null;
export const hasLocation = (p?: Profile | null) => !!p && p.lat != null && p.lng != null;
export function effectiveViewGroups(p: Profile): string[] {
  const v = p.viewGroups?.filter((g) => BLOOD_GROUPS.includes(g));
  if (v && v.length) return v;
  const c = compatibleGroups(p);
  return c.length ? c : BLOOD_GROUPS;
}
export function effectiveNotifyGroups(p: Profile): string[] {
  const c = compatibleGroups(p);
  const n = p.notifyGroups?.filter((g) => c.includes(g));
  return n && n.length ? n : c;
}

// ---------- সেভ ----------
/** নিজের প্রোফাইলে ঘর বসানো (merge); null = খালি, fareAfterKm/photo/photoThumb-এ null = মোছা */
async function update(body: { set?: Record<string, unknown>; commitAdd?: string; commitRemove?: string[] }) {
  await call('profile_update', body);
}

export async function saveContact(_uid: string, name: string, phone: string) {
  await update({ set: { name, phone } });
}

/** ডোনারের তথ্য; শেষ রক্তদান শুধু প্রথমবার (পরে সার্ভার বা "তারিখ বদলান" দিয়ে)। ডোনার খোঁজার ঘর সার্ভার ঠিক করে */
export async function saveDonor(_uid: string, p: Pick<Profile, 'bloodGroup' | 'birthDateMillis' | 'weightKg' | 'lastDonationMillis'>, includeDonation: boolean) {
  const set: Record<string, unknown> = { bloodGroup: p.bloodGroup, birthDateMillis: p.birthDateMillis, weightKg: p.weightKg };
  if (includeDonation && p.lastDonationMillis != null) set.lastDonationMillis = p.lastDonationMillis;
  await update({ set });
}

export async function saveBasics(_uid: string, p: Profile, includeDonation: boolean) {
  const set: Record<string, unknown> = {
    name: p.name, phone: p.phone, bloodGroup: p.bloodGroup, birthDateMillis: p.birthDateMillis, weightKg: p.weightKg, notify: p.notify,
  };
  if (includeDonation && p.lastDonationMillis != null) set.lastDonationMillis = p.lastDonationMillis;
  await update({ set });
}

/** লোকেশন (রাউন্ড করা); geohash আর সময় সার্ভার বসায় */
export async function saveLocation(_uid: string, lat: number, lng: number, placeName: string, source: 'gps' | 'district') {
  await update({ set: { lat: roundCoord(lat), lng: roundCoord(lng), placeName, locationSource: source } });
}

export async function saveSettings(_uid: string, p: Profile) {
  await update({
    set: {
      notify: p.notify,
      maxPushPerDay: Math.min(Math.max(p.maxPushPerDay, 1), MAX_PUSH_PER_DAY_LIMIT),
      notifyGroups: p.notifyGroups,
      viewGroups: p.viewGroups,
      maxKm: p.maxKm,
      fareAfterKm: p.fareAfterKm ?? null,
    },
  });
}

export async function saveLang(_uid: string, code: string) {
  await update({ set: { lang: code } });
}

export async function savePhoto(_uid: string, base64: string | null, thumb: string | null) {
  await update({ set: { photo: base64, photoThumb: thumb } });
}

export async function addCommitment(_uid: string, requestId: string) {
  await update({ commitAdd: requestId });
}

export async function removeCommitments(_uid: string, ids: string[]) {
  for (let i = 0; i < ids.length; i += 100) await update({ commitRemove: ids.slice(i, i + 100) });
}
