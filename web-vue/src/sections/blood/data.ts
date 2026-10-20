// রক্তের রিকোয়েস্টের ডেটা — অ্যাপের RequestRepository আর ServerApi-র মতো। সব সার্ভার দিয়ে
// (server/lib/clientdata.js — কে কী দেখতে পারে সেখানে); আর সরাসরি Firestore নয়।
// পরীক্ষার বিল্ডে (TEST_MODE) সার্ভারের বদলে window.__lfTest-এর docs/queries।
import { call, post } from '@/lib/api';
import { distanceKm } from '@/lib/geo';
import { TEST_MODE, testDoc, testQuery } from '@/lib/testmode';
import { removeCommitments } from '@/lib/profile';
import { useSession } from '@/lib/session';
import { queryClient, useQ } from '@/lib/query';
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

// ---------- সার্ভারের পড়া (আগে সরাসরি Firestore) ----------
export async function getRequest(id: string): Promise<BloodRequest | null> {
  if (TEST_MODE) {
    const x = testFindRequest(id);
    return x ? requestFrom(id, x) : null;
  }
  const { request: x } = await call<{ request: Record<string, any> | null }>('blood_request_get', { requestId: id });
  return x ? requestFrom(str(x.id) || id, x) : null;
}

const str = (v: unknown) => (typeof v === 'string' ? v : '');

export async function myRequests(uid: string): Promise<BloodRequest[]> {
  let list: BloodRequest[];
  if (TEST_MODE) list = (testQuery(`requests?requesterUid=${uid}`) ?? []).map((x) => requestFrom(x.id, x));
  else {
    const o = await call<{ items: Record<string, any>[] }>('blood_requests_mine');
    list = (o.items ?? []).map((x) => requestFrom(str(x.id), x));
  }
  return list.sort((a, b) => b.createdAtMillis - a.createdAtMillis);
}

export interface Nearby { r: BloodRequest; km: number }

/**
 * আশেপাশের খোলা রিকোয়েস্ট (লগইন করা): গ্রুপ মিলিয়ে, নিজেরগুলো বাদ, জরুরি আগে, তারপর কাছেরটা।
 * সার্ভার geoOpen-এর ঘর ধরে খোঁজে (না চললে কাছের জেলা) আর এগুলো ছাঁকে; এখানেও একই ছাঁকনি (পরীক্ষার ডেটার জন্য)।
 */
export async function nearbyOpen(lat: number, lng: number, groups: string[] | null, excludeUid: string | null, maxKm: number): Promise<Nearby[]> {
  let all: BloodRequest[];
  if (TEST_MODE) all = (testQuery('nearby') ?? []).map((x) => requestFrom(x.id, x));
  else {
    const o = await call<{ items: Record<string, any>[] }>('blood_requests_near', { lat, lng, maxKm, groups });
    all = (o.items ?? []).map((x) => requestFrom(str(x.id), x));
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
  const { response: x } = await call<{ response: Record<string, any> | null }>('blood_response_get', { requestId });
  return x ? responseFrom(uid, x) : null;
}

/** শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার */
export async function responses(requestId: string): Promise<DonorResponse[]> {
  let list: DonorResponse[];
  if (TEST_MODE) list = (testQuery(`requests/${requestId}/responses`) ?? []).map((x) => responseFrom(x.donorUid ?? x.id, x));
  else {
    const o = await call<{ items: Record<string, any>[] }>('blood_responses_list', { requestId });
    list = (o.items ?? []).map((x) => responseFrom(str(x.donorUid), x));
  }
  return list.filter((d) => d.status === 'accepted' || d.status === 'donated').sort((a, b) => a.createdAtMillis - b.createdAtMillis);
}

/** রক্তপ্রার্থীর নাম ও ফোন (রক্তপ্রার্থী নিজে বা রাজি ডোনার) */
export async function requesterContact(requestId: string): Promise<{ name: string; phone: string } | null> {
  if (TEST_MODE) {
    const x = testDoc(`requests/${requestId}/private/contact`);
    return x ? { name: x.name ?? '', phone: x.phone ?? '' } : null;
  }
  const { contact } = await call<{ contact: { name?: string; phone?: string } | null }>('blood_contact_get', { requestId });
  if (!contact) return null;
  return { name: contact.name ?? '', phone: contact.phone ?? '' };
}

/** একজন একটা রিকোয়েস্ট একবারই রিপোর্ট করতে পারেন */
export async function reportRequest(requestId: string, _uid: string): Promise<void> {
  if (TEST_MODE) return;
  await call('report_request', { requestId });
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
  // পড়তে ভুল হলে (ইন্টারনেট নেই) কিছুই সরানো হয় না — অ্যাপের মতো ভুলটা বাইরে যায়
  let failed = false;
  await Promise.all(ids.map(async (id) => {
    const r = await getRequest(id).catch(() => {
      failed = true;
      return null;
    });
    if (failed && !r) return;
    const resp = r ? await myResponse(id, uid).catch(() => {
      failed = true;
      return undefined;
    }) : null;
    if (resp === undefined) return;
    const endedAt = !r ? 0 : r.closedAtMillis > 0 ? r.closedAtMillis : activeUntil(r);
    const recentlyEnded = !!r && !isActive(r, now) && now - endedAt < 3 * DAY_MS;
    if (r && resp?.status === 'accepted' && (isActive(r, now) || recentlyEnded)) active.push(r);
    else finished.push(id);
  }));
  if (failed) throw new Error('offline');
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

// ---------- vue-query (React-এর react-query-র একই) ----------
export const KEY = 'blood';

export function useInvalidate() {
  return () => queryClient.invalidateQueries({ queryKey: [KEY] });
}

/** নিজের রিকোয়েস্ট (সব) */
export function useMine() {
  const s = useSession();
  return useQ<BloodRequest[]>(() => ({
    queryKey: [KEY, 'mine', s.uid],
    queryFn: () => myRequests(s.uid!),
    enabled: !!s.uid,
    staleTime: 10_000,
  }));
}

/** নিজের একটা রিকোয়েস্ট আর রাজি ডোনার */
export function useMyRequest(id: () => string) {
  const s = useSession();
  return useQ<{ r: BloodRequest | null; responses: DonorResponse[] }>(() => ({
    queryKey: [KEY, 'myreq', id(), s.uid],
    queryFn: async () => {
      const [r, list] = await Promise.all([getRequest(id()), responses(id()).catch(() => [] as DonorResponse[])]);
      return { r, responses: list };
    },
    enabled: !!s.uid,
    staleTime: 5_000,
  }));
}
