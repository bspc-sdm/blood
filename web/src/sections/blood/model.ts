// রক্তের রিকোয়েস্ট আর ডোনারের সাড়া — অ্যাপের Model.kt (BloodRequest, DonorResponse)-এর মতো হিসাব
import { DAY_MS, DHAKA_OFFSET_MS } from '@/lib/rules';
import { formatNeeded, todayUtcMidnight } from '@/lib/format';
import { districtLabel } from '@/lib/geo';
import { num, tr } from '@/lib/i18n';
import { TEST_MODE } from '@/lib/testmode';

export interface BloodRequest {
  id: string;
  requesterUid: string;
  requesterName: string;
  patientGroup: string;
  bags: number;
  hospital: string;
  placeName: string;
  lat: number;
  lng: number;
  neededByMillis: number;
  neededAtMillis: number | null;
  urgent: boolean;
  travelFare: boolean;
  note: string;
  status: string;
  createdAtMillis: number;
  serverCreatedMillis: number;
  notified: boolean;
  notifiedCount: number;
  fulfilled: boolean;
  acceptedCount: number;
  bagsReceived: number;
  confirmedCount: number;
  closedAtMillis: number;
  closedBy: string;
  notifyRounds: number;
  lastNotifiedMillis: number;
  /** অতিথির তালিকা থেকে (নাম, নোট নেই) */
  guest?: boolean;
}

export interface DonorResponse {
  donorUid: string;
  donorName: string;
  donorPhone: string;
  donorGroup: string;
  /** accepted, cancelled, declined, donated */
  status: string;
  confirmed: boolean;
  distanceKm: number | null;
  fareAfterKm: number | null;
  createdAtMillis: number;
}

const HOUR = 60 * 60 * 1000;

/** পরীক্ষার ডেটায় "+5h", "-30m", "+1d" = এখন থেকে কত পরে/আগে (স্ক্রিনশট যেন সবসময় চালু রিকোয়েস্ট দেখায়) */
function relative(v: string): number | null {
  const m = /^([+-]\d+(?:\.\d+)?)([mhd])$/.exec(v.trim());
  if (!m) return null;
  const n = parseFloat(m[1]);
  const unit = m[2] === 'm' ? 60000 : m[2] === 'h' ? HOUR : DAY_MS;
  return Date.now() + n * unit;
}

/** সংখ্যা, Firestore Timestamp, বা (পরীক্ষায়) "+5h" → মিলিসেকেন্ড */
export function millis(v: unknown): number {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (v && typeof v === 'object' && typeof (v as { toMillis?: () => number }).toMillis === 'function') return (v as { toMillis: () => number }).toMillis();
  if (TEST_MODE && typeof v === 'string') return relative(v) ?? 0;
  return 0;
}
/** তারিখ (UTC মধ্যরাত); পরীক্ষায় "+1d" = আজ থেকে ১ দিন পর */
function dateMillis(v: unknown): number {
  if (TEST_MODE && typeof v === 'string') {
    const m = /^([+-]\d+)d$/.exec(v.trim());
    if (m) return todayUtcMidnight() + parseInt(m[1], 10) * DAY_MS;
  }
  return millis(v);
}
const int = (v: unknown, d = 0) => (typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : d);
const str = (v: unknown) => (typeof v === 'string' ? v : '');

/** Firestore-এর requests/{id} → BloodRequest (অ্যাপের RequestRepository.fromSnap) */
export function requestFrom(id: string, x: Record<string, any>): BloodRequest {
  const neededAt = x.neededAtMillis == null ? null : millis(x.neededAtMillis) || null;
  let neededBy = dateMillis(x.neededByMillis);
  // পরীক্ষায় শুধু সময় দেওয়া থাকলে তারিখ সেই দিনের
  if (!neededBy && neededAt) {
    const d = new Date(neededAt + DHAKA_OFFSET_MS);
    neededBy = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  }
  const notified = x.notifiedAt != null;
  return {
    id,
    requesterUid: str(x.requesterUid),
    requesterName: str(x.requesterName),
    patientGroup: str(x.patientGroup),
    bags: int(x.bags, 1) || 1,
    hospital: str(x.hospital),
    placeName: str(x.placeName),
    lat: typeof x.lat === 'number' ? x.lat : 0,
    lng: typeof x.lng === 'number' ? x.lng : 0,
    neededByMillis: neededBy,
    neededAtMillis: neededAt,
    urgent: x.urgent === true,
    travelFare: x.travelFare === true,
    note: str(x.note),
    status: str(x.status) || 'open',
    createdAtMillis: millis(x.createdAtMillis),
    serverCreatedMillis: millis(x.createdAt) || millis(x.createdAtMillis),
    notified,
    notifiedCount: Math.max(int(x.notifiedCount), 0),
    fulfilled: x.fulfilled === true,
    acceptedCount: Math.max(int(x.acceptedCount), 0),
    bagsReceived: Math.max(int(x.bagsReceived), 0),
    confirmedCount: Math.max(int(x.confirmedCount), 0),
    closedAtMillis: millis(x.closedAt),
    closedBy: str(x.closedBy),
    notifyRounds: x.notifyRounds != null ? int(x.notifyRounds) : notified ? 1 : 0,
    lastNotifiedMillis: millis(x.lastNotifiedAt) || millis(x.notifiedAt),
  };
}

/** অতিথির তালিকার একটা ঘর (server/lib/guest.js requestView) */
export function requestFromGuest(x: Record<string, any>): { r: BloodRequest; km: number } {
  const r = requestFrom(str(x.id), x);
  r.guest = true;
  return { r, km: typeof x.km === 'number' ? x.km : 0 };
}

export function responseFrom(id: string, x: Record<string, any>): DonorResponse {
  return {
    donorUid: str(x.donorUid) || id,
    donorName: str(x.donorName),
    donorPhone: str(x.donorPhone),
    donorGroup: str(x.donorGroup),
    status: str(x.status) || 'accepted',
    confirmed: x.confirmed === true,
    distanceKm: typeof x.distanceKm === 'number' ? Math.round(x.distanceKm) : null,
    fareAfterKm: typeof x.fareAfterKm === 'number' ? Math.round(x.fareAfterKm) : null,
    createdAtMillis: millis(x.createdAtMillis),
  };
}

// ---------- হিসাব (Model.kt-এর মতো) ----------

/** কতক্ষণ খোলা থাকে: সময় দেওয়া থাকলে তার ৬ ঘণ্টা পর, নইলে দিনের শেষে */
export const activeUntil = (r: BloodRequest) => (r.neededAtMillis != null ? r.neededAtMillis + 6 * HOUR : r.neededByMillis + DAY_MS);
/** কাউন্টডাউন যে মুহূর্ত পর্যন্ত */
export const deadline = (r: BloodRequest) => r.neededAtMillis ?? r.neededByMillis + DAY_MS - DHAKA_OFFSET_MS;
export const isActive = (r: BloodRequest, now = Date.now()) => r.status === 'open' && activeUntil(r) > now;
export const bagsLeft = (r: BloodRequest) => Math.max(r.bags - Math.max(r.confirmedCount, r.bagsReceived), 0);
/** ভুল করে বন্ধ — ১ ঘণ্টার মধ্যে আবার খোলা যায় */
export const canReopen = (r: BloodRequest, now = Date.now()) =>
  r.status === 'closed' && ['requester', 'not_needed'].includes(r.closedBy) && now - r.closedAtMillis < HOUR && activeUntil(r) > now;

export const neededText = (r: BloodRequest) => formatNeeded(r.neededByMillis, r.neededAtMillis);

/** রাজি থাকা বা রক্ত দেওয়া ডোনার */
export const willing = (list: DonorResponse[]) => list.filter((d) => d.status === 'accepted' || d.status === 'donated');
/** নিশ্চিত বা রক্ত দেওয়া — ব্যাগ পূরণের হিসাবে ধরা হয় */
export const counts = (d: DonorResponse) => d.status === 'donated' || (d.status === 'accepted' && d.confirmed);
export const needsFare = (d: DonorResponse) => d.fareAfterKm != null && (d.distanceKm ?? 0) > d.fareAfterKm;

/** শেয়ারের লিংক: এই ঠিকানারই /r/{id} পাতা */
export const shareLink = (id: string) => `${window.location.origin}/r/${id}`;

/** শেয়ারের লেখা (অ্যাপের shareRequest) */
export function shareText(r: BloodRequest): string {
  const noDonor = r.acceptedCount === 0 && !r.fulfilled;
  const place = districtLabel(r.placeName);
  const link = shareLink(r.id);
  return tr(
    `${r.urgent ? 'URGENT: ' : ''}${noDonor ? 'No donor yet! ' : ''}${r.patientGroup} blood needed — ${r.bags} bag(s) at ${r.hospital}, ${place}. ` +
      `Needed by ${neededText(r)}.${r.travelFare ? ' Travel cost will be paid.' : ''} Can you help or share? ${link}`,
    `${r.urgent ? 'জরুরি: ' : ''}${noDonor ? 'এখনো কোনো ডোনার পাওয়া যায়নি! ' : ''}${r.patientGroup} রক্ত দরকার — ${num(r.bags)} ব্যাগ, ${r.hospital}, ${place}। ` +
      `লাগবে ${neededText(r)}।${r.travelFare ? ' যাতায়াত খরচ দেওয়া হবে।' : ''} দিতে পারলে বা কাউকে চিনলে সাহায্য করুন: ${link}`,
  );
}

/** হাসপাতালের নাম Title Case (অ্যাপের TitleCase.name) */
const MINOR = new Set(['a', 'an', 'the', 'and', 'of', 'in', 'on', 'at', 'for', 'to', 'by', 'with']);
export function nameCase(s: string): string {
  const parts = s.trim().replace(/\s+/g, ' ').split(' ');
  return parts
    .map((w, i) => {
      const k = w.search(/\p{L}/u);
      if (k < 0) return w;
      const ch = w[k];
      if (ch === ch.toUpperCase()) return w;
      if (i !== 0 && i !== parts.length - 1 && MINOR.has(w.toLowerCase())) return w;
      if (/\d/.test(w.slice(0, k))) return w;
      return w.slice(0, k) + ch.toUpperCase() + w.slice(k + 1);
    })
    .join(' ');
}
