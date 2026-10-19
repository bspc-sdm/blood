// অ্যাম্বুলেন্সের দূরত্ব কোথা থেকে: ব্রাউজারে লোকেশনের অনুমতি আগেই দেওয়া থাকলে এখনকার জায়গা (বেছে নেওয়া জেলার ভেতরে হলে),
// নইলে প্রোফাইলের GPS জায়গা (একই জেলা), নইলে "এলাকা ধরে" (স্থানাঙ্ক নয় — জেলার কেন্দ্র থেকে দূরত্ব অর্থহীন, উপজেলার ক্রম নষ্ট করে)।
// অনুমতি চেয়ে আটকানো হয় না — প্রতিটা সেকেন্ড দামি।
// GPS-এর জন্য সর্বোচ্চ ২.৫ সেকেন্ড অপেক্ষা; তারপর একবার ঠিক হলে আর বদলায় না (বাটনের গাড়ি হঠাৎ বদলে যেন ভুল জায়গায় চাপ না পড়ে)।
import { computed, ref, watch, type ComputedRef } from 'vue';
import { currentPosition, districtAt } from '@/lib/geo';
import { useSession } from '@/lib/session';

/** byArea: GPS নেই — সার্ভারে স্থানাঙ্ক পাঠানো হয় না, আগের মতো নিজের উপজেলা আগে; lat/lng তখন null */
export type Origin = { lat: number; lng: number; byArea: false } | { lat: null; lng: null; byArea: true };
// ২ দশমিক (≈১ কিমি) — সার্ভারও এভাবেই কাটে; এর বেশি সূক্ষ্ম পাঠানোর দরকার নেই
const r2 = (v: number) => Math.round(v * 100) / 100;

export function useAmbulanceOrigin(district: () => string | undefined, on: () => boolean): {
  origin: ComputedRef<Origin | null>; ready: ComputedRef<boolean>; exact: ComputedRef<{ lat: number; lng: number } | null>;
} {
  const s = useSession();
  const gps = ref<{ lat: number; lng: number; district: string } | null | undefined>(on() ? undefined : null);
  let settled = false;
  watch(
    on,
    (o, _p, onCleanup) => {
      if (!o) return;
      let live = true;
      const done = (v: { lat: number; lng: number; district: string } | null) => {
        if (!live || settled) return;
        settled = true;
        gps.value = v;
      };
      const timer = setTimeout(() => done(null), 2500);
      const q = navigator.permissions?.query?.({ name: 'geolocation' as PermissionName });
      if (!q) done(null);
      else {
        q.then(async (p) => {
          if (p.state !== 'granted') return null;
          const pos = await currentPosition();
          return { lat: pos.lat, lng: pos.lng, district: (await districtAt(pos.lat, pos.lng)).bn };
        }).then((v) => done(v), () => done(null));
      }
      onCleanup(() => {
        live = false;
        clearTimeout(timer);
      });
    },
    { immediate: true },
  );
  const origin = computed<Origin | null>(() => {
    const d = district();
    if (!on() || !d) return null;
    const g = gps.value;
    if (g && g.district === d) return { lat: r2(g.lat), lng: r2(g.lng), byArea: false };
    const p = s.place;
    if (p?.source === 'gps' && p.placeName === d) return { lat: r2(p.lat), lng: r2(p.lng), byArea: false };
    return { lat: null, lng: null, byArea: true };
  });
  // অ্যাম্বুলেন্স ডাকার জন্য কাটা নয় এমন জায়গা (শুধু রাজি হওয়া চালক পান — ম্যাপে খুলতে)
  const exact = computed(() => {
    const d = district();
    if (!on() || !d) return null;
    const g = gps.value;
    if (g && g.district === d) return { lat: g.lat, lng: g.lng };
    const p = s.place;
    return p?.source === 'gps' && p.placeName === d ? { lat: p.lat, lng: p.lng } : null;
  });
  return { origin, ready: computed(() => !on() || gps.value !== undefined), exact };
}
