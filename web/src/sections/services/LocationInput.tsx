// অ্যাম্বুলেন্স ডাকার "কোথা থেকে নেবে" (২.২৯.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — অ্যাপের LocationInput.kt-এর ওয়েব রূপ।
// ম্যাপ নেই (খরচ/ঝুঁকি নয়): এলাকা (জেলা › উপজেলা › ইউনিয়ন, বাধ্যতামূলক উপজেলা পর্যন্ত) + "এলাকা / রাস্তা / চেনা জায়গা"
// লেখা (আগের বারের লেখা আগে থেকে বসানো) + ঐচ্ছিক "আমার এখনকার জায়গা" (ব্রাউজারের এককালীন GPS — বিনামূল্যে, ম্যাপ লাগে না)।
// GPS পেলে পাঠানো দূরত্ব ধরে (আগের মতো); না পেলে/না দিলে উপজেলা ধরে (নিজের উপজেলার গাড়ি আগে — server/lib/ambreq.js)।
// ঠিক জায়গা শুধু রাজি হওয়া চালক পান, অনুরোধ বন্ধের ২৪ ঘণ্টা পর মোছে (সার্ভারের নিয়ম, বদলায়নি)।
import { useEffect, useState } from 'react';
import { Button, ErrorText, IconLine, TextInput } from '@/ui/kit';
import { AreaPicker } from '@/ui/AreaPicker';
import { tr } from '@/lib/i18n';
import { areaLabel, currentPosition, districtAt, ensureAreas } from '@/lib/geo';

/**
 * ভবিষ্যতে ম্যাপ (বিনিয়োগ পেলে) — এখানে যোগ হবে।
 * true করলে LocationInput-এর ভেতরে একটা MapPicker বসবে (point বদলায়); ডাকার বাকি পথ (RequestDialog → ambreq_create)
 * একই থাকে — শুধু LocationValue.point ভরে। টাইলের খরচ/নিয়ম ঠিক না হওয়া পর্যন্ত false।
 */
export const MAP_PICKER_ENABLED = false;

export interface LocationValue {
  district: string;
  upazilaId: number | null;
  unionId: number | null;
  /** এলাকা / রাস্তা / চেনা জায়গা (চালক পড়েন) */
  landmark: string;
  /** "আমার এখনকার জায়গা" — না দিলে null (উপজেলা ধরে) */
  point: { lat: number; lng: number } | null;
}

/** উপজেলা বাছা না থাকলে পাঠানো যায় না */
export const locationError = (v: LocationValue): string | null =>
  v.upazilaId == null ? tr('Choose the upazila', 'উপজেলা বেছে নিন')
    : !v.landmark.trim() ? tr('Write the area, road or a landmark', 'এলাকা / রাস্তা / চেনা জায়গা লিখুন') : null;

export function LocationInput({ value, onChange, showErrors }: { value: LocationValue; onChange: (v: LocationValue) => void; showErrors?: boolean }) {
  const [picking, setPicking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  useEffect(() => {
    ensureAreas().catch(() => {});
  }, []);

  async function locate() {
    setAsking(false);
    setBusy(true);
    setErr(null);
    try {
      const p = await currentPosition();
      const d = await districtAt(p.lat, p.lng);
      if (d.bn !== value.district) {
        setErr(tr(`You are in ${d.en}, not the chosen district — change the area if needed. The area will be used.`, `আপনি এখন ${d.bn}-এ, বেছে নেওয়া জেলায় নন — দরকার হলে এলাকা বদলান। এলাকা ধরে পাঠানো হবে।`));
      } else onChange({ ...value, point: p });
    } catch {
      setErr(tr("Couldn't get your location — the chosen area will be used.", 'এখনকার জায়গা পাওয়া যায়নি — বেছে নেওয়া এলাকা ধরে পাঠানো হবে।'));
    } finally {
      setBusy(false);
    }
  }

  const noUpazila = value.upazilaId == null;
  return (
    <div className="col gap-8 loc-input" data-testid="amb-location">
      <span className="t-title-s">{tr('Area', 'এলাকা')}</span>
      <button
        type="button"
        className={`loc-area${showErrors && noUpazila ? ' error' : ''}`}
        onClick={() => setPicking(true)}
        data-testid="amb-area"
      >
        <IconLine icon="location_on">
          {areaLabel(value.district, value.upazilaId, value.unionId)}
          {noUpazila ? <span className="loc-need"> · {tr('choose upazila', 'উপজেলা বেছে নিন')}</span> : null}
        </IconLine>
        <span className="t-label-l loc-change">{tr('Change', 'বদলান')}</span>
      </button>
      <TextInput
        label={tr('Area / road / landmark', 'এলাকা / রাস্তা / চেনা জায়গা')}
        hint={tr('e.g. house 12, near the school gate', 'যেমন: বাসা ১২, স্কুল গেটের পাশে')}
        value={value.landmark}
        maxLength={160}
        onChange={(e) => onChange({ ...value, landmark: e.target.value })}
        error={showErrors && !value.landmark.trim() ? tr('Required', 'লিখতে হবে') : null}
        data-testid="amb-pickup"
      />
      {value.point ? (
        <div className="loc-gps-ok row gap-8" data-testid="amb-gps-ok">
          <span className="grow t-body-m">{tr('Current location taken ✓', 'এখনকার জায়গা নেওয়া হয়েছে ✓')}</span>
          <Button variant="text" small onClick={() => onChange({ ...value, point: null })}>{tr('Remove', 'বাদ দিন')}</Button>
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
        <Button variant="outline" icon="location_on" busy={busy} onClick={() => setAsking(true)} data-testid="amb-gps">
          {tr('My current location (optional)', 'আমার এখনকার জায়গা (ঐচ্ছিক)')}
        </Button>
      )}
      {!value.point ? <span className="t-body-s muted">{tr('Without it, nearby ambulances of the chosen upazila get it first.', 'না দিলে বেছে নেওয়া উপজেলার গাড়ি আগে খবর পায়।')}</span> : null}
      <ErrorText>{err}</ErrorText>
      {/* MAP_PICKER_ENABLED হলে এখানে ম্যাপ — এখন নেই */}
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
            // জেলা বদলালে আগের GPS আর খাটে না
            onChange({ ...value, district: v.district, upazilaId: v.upazilaId, unionId: v.unionId ?? null, point: v.district === value.district ? value.point : null });
          }}
        />
      ) : null}
    </div>
  );
}
