// প্রোফাইল (users/{uid}) — অ্যাপের ProfileRepository-র মতো; Firestore-এর নিয়ম একই
import { deleteField, doc, getDoc, serverTimestamp, setDoc, updateDoc, FieldPath } from 'firebase/firestore';
import { db } from './firebase';
import { geohash, roundCoord } from './geo';
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

export async function loadProfile(uid: string): Promise<Profile | null> {
  const snap = await getDoc(doc(db, 'users', uid));
  if (!snap.exists()) return null;
  const x = snap.data();
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
const ref = (uid: string) => doc(db, 'users', uid);

/** ডোনার খোঁজার ঘর "গ্রুপ|geohash" ঠিক রাখা (না পারলেও সার্ভার রোজ ঠিক করে) */
async function refreshDonorGeo(uid: string) {
  try {
    const s = await getDoc(ref(uid));
    const group = s.get('bloodGroup');
    const hash = s.get('geohash');
    const key = group && BLOOD_GROUPS.includes(group) && hash ? `${group}|${hash}` : null;
    if (s.get('donorGeo') !== key) await setDoc(ref(uid), { donorGeo: key ?? deleteField() }, { merge: true });
  } catch {
    /* সার্ভার পরে ঠিক করে */
  }
}

export async function saveContact(uid: string, name: string, phone: string) {
  await setDoc(ref(uid), { name, phone, updatedAt: serverTimestamp() }, { merge: true });
}

/** ডোনারের তথ্য; শেষ রক্তদান শুধু প্রথমবার (পরে সার্ভার বা "তারিখ বদলান" দিয়ে) */
export async function saveDonor(uid: string, p: Pick<Profile, 'bloodGroup' | 'birthDateMillis' | 'weightKg' | 'lastDonationMillis'>, includeDonation: boolean) {
  const data: Record<string, unknown> = { bloodGroup: p.bloodGroup, birthDateMillis: p.birthDateMillis, weightKg: p.weightKg, updatedAt: serverTimestamp() };
  if (includeDonation && p.lastDonationMillis != null) data.lastDonationMillis = p.lastDonationMillis;
  await setDoc(ref(uid), data, { merge: true });
  await refreshDonorGeo(uid);
}

export async function saveBasics(uid: string, p: Profile, includeDonation: boolean) {
  const data: Record<string, unknown> = {
    name: p.name, phone: p.phone, bloodGroup: p.bloodGroup, birthDateMillis: p.birthDateMillis, weightKg: p.weightKg, notify: p.notify, updatedAt: serverTimestamp(),
  };
  if (includeDonation && p.lastDonationMillis != null) data.lastDonationMillis = p.lastDonationMillis;
  await setDoc(ref(uid), data, { merge: true });
  await refreshDonorGeo(uid);
}

export async function saveLocation(uid: string, lat: number, lng: number, placeName: string, source: 'gps' | 'district') {
  const rLat = roundCoord(lat);
  const rLng = roundCoord(lng);
  await setDoc(ref(uid), { lat: rLat, lng: rLng, geohash: geohash(rLat, rLng), placeName, locationSource: source, locationUpdatedAtMillis: Date.now() }, { merge: true });
  await refreshDonorGeo(uid);
}

export async function saveSettings(uid: string, p: Profile) {
  await setDoc(ref(uid), {
    notify: p.notify,
    maxPushPerDay: Math.min(Math.max(p.maxPushPerDay, 1), MAX_PUSH_PER_DAY_LIMIT),
    notifyGroups: p.notifyGroups,
    viewGroups: p.viewGroups,
    maxKm: p.maxKm,
    fareAfterKm: p.fareAfterKm ?? deleteField(),
  }, { merge: true });
}

export async function saveLang(uid: string, code: string) {
  await setDoc(ref(uid), { lang: code }, { merge: true });
}

export async function savePhoto(uid: string, base64: string | null, thumb: string | null) {
  await setDoc(ref(uid), { photo: base64 ?? deleteField(), photoThumb: thumb ?? deleteField() }, { merge: true });
}

export async function addCommitment(uid: string, requestId: string) {
  await setDoc(ref(uid), { commitments: { [requestId]: Date.now() } }, { merge: true });
}

export async function removeCommitments(uid: string, ids: string[]) {
  if (!ids.length) return;
  const args: unknown[] = [];
  ids.forEach((id) => args.push(new FieldPath('commitments', id), deleteField()));
  const [f, v, ...rest] = args;
  await updateDoc(ref(uid), f as FieldPath, v, ...rest);
}
