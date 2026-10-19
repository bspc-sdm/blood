// অ্যাম্বুলেন্স ডাকার জায়গা (২.৩১.০) — সার্ভারের lib/ambplace.js-এর ওয়েব রূপ (একই নিয়ম):
// Google Maps-এর লিংক/লেখা থেকে বিন্দু, "Google ম্যাপে দেখুন"-এর লিংক, বিন্দু থেকে উপজেলা (কাছের উপজেলা-কেন্দ্র)।
// সব বিনামূল্যে: লিংক খোলা মানে শুধু নতুন ট্যাবে Google Maps — কোনো API key নয়, কিছু ফেরত আসে না।
import { distanceKm, ensureAreas, upazilasOf } from '@/lib/geo';

export interface Point { lat: number; lng: number }

/** "Google ম্যাপে দেখুন" — বিন্দুটা Google Maps-এ (ব্রাউজারের নতুন ট্যাবে / ফোনে Google Maps অ্যাপে) */
export const googleMapsUrl = (p: Point) => `https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lng}`;

const inBd = (lat: number, lng: number) => lat >= 20.3 && lat <= 26.8 && lng >= 87.9 && lng <= 92.8;
const NUM = '(-?\\d{1,3}(?:\\.\\d+)?)';
// আগে আসল জায়গা (!3d…!4d…), তারপর q/query/ll…=lat,lng, /search|place/lat,lng, শেষে @lat,lng (ম্যাপের মাঝখান)
const PATTERNS = [
  new RegExp(`!3d${NUM}!4d${NUM}`),
  new RegExp(`[?&](?:q|query|ll|center|destination|daddr|sll)=(?:loc:)?${NUM}\\s*,\\s*\\+?${NUM}`),
  new RegExp(`/(?:search|place|dir)/${NUM}\\s*,\\s*\\+?${NUM}`),
  new RegExp(`@${NUM},${NUM}`),
  new RegExp(`\\bgeo:${NUM},${NUM}`),
];
const PLAIN = new RegExp(`(?:^|[^\\d.])${NUM}\\s*,\\s*${NUM}(?![\\d.])`);
const SHORT_RE = /https?:\/\/(?:maps\.app\.goo\.gl|goo\.gl\/maps)\/[A-Za-z0-9_\-/?=&.%]+/i;
/** লেখায় Google Maps-এর লিংক আছে কি না (লেখার ঘরে পেস্ট ধরা) */
export const MAPS_LINK_RE = /https?:\/\/(?:maps\.app\.goo\.gl|goo\.gl\/maps|(?:www\.|maps\.)?google\.com(?:\.bd)?\/maps|maps\.google\.com)\S*/i;

function decode(s: string): string {
  let out = s;
  for (let i = 0; i < 2; i++) {
    try {
      out = decodeURIComponent(out.replace(/\+/g, ' '));
    } catch {
      break;
    }
  }
  return out;
}

/** শেয়ার/পেস্ট করা লেখা → { lat, lng } বা { short } (সার্ভারে খুলতে হবে) বা null */
export function parseMapsText(text: string): Point | { short: string } | null {
  const s = String(text || '').slice(0, 2000);
  if (!s.trim()) return null;
  const t = decode(s);
  for (const re of [...PATTERNS, PLAIN]) {
    const m = t.match(re);
    if (!m) continue;
    const lat = Number(m[1]);
    const lng = Number(m[2]);
    if (Number.isFinite(lat) && Number.isFinite(lng) && inBd(lat, lng)) return { lat: Math.round(lat * 1e5) / 1e5, lng: Math.round(lng * 1e5) / 1e5 };
  }
  const short = s.match(SHORT_RE);
  return short ? { short: short[0] } : null;
}

// উপজেলার কেন্দ্র (public/data/upazila_centers.json — scripts/build-upazila-centers.js), দরকার হলে একবার আনা
let centers: { c: Record<string, [number, number]>; cc: Record<string, [number, number][]> } | null = null;
let loading: Promise<void> | null = null;
function loadCenters(): Promise<void> {
  if (centers) return Promise.resolve();
  loading ??= fetch('/w/data/upazila_centers.json').then((r) => r.json()).then((j) => { centers = j; }, () => { loading = null; });
  return loading;
}

/**
 * বিন্দু থেকে জেলার ভেতরের উপজেলা (সবচেয়ে কাছের উপজেলা-কেন্দ্র, ৩০ কিমির ভেতরে); শহরের থানায় (কেন্দ্র নেই) বা
 * না পারলে null — তখন বেছে নেওয়া উপজেলা থাকে। সার্ভারও একই নিয়মে (উপজেলা না পাঠালে)।
 */
export async function nearestUpazila(district: string, p: Point): Promise<number | null> {
  await Promise.all([loadCenters(), ensureAreas()]).catch(() => {});
  if (!centers) return null;
  const ups = upazilasOf(district);
  let best: number | null = null;
  let bestKm = Infinity;
  for (const u of ups) {
    const c = centers.c[u.id];
    if (!c) continue;
    const km = distanceKm(p.lat, p.lng, c[0], c[1]);
    if (km < bestKm) {
      bestKm = km;
      best = u.id;
    }
  }
  if (best == null || bestKm > 30) return null;
  if (ups.some((u) => !centers!.c[u.id])) {
    for (const c of centers.cc[district] || []) if (distanceKm(p.lat, p.lng, c[0], c[1]) < bestKm) return null;
  }
  return best;
}
