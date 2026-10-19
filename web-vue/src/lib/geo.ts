// এলাকা: জেলা, উপজেলা, ইউনিয়ন (public/data/geo.json — দরকার হলে একবার আনা), GPS থেকে জেলা, দূরত্ব।
import { distanceBetween, geohashForLocation } from 'geofire-common';
import { DISTRICTS, type District } from './districts-data';
import { ref } from 'vue';
import { isBangla } from './i18n';

export { DISTRICTS, type District };

export interface Upazila { id: number; district: string; bn: string; en: string }
export interface Union { id: number; upazilaId: number; bn: string; en: string }

/** তালিকা এলে বাড়ে — যেসব টেমপ্লেট উপজেলা/ইউনিয়নের নাম পড়ে সেগুলো নিজে থেকে আবার আঁকে (React-এ useAreasLoaded/bump) */
export const areasTick = ref(0);
let upazilas: Upazila[] = [];
let unions: Union[] = [];
let loading: Promise<void> | null = null;

/** জেলা-উপজেলা-ইউনিয়নের তালিকা (≈২৬০ KB) একবার আনা */
export function ensureAreas(): Promise<void> {
  if (upazilas.length) return Promise.resolve();
  if (!loading) {
    loading = fetch('/w/data/geo.json')
      .then((r) => r.json())
      .then((o: { u: [number, string, string, string][]; n: [number, number, string, string][] }) => {
        upazilas = o.u.map(([id, district, bn, en]) => ({ id, district, bn, en }));
        unions = o.n.map(([id, upazilaId, bn, en]) => ({ id, upazilaId, bn, en }));
        areasTick.value++;
      })
      .catch((e) => {
        loading = null;
        throw e;
      });
  }
  return loading;
}
export const areasReady = () => areasTick.value >= 0 && upazilas.length > 0;

const byLabel = <T extends { bn: string; en: string }>(a: T, b: T) => (isBangla() ? a.bn.localeCompare(b.bn, 'bn') : a.en.localeCompare(b.en));

export function districtsSorted(): District[] {
  return [...DISTRICTS].sort(byLabel);
}
export function upazilasOf(district: string): Upazila[] {
  void areasTick.value;
  return upazilas.filter((u) => u.district === district).sort(byLabel);
}
export function unionsOf(upazilaId: number): Union[] {
  void areasTick.value;
  return unions.filter((n) => n.upazilaId === upazilaId).sort(byLabel);
}
export const upazila = (id?: number | null) => (id == null || areasTick.value < 0 ? undefined : upazilas.find((u) => u.id === id));
export const union = (id?: number | null) => (id == null || areasTick.value < 0 ? undefined : unions.find((u) => u.id === id));
export const district = (bn?: string | null) => (bn ? DISTRICTS.find((d) => d.bn === bn) : undefined);

/** ডাটাবেসে জেলার নাম বাংলায়; ইংরেজিতে ইংরেজি নাম */
export function districtLabel(bn?: string | null): string {
  if (!bn) return '';
  if (isBangla()) return bn;
  const n = bnNorm(bn);
  return DISTRICTS.find((d) => d.bn === bn || bnNorm(d.bn) === n)?.en ?? bn;
}
const lbl = (x?: { bn: string; en: string }) => (x ? (isBangla() ? x.bn : x.en) : '');

/** "দেবিদ্বার, কুমিল্লা" — উপজেলা/ইউনিয়ন থাকলে সেটা আগে (তালিকা আগে আনা না থাকলে শুধু জেলা) */
export function areaLabel(districtBn?: string | null, upazilaId?: number | null, unionId?: number | null): string {
  const parts = [lbl(union(unionId)), lbl(upazila(upazilaId)), districtLabel(districtBn)].filter(Boolean);
  return parts.join(', ');
}

/** "য়/ড়/ঢ়" দুই রকম ইউনিকোডে লেখা যায় — মেলানোর আগে এক রূপে */
export function bnNorm(s: string): string {
  return s.normalize('NFC').replace(/য়/g, 'য়').replace(/ড়/g, 'ড়').replace(/ঢ়/g, 'ঢ়').toLowerCase();
}

export interface AreaHit { district: string; upazilaId: number | null; unionId: number | null }

/** এক সার্চ বক্সে জেলা, উপজেলা (চাইলে ইউনিয়ন) — নামের শুরুতে মিললে আগে */
export function searchAreas(query: string, withUnions: boolean, limit = 40): AreaHit[] {
  const q = bnNorm(query.trim());
  if (!q) return [];
  const score = (bn0: string, en: string) => {
    const bn = bnNorm(bn0);
    const e = en.toLowerCase();
    if (bn.startsWith(q) || e.startsWith(q)) return 0;
    if (bn.includes(q) || e.includes(q)) return 1;
    return -1;
  };
  const out: [number, AreaHit][] = [];
  DISTRICTS.forEach((d) => {
    const s = score(d.bn, d.en);
    if (s >= 0) out.push([s * 3, { district: d.bn, upazilaId: null, unionId: null }]);
  });
  upazilas.forEach((u) => {
    const s = score(u.bn, u.en);
    if (s >= 0) out.push([s * 3 + 1, { district: u.district, upazilaId: u.id, unionId: null }]);
  });
  if (withUnions && q.length >= 2) {
    const ups = new Map(upazilas.map((u) => [u.id, u]));
    unions.forEach((n) => {
      const s = score(n.bn, n.en);
      const u = ups.get(n.upazilaId);
      if (s >= 0 && u) out.push([s * 3 + 2, { district: u.district, upazilaId: u.id, unionId: n.id }]);
    });
  }
  return out.sort((a, b) => a[0] - b[0]).slice(0, limit).map((x) => x[1]);
}

// ---------- GPS ----------

/** প্রাইভেসির জন্য স্থানাঙ্ক গোল (≈১ কিমি) */
export const roundCoord = (v: number) => Math.round(v * 100) / 100;
export const geohash = (lat: number, lng: number) => geohashForLocation([lat, lng], 10);
export const distanceKm = (aLat: number, aLng: number, bLat: number, bLng: number) => distanceBetween([aLat, aLng], [bLat, bLng]);

export function nearestDistrict(lat: number, lng: number): District {
  return DISTRICTS.reduce((best, d) => (distanceKm(lat, lng, d.lat, d.lng) < distanceKm(lat, lng, best.lat, best.lng) ? d : best));
}

type Shape = { name: string; rings: number[][]; minLat: number; maxLat: number; minLng: number; maxLng: number };
let shapes: Shape[] | null = null;
async function loadBounds(): Promise<Shape[]> {
  if (shapes) return shapes;
  const o: { d: [string, number[][]][] } = await fetch('/w/data/district_bounds.json').then((r) => r.json());
  shapes = o.d.map(([name, rings]) => {
    let a = 90, b = -90, c = 180, f = -180;
    rings.forEach((r) => {
      for (let j = 0; j < r.length; j += 2) {
        a = Math.min(a, r[j]); b = Math.max(b, r[j]); c = Math.min(c, r[j + 1]); f = Math.max(f, r[j + 1]);
      }
    });
    return { name, rings, minLat: a, maxLat: b, minLng: c, maxLng: f };
  });
  return shapes;
}
function inside(lat: number, lng: number, r: number[]) {
  let c = false;
  const n = r.length / 2;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const yi = r[2 * i], xi = r[2 * i + 1], yj = r[2 * j], xj = r[2 * j + 1];
    if (yi > lat !== yj > lat && lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) c = !c;
  }
  return c;
}

/** GPS-এর জায়গা কোন জেলার ভেতরে (সীমানা দিয়ে; বাইরে পড়লে কাছের কেন্দ্র) */
export async function districtAt(lat: number, lng: number): Promise<District> {
  try {
    const s = (await loadBounds()).find((x) => lat >= x.minLat && lat <= x.maxLat && lng >= x.minLng && lng <= x.maxLng && x.rings.some((r) => inside(lat, lng, r)));
    const d = s && DISTRICTS.find((x) => x.bn === s.name);
    if (d) return d;
  } catch {
    /* সীমানা আনা না গেলে কাছের কেন্দ্র */
  }
  return nearestDistrict(lat, lng);
}

/** ব্রাউজারের কাছে আনুমানিক লোকেশন চাওয়া */
/** লোকেশন পাওয়া গেছে, কিন্তু যথেষ্ট ঠিক নয় (accuracy খারাপ) */
export class InaccuratePosition extends Error {
  accuracy: number;
  constructor(accuracy: number) {
    super('inaccurate');
    this.accuracy = accuracy;
  }
}

/**
 * ঠিক জায়গা (গাড়ির জায়গা দেওয়ার মতো কাজে): high accuracy, পুরোনো জায়গা নয়;
 * accuracy maxM মিটারের বেশি খারাপ হলে InaccuratePosition (ঘরের ভেতরে/ওয়াই-ফাই দিয়ে আন্দাজ ইত্যাদি)।
 */
export function precisePosition(maxM = 200): Promise<{ lat: number; lng: number; accuracy: number }> {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) return reject(new Error('no-geo'));
    navigator.geolocation.getCurrentPosition(
      (p) => {
        const accuracy = p.coords.accuracy;
        if (!Number.isFinite(accuracy) || accuracy > maxM) reject(new InaccuratePosition(accuracy));
        else resolve({ lat: p.coords.latitude, lng: p.coords.longitude, accuracy });
      },
      (e) => reject(e),
      { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 },
    );
  });
}

export function currentPosition(): Promise<{ lat: number; lng: number }> {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) return reject(new Error('no-geo'));
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lng: p.coords.longitude }),
      (e) => reject(e),
      { enableHighAccuracy: false, timeout: 15000, maximumAge: 10 * 60 * 1000 },
    );
  });
}
