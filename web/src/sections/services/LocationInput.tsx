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
import { useEffect, useState } from 'react';
import { Button, ErrorText, IconLine, TextInput } from '@/ui/kit';
import { AreaPicker } from '@/ui/AreaPicker';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { areaLabel, currentPosition, districtAt, districtLabel, ensureAreas, upazila } from '@/lib/geo';
import { MAPS_LINK_RE, googleMapsUrl, nearestUpazila, parseMapsText, type Point } from './place';

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
async function withPoint(value: LocationValue, p: Point, from: 'gps' | 'map'): Promise<LocationValue> {
  const d = (await districtAt(p.lat, p.lng)).bn;
  const auto = await nearestUpazila(d, p);
  const keep = d === value.district && !value.upazilaAuto ? value.upazilaId : null;
  return {
    ...value, district: d, point: p, pointFrom: from,
    upazilaId: auto ?? keep, upazilaAuto: auto != null, unionId: auto == null && keep != null ? value.unionId : null,
  };
}

export function LocationInput({ value, onChange, showErrors }: { value: LocationValue; onChange: (v: LocationValue) => void; showErrors?: boolean }) {
  const [picking, setPicking] = useState(false);
  const [busy, setBusy] = useState<'gps' | 'map' | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  const [mapHelp, setMapHelp] = useState(false);
  useEffect(() => {
    ensureAreas().catch(() => {});
  }, []);
  // আগে থেকে পাওয়া জায়গা (তালিকার GPS) — উপজেলা আপনা থেকে
  useEffect(() => {
    if (value.point && value.upazilaId == null) withPoint(value, value.point, value.pointFrom ?? 'gps').then(onChange, () => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function locate() {
    setAsking(false);
    setBusy('gps');
    setErr(null);
    try {
      const p = await currentPosition();
      onChange(await withPoint(value, p, 'gps'));
    } catch {
      setErr(tr("Couldn't get your location — choose the area, or give a place from Google Maps.", 'এখনকার জায়গা পাওয়া যায়নি — এলাকা বেছে নিন, অথবা Google ম্যাপ থেকে জায়গা দিন।'));
    } finally {
      setBusy(null);
    }
  }

  /** লেখার ঘরে Google ম্যাপের লিংক পেস্ট হলে: জায়গা নেওয়া, লিংকটা লেখা থেকে সরানো */
  async function fromLink(text: string) {
    const rest = text.replace(MAPS_LINK_RE, '').replace(/\s+/g, ' ').trim();
    const parsed = parseMapsText(text);
    setErr(null);
    if (!parsed) {
      // লেখা যেমন আছে থাকে (মুছে দিলে বা ঠিক করলে আবার দেখা হয়)
      setErr(tr("Couldn't find a place in this link — in Google Maps, press and hold the place, then Share.", 'এই লিংকে জায়গা পাওয়া যায়নি — Google ম্যাপে জায়গাটা চেপে ধরে "শেয়ার" চাপুন।'));
      return;
    }
    setBusy('map');
    try {
      const p = 'short' in parsed ? await call<Point>('maps_link_resolve', { url: parsed.short }) : parsed;
      onChange(await withPoint({ ...value, landmark: rest }, p, 'map'));
      setMapHelp(false);
    } catch (x) {
      setErr(errorText(x));
    } finally {
      setBusy(null);
    }
  }

  // লেখার ঘরে লিংক: লেখা থামার ০.৭ সেকেন্ড পরে পড়া — অক্ষর ধরে লিখলে অর্ধেক লিংক ("…?q=24.08,90") থেকে ভুল জায়গা নয়
  useEffect(() => {
    if (!MAPS_LINK_RE.test(value.landmark)) return;
    const h = setTimeout(() => fromLink(value.landmark), 700);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.landmark]);

  const noUpazila = value.upazilaId == null;
  const p = value.point;
  const u = upazila(value.upazilaId);
  return (
    <div className="col gap-8 loc-input" data-testid="amb-location">
      <span className="t-title-s">{tr('Pick-up from', 'কোথা থেকে নেবে')}</span>
      {p ? (
        <div className="loc-gps-ok row gap-8" data-testid={value.pointFrom === 'map' ? 'amb-map-ok' : 'amb-gps-ok'}>
          <span className="grow col gap-2">
            <span className="t-body-m">{value.pointFrom === 'map' ? tr('Place from the map taken ✓', 'ম্যাপের জায়গা নেওয়া হয়েছে ✓') : tr('Current location taken ✓', 'এখনকার জায়গা নেওয়া হয়েছে ✓')}</span>
            <a className="t-label-l loc-maplink" href={googleMapsUrl(p)} target="_blank" rel="noopener noreferrer" data-testid="amb-gmaps">{tr('See on Google Maps', 'Google ম্যাপে দেখুন')}</a>
          </span>
          <Button variant="text" small onClick={() => onChange({ ...value, point: null, pointFrom: null, ...(value.upazilaAuto ? { upazilaId: null, upazilaAuto: false } : {}) })}>{tr('Remove', 'বাদ দিন')}</Button>
        </div>
      ) : asking ? (
        // অনুমতি চাওয়ার আগে কেন — ব্রাউজারের নিজের প্রশ্ন এরপর আসে
        <div className="loc-why col gap-6">
          <span className="t-body-m">
            {tr('Your current location helps the nearest ambulance come first. Only the driver who accepts sees it, and it is deleted 24 hours after the request ends.',
              'এখনকার জায়গা দিলে সবচেয়ে কাছের অ্যাম্বুলেন্স আগে খবর পায়। শুধু রাজি হওয়া চালক দেখবেন, অনুরোধ শেষ হওয়ার ২৪ ঘণ্টা পর মুছে যায়।')}
          </span>
          <div className="row gap-8">
            <Button small icon="location_on" onClick={locate}>{tr('Allow', 'অনুমতি দিন')}</Button>
            <Button small variant="text" onClick={() => setAsking(false)}>{tr('Not now', 'এখন না')}</Button>
          </div>
        </div>
      ) : (
        <Button icon="location_on" block busy={busy === 'gps'} onClick={() => setAsking(true)} data-testid="amb-gps">
          {tr('Current location', 'এখনকার জায়গা')}
        </Button>
      )}
      {!p ? (
        <>
          <Button variant="outline" icon="map" block busy={busy === 'map'} onClick={() => setMapHelp((v) => !v)} data-testid="amb-map">
            {tr('Give a place from Google Maps', 'Google ম্যাপ থেকে জায়গা দিন')}
          </Button>
          {mapHelp ? (
            <span className="t-body-s loc-why" data-testid="amb-map-help">
              {tr('In Google Maps, press and hold the place → Share → Copy link, then paste it in the box below. ', 'Google ম্যাপে জায়গাটা চেপে ধরুন → শেয়ার → লিংক কপি করুন, তারপর নিচের ঘরে পেস্ট করুন। ')}
              <a href="https://www.google.com/maps" target="_blank" rel="noopener noreferrer">{tr('Open Google Maps', 'Google ম্যাপ খুলুন')}</a>
            </span>
          ) : null}
        </>
      ) : null}
      <TextInput
        label={p ? tr('Write the location (optional)', 'লোকেশন লিখুন (ঐচ্ছিক)') : tr('Write the location', 'লোকেশন লিখুন')}
        hint={tr('Area / road / landmark — e.g. house 12, near the school gate. A Google Maps link works too.', 'এলাকা / রাস্তা / চেনা জায়গা — যেমন: বাসা ১২, স্কুল গেটের পাশে। Google ম্যাপের লিংকও দেওয়া যায়।')}
        value={value.landmark}
        maxLength={400}
        onChange={(e) => {
          const t = e.target.value;
          setErr(null);
          onChange({ ...value, landmark: t.slice(0, MAPS_LINK_RE.test(t) ? 600 : 160) });
        }}
        error={showErrors && !p && !value.landmark.trim() ? tr('Required', 'লিখতে হবে') : null}
        data-testid="amb-pickup"
      />
      <button
        type="button"
        className={`loc-area${showErrors && !p && noUpazila ? ' error' : ''}`}
        onClick={() => setPicking(true)}
        data-testid="amb-area"
      >
        <IconLine icon="location_on">
          {value.upazilaAuto && u
            ? `${tr(u.en, u.bn)}, ${districtLabel(value.district)} ${tr('(from the location)', '(জায়গা থেকে)')}`
            : areaLabel(value.district, value.upazilaId, value.unionId)}
          {noUpazila && !p ? <span className="loc-need"> · {tr('choose upazila', 'উপজেলা বেছে নিন')}</span> : null}
        </IconLine>
        <span className="t-label-l loc-change">{tr('Change', 'বদলান')}</span>
      </button>
      <ErrorText>{err}</ErrorText>
      {picking ? (
        <AreaPicker
          title={tr('Where to pick up', 'কোথা থেকে নেবে')}
          value={{ district: value.district, upazilaId: value.upazilaId, unionId: value.unionId }}
          withUnions
          allowAllDistrict={false}
          onClose={() => setPicking(false)}
          onPicked={(v) => {
            setPicking(false);
            if (v.upazilaId == null) return;
            // জেলা বদলালে আগের জায়গা আর খাটে না
            const same = v.district === value.district;
            onChange({ ...value, district: v.district, upazilaId: v.upazilaId, unionId: v.unionId ?? null, upazilaAuto: false, point: same ? value.point : null, pointFrom: same ? value.pointFrom : null });
          }}
        />
      ) : null}
    </div>
  );
}
