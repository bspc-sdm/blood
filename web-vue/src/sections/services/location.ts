// অ্যাম্বুলেন্স ডাকার "কোথা থেকে নেবে" (২.৩১.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — অ্যাপের LocationInput.kt-এর ওয়েব রূপ।
// তিনভাবে, সবই বিনামূল্যে (অ্যাপ/সাইটের ভেতরে ম্যাপ নেই):
//   ১) "এখনকার জায়গা" (প্রধান): ব্রাউজারের এককালীন GPS। নেওয়ার পর "Google ম্যাপে দেখুন" — নতুন ট্যাবে Google Maps-এ বিন্দুটা,
//      শুধু মিলিয়ে দেখার জন্য (কিছু ফেরত আসে না, API key নয়)।
//   ২) "Google ম্যাপ থেকে জায়গা দিন": Google Maps-এ জায়গা চেপে ধরে লিংক কপি → লেখার ঘরে পেস্ট। লম্বা লিংক এখানেই পড়া
//      (place.ts), ছোট লিংক (maps.app.goo.gl) সার্ভারে খোলা (maps_link_resolve)।
//   ৩) "লোকেশন লিখুন": এলাকা / রাস্তা / চেনা জায়গা (জায়গা না দিলে বাধ্যতামূলক)।
// জায়গা (১ বা ২) থাকলে জেলা সেই জায়গার, উপজেলা আপনা থেকে (কাছের উপজেলা-কেন্দ্র); না পারলে উপজেলার ঘর থাকে।
// ভবিষ্যতে অ্যাপের ভেতরে Google Maps SDK-র ম্যাপ বসানো যায় (Android-এ বিনামূল্যে), কিন্তু Google Cloud-এ বিলিং অ্যাকাউন্ট
// (কার্ড) লাগে — এখন নেই। তখনও শুধু LocationValue.point ভরবে; ডাকার বাকি পথ (RequestDialog → ambreq_create) একই।
// ঠিক জায়গা শুধু রাজি হওয়া চালক পান, অনুরোধ বন্ধের ২৪ ঘণ্টা পর মোছে (সার্ভারের নিয়ম)।
import { tr } from '@/lib/i18n';
import { districtAt } from '@/lib/geo';
import { nearestUpazila, type Point } from './place';

export interface LocationValue {
  district: string;
  upazilaId: number | null;
  unionId: number | null;
  /** লোকেশন লিখুন: এলাকা / রাস্তা / চেনা জায়গা (চালক পড়েন) */
  landmark: string;
  /** ঠিক জায়গা — এখনকার (gps) বা Google ম্যাপ থেকে (map); না দিলে null (উপজেলা ধরে) */
  point: Point | null;
  pointFrom?: 'gps' | 'map' | null;
  /** উপজেলা জায়গা থেকে আপনা থেকে বসানো */
  upazilaAuto?: boolean;
}

/** জায়গা না থাকলে উপজেলা আর লেখা দুটোই লাগে; জায়গা থাকলে কিছুই বাধ্যতামূলক নয় */
export const locationError = (v: LocationValue): string | null =>
  v.point ? null
    : v.upazilaId == null ? tr('Choose the upazila, or give your current location', 'উপজেলা বেছে নিন, অথবা এখনকার জায়গা দিন')
      : !v.landmark.trim() ? tr('Write the area, road or a landmark', 'এলাকা / রাস্তা / চেনা জায়গা লিখুন') : null;

/** জায়গা থেকে জেলা আর উপজেলা (আপনা থেকে, না পারলে একই জেলায় আগেরটা) */
export async function withPoint(value: LocationValue, p: Point, from: 'gps' | 'map'): Promise<LocationValue> {
  const d = (await districtAt(p.lat, p.lng)).bn;
  const auto = await nearestUpazila(d, p);
  const keep = d === value.district && !value.upazilaAuto ? value.upazilaId : null;
  return {
    ...value, district: d, point: p, pointFrom: from,
    upazilaId: auto ?? keep, upazilaAuto: auto != null, unionId: auto == null && keep != null ? value.unionId : null,
  };
}

