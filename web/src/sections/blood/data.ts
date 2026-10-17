// রক্তের রিকোয়েস্টের ডেটা — অ্যাপের RequestRepository (Firestore) আর ServerApi (সার্ভার)-এর মতো।
// পরীক্ষার বিল্ডে (TEST_MODE) Firestore-এর বদলে window.__lfTest-এর docs/queries।
import {
  collection, doc, endAt, getDoc, getDocs, limit, orderBy, query, setDoc, startAt, where,
} from 'firebase/firestore';
import { geohashQueryBounds } from 'geofire-common';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { db } from '@/lib/firebase';
import { call, post } from '@/lib/api';
import { DISTRICTS, distanceKm } from '@/lib/geo';
import { TEST_MODE, testDoc, testQuery } from '@/lib/testmode';
import { removeCommitments } from '@/lib/profile';
import { useSession } from '@/lib/session';
import { DAY_MS } from '@/lib/rules';
import {
  activeUntil, deadline, isActive, requestFrom, requestFromGuest, responseFrom,
  type BloodRequest, type DonorResponse,
} from './model';

// ---------- পরীক্ষার ডেটা ----------
function testFindRequest(id: string): Record<string, any> | undefined {
  const d = testDoc(`requests/${id}`);
  if (d) return d;
  const qs = window.__lfTest?.queries ?? {};
  for (const k of Object.keys(qs)) {
    const hit = qs[k]?.find((x: any) => x && x.id === id && x.patientGroup);
    if (hit) return hit;
  }
  return undefined;
}

// ---------- Firestore ----------
export async function getRequest(id: string): Promise<BloodRequest | null> {
  if (TEST_MODE) {
    const x = testFindRequest(id);
    return x ? requestFrom(id, x) : null;
  }
  const s = await getDoc(doc(db, 'requests', id));
  return s.exists() ? requestFrom(s.id, s.data()) : null;
}

export async function myRequests(uid: string): Promise<BloodRequest[]> {
  let list: BloodRequest[];
  if (TEST_MODE) list = (testQuery(`requests?requesterUid=${uid}`) ?? []).map((x) => requestFrom(x.id, x));
  else {
    const s = await getDocs(query(collection(db, 'requests'), where('requesterUid', '==', uid)));
    list = s.docs.map((d) => requestFrom(d.id, d.data()));
  }
  return list.sort((a, b) => b.createdAtMillis - a.createdAtMillis);
}

/** কাছের জেলা (কেন্দ্র ৭০ কিমির মধ্যে, সর্বোচ্চ ৩০টা) — geohash না চললে */
function nearDistricts(lat: number, lng: number): string[] {
  const l = DISTRICTS.map((d) => [d, distanceKm(lat, lng, d.lat, d.lng)] as const)
    .filter((x) => x[1] <= 70)
    .sort((a, b) => a[1] - b[1])
    .slice(0, 30)
    .map((x) => x[0].bn);
  if (l.length) return l;
  return [DISTRICTS.reduce((b, d) => (distanceKm(lat, lng, d.lat, d.lng) < distanceKm(lat, lng, b.lat, b.lng) ? d : b)).bn];
}

async function nearbyByGeohash(lat: number, lng: number, maxKm: number): Promise<BloodRequest[]> {
  const bounds = geohashQueryBounds([lat, lng], maxKm * 1000);
  const seen = new Set<string>();
  const out: BloodRequest[] = [];
  const snaps = await Promise.all(
    bounds.map(([a, b]) => getDocs(query(collection(db, 'requests'), orderBy('geoOpen'), startAt(a), endAt(b), limit(500)))),
  );
  snaps.forEach((s) => s.docs.forEach((d) => {
    if (!seen.has(d.id)) {
      seen.add(d.id);
      out.push(requestFrom(d.id, d.data()));
    }
  }));
  return out.filter((r) => r.status === 'open');
}

export interface Nearby { r: BloodRequest; km: number }

/** আশেপাশের খোলা রিকোয়েস্ট (লগইন করা): গ্রুপ মিলিয়ে, নিজেরগুলো বাদ, জরুরি আগে, তারপর কাছেরটা */
export async function nearbyOpen(lat: number, lng: number, groups: string[] | null, excludeUid: string | null, maxKm: number): Promise<Nearby[]> {
  let all: BloodRequest[];
  if (TEST_MODE) all = (testQuery('nearby') ?? []).map((x) => requestFrom(x.id, x));
  else {
    try {
      all = await nearbyByGeohash(lat, lng, maxKm);
    } catch {
      const s = await getDocs(query(collection(db, 'requests'), where('status', '==', 'open'), where('placeName', 'in', nearDistricts(lat, lng))));
      all = s.docs.map((d) => requestFrom(d.id, d.data()));
    }
  }
  const now = Date.now();
  return all
    .filter((r) => isActive(r, now) && r.requesterUid !== excludeUid && (!groups || groups.includes(r.patientGroup)))
    .map((r) => ({ r, km: distanceKm(lat, lng, r.lat, r.lng) }))
    .filter((x) => x.km <= maxKm)
    .sort((a, b) => Number(b.r.urgent) - Number(a.r.urgent) || a.km - b.km);
}

export async function myResponse(requestId: string, uid: string): Promise<DonorResponse | null> {
  if (TEST_MODE) {
    const x = testDoc(`requests/${requestId}/responses/${uid}`);
    return x ? responseFrom(uid, x) : null;
  }
  const s = await getDoc(doc(db, 'requests', requestId, 'responses', uid));
  return s.exists() ? responseFrom(s.id, s.data()) : null;
}

/** শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার */
export async function responses(requestId: string): Promise<DonorResponse[]> {
  let list: DonorResponse[];
  if (TEST_MODE) list = (testQuery(`requests/${requestId}/responses`) ?? []).map((x) => responseFrom(x.donorUid ?? x.id, x));
  else {
    const s = await getDocs(query(collection(db, 'requests', requestId, 'responses'), where('status', 'in', ['accepted', 'donated'])));
    list = s.docs.map((d) => responseFrom(d.id, d.data()));
  }
  return list.filter((d) => d.status === 'accepted' || d.status === 'donated').sort((a, b) => a.createdAtMillis - b.createdAtMillis);
}

/** রক্তপ্রার্থীর নাম ও ফোন (রক্তপ্রার্থী নিজে বা রাজি ডোনার) */
export async function requesterContact(requestId: string): Promise<{ name: string; phone: string } | null> {
  if (TEST_MODE) {
    const x = testDoc(`requests/${requestId}/private/contact`);
    return x ? { name: x.name ?? '', phone: x.phone ?? '' } : null;
  }
  const s = await getDoc(doc(db, 'requests', requestId, 'private', 'contact'));
  if (!s.exists()) return null;
  return { name: s.get('name') ?? '', phone: s.get('phone') ?? '' };
}

/** একজন একটা রিকোয়েস্ট একবারই রিপোর্ট করতে পারেন */
export async function reportRequest(requestId: string, uid: string): Promise<void> {
  if (TEST_MODE) return;
  await setDoc(doc(db, 'reports', `${requestId}_${uid}`), { requestId, reporterUid: uid, createdAtMillis: Date.now() });
}

export async function dropCommitments(uid: string, ids: string[]) {
  if (TEST_MODE || !ids.length) return;
  await removeCommitments(uid, ids);
}

/** ডোনারের "প্রসেসিং" তালিকা: যেসব রিকোয়েস্টে এখনো রাজি; শেষ হওয়াগুলো (৩ দিন পরে) প্রোফাইল থেকে সরানো */
export async function processing(uid: string, ids: string[]): Promise<BloodRequest[]> {
  const active: BloodRequest[] = [];
  const finished: string[] = [];
  const now = Date.now();
  await Promise.all(ids.map(async (id) => {
    const r = await getRequest(id).catch(() => null);
    const resp = r ? await myResponse(id, uid).catch(() => null) : null;
    const endedAt = !r ? 0 : r.closedAtMillis > 0 ? r.closedAtMillis : activeUntil(r);
    const recentlyEnded = !!r && !isActive(r, now) && now - endedAt < 3 * DAY_MS;
    if (r && resp?.status === 'accepted' && (isActive(r, now) || recentlyEnded)) active.push(r);
    else finished.push(id);
  }));
  dropCommitments(uid, finished).catch(() => {});
  return active.sort((a, b) => deadline(a) - deadline(b));
}

// ---------- সার্ভার ----------
export const api = {
  guestRequests: (lat: number, lng: number, maxKm: number) =>
    call<{ items: any[] }>('guest_requests', { lat, lng, maxKm }).then((o) => (o.items ?? []).map(requestFromGuest)),
  respond: (requestId: string) => post('/api/respond', { requestId }),
  /** ডোনার "cancel"/"donated"; রক্তপ্রার্থী "remove"/"restore" (donorUid সহ) */
  updateResponse: (requestId: string, op: string, donorUid?: string) =>
    call('response_update', { requestId, op, ...(donorUid ? { donorUid } : {}) }),
  confirmDonor: (requestId: string, donorUid: string) => call('confirm_donor', { requestId, donorUid }),
  closeRequest: (requestId: string, donatedUids: string[], bagsReceived: number) => post('/api/close-request', { requestId, donatedUids, bagsReceived }),
  deleteRequest: (requestId: string) => call<{ notified?: number }>('request_delete', { requestId }).then((o) => o.notified ?? 0),
  reopen: (requestId: string) => call('request_reopen', { requestId }),
  notify: (requestId: string, again = false) => post<{ sent?: number }>('/api/notify-request', { requestId, again }).then((o) => o.sent ?? 0),
  edit: (body: Record<string, unknown>) => post<{ notified?: number }>('/api/edit-request', body).then((o) => o.notified ?? 0),
  create: (body: Record<string, unknown>) => call<{ id: string }>('request_create', body).then((o) => o.id),
};

// ---------- react-query ----------
export const KEY = 'blood';

export function useInvalidate() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: [KEY] });
}

/** নিজের রিকোয়েস্ট (সব) */
export function useMine() {
  const { uid } = useSession();
  return useQuery({
    queryKey: [KEY, 'mine', uid],
    queryFn: () => myRequests(uid!),
    enabled: !!uid,
    staleTime: 10_000,
  });
}

/** নিজের একটা রিকোয়েস্ট আর রাজি ডোনার */
export function useMyRequest(id: string) {
  const { uid } = useSession();
  return useQuery({
    queryKey: [KEY, 'myreq', id, uid],
    queryFn: async () => {
      const [r, list] = await Promise.all([getRequest(id), responses(id).catch(() => [] as DonorResponse[])]);
      return { r, responses: list };
    },
    enabled: !!uid,
    staleTime: 5_000,
  });
}
