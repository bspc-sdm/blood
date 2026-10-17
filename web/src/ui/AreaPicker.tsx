// এলাকা বাছাই:
// PlacePicker — নিজের এলাকা (GPS বা জেলা), রক্তের রিকোয়েস্ট/কাছের জিনিসের জন্য (অ্যাপের LocationPicker)
// AreaPicker — জেলা › উপজেলা › (ইউনিয়ন), সেবা/ডাক্তারের তালিকার জন্য (অ্যাপের AreaChips + খোঁজা)
import { useEffect, useMemo, useState } from 'react';
import { Button, Dialog, ErrorText, ListRow } from './kit';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { tr, isBangla } from '@/lib/i18n';
import {
  areaLabel, areasReady, currentPosition, districtAt, districtsSorted, ensureAreas, searchAreas, unionsOf, upazilasOf, DISTRICTS, district as findDistrict, type AreaHit,
} from '@/lib/geo';
import type { Place } from '@/lib/session';

export function PlacePicker({ title, onPicked, onClose, busy, error }: { title?: string; onPicked: (p: Place) => void; onClose: () => void; busy?: boolean; error?: string | null }) {
  const [q, setQ] = useState('');
  const [gps, setGps] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const list = useMemo(() => {
    const t = q.trim().toLowerCase();
    return districtsSorted().filter((d) => !t || d.bn.includes(q.trim()) || d.en.toLowerCase().includes(t));
  }, [q]);

  async function useGps() {
    setGps(true);
    setErr(null);
    try {
      const { lat, lng } = await currentPosition();
      const d = await districtAt(lat, lng);
      onPicked({ lat, lng, placeName: d.bn, source: 'gps' });
    } catch {
      setErr(tr("Couldn't get your location. Allow location in the browser or pick a district.", 'লোকেশন পাওয়া যায়নি। ব্রাউজারে লোকেশনের অনুমতি দিন বা জেলা বেছে নিন।'));
    } finally {
      setGps(false);
    }
  }

  return (
    <Dialog onClose={onClose} title={title ?? tr('Your area', 'আপনার এলাকা')}>
      <Button icon="location_on" block busy={gps || busy} onClick={useGps}>{tr('Use my current location', 'বর্তমান লোকেশন ব্যবহার করুন')}</Button>
      <p className="t-body-s muted">{tr('Only your approximate area (~1 km) is saved.', 'শুধু আনুমানিক এলাকা (≈১ কিমি) রাখা হয়।')}</p>
      <ErrorText>{err ?? error}</ErrorText>
      <input className="input" placeholder={tr('Search district', 'জেলা খুঁজুন')} value={q} onChange={(e) => setQ(e.target.value)} aria-label={tr('Search district', 'জেলা খুঁজুন')} />
      <div className="col gap-2" style={{ maxHeight: '45dvh', overflowY: 'auto', margin: '0 -8px' }}>
        {list.map((d) => (
          <ListRow key={d.en} title={isBangla() ? d.bn : d.en} sub={isBangla() ? d.en : d.bn} onClick={() => onPicked({ lat: d.lat, lng: d.lng, placeName: d.bn, source: 'district' })} trailing={<span />} />
        ))}
      </div>
    </Dialog>
  );
}

export interface AreaValue { district: string; upazilaId: number | null; unionId?: number | null }

export function AreaPicker({
  value, onPicked, onClose, withUnions, title, allowAllDistrict = true,
}: { value: AreaValue | null; onPicked: (v: AreaValue) => void; onClose: () => void; withUnions?: boolean; title?: string; allowAllDistrict?: boolean }) {
  const [ready, setReady] = useState(areasReady());
  const [err, setErr] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [dist, setDist] = useState<string | null>(value?.district ?? null);
  const [upz, setUpz] = useState<number | null>(value?.upazilaId ?? null);
  const [gps, setGps] = useState(false);

  useEffect(() => {
    ensureAreas().then(() => setReady(true), () => setErr(tr("Couldn't load areas. Check your internet.", 'এলাকার তালিকা আনা যায়নি। ইন্টারনেট দেখুন।')));
  }, []);

  const hits: AreaHit[] = useMemo(() => (ready && q.trim() ? searchAreas(q, !!withUnions) : []), [q, ready, withUnions]);

  async function useGps() {
    setGps(true);
    setErr(null);
    try {
      const { lat, lng } = await currentPosition();
      const d = await districtAt(lat, lng);
      setDist(d.bn);
      setUpz(null);
    } catch {
      setErr(tr("Couldn't get your location.", 'লোকেশন পাওয়া যায়নি।'));
    } finally {
      setGps(false);
    }
  }

  const step = !dist ? 'district' : upz == null ? 'upazila' : 'union';
  const back = () => (step === 'union' ? setUpz(null) : step === 'upazila' ? setDist(null) : onClose());

  return (
    <Dialog onClose={onClose} title={title ?? tr('Choose area', 'এলাকা বেছে নিন')}>
      <input className="input" placeholder={withUnions ? tr('Search district, upazila or union', 'জেলা, উপজেলা বা ইউনিয়ন খুঁজুন') : tr('Search district or upazila', 'জেলা বা উপজেলা খুঁজুন')} value={q} onChange={(e) => setQ(e.target.value)} aria-label={tr('Search area', 'এলাকা খুঁজুন')} />
      <ErrorText>{err}</ErrorText>
      {q.trim() ? (
        <div className="col gap-2" style={{ maxHeight: '50dvh', overflowY: 'auto', margin: '0 -8px' }}>
          {!ready ? <div className="loading"><span className="spinner" /></div> : null}
          {hits.map((h) => (
            <ListRow key={`${h.district}-${h.upazilaId}-${h.unionId}`} title={areaLabel(h.district, h.upazilaId, h.unionId)} onClick={() => onPicked(h)} trailing={<span />} />
          ))}
          {ready && !hits.length ? <p className="t-body-m muted" style={{ padding: 16 }}>{tr('No match', 'মেলেনি')}</p> : null}
        </div>
      ) : (
        <>
          <div className="row">
            {step !== 'district' ? <Button variant="text" icon="arrow_back" onClick={back}>{tr('Back', 'পেছনে')}</Button> : null}
            <span className="grow t-title-s">
              {step === 'district' ? tr('District', 'জেলা') : step === 'upazila' ? areaLabel(dist) : areaLabel(dist, upz)}
            </span>
            {step === 'district' ? <Button variant="text" icon="location_on" busy={gps} onClick={useGps}>{tr('My location', 'আমার লোকেশন')}</Button> : null}
          </div>
          <div className="col gap-2" style={{ maxHeight: '50dvh', overflowY: 'auto', margin: '0 -8px' }}>
            {step === 'district'
              ? districtsSorted().map((d) => <ListRow key={d.en} title={isBangla() ? d.bn : d.en} onClick={() => { setDist(d.bn); setUpz(null); }} />)
              : null}
            {step === 'upazila' ? (
              <>
                {allowAllDistrict ? (
                  <ListRow icon="check_circle" title={tr(`All of ${areaLabel(dist)}`, `পুরো ${areaLabel(dist)}`)} onClick={() => onPicked({ district: dist!, upazilaId: null, unionId: null })} trailing={<span />} />
                ) : null}
                {!ready ? <div className="loading"><span className="spinner" /></div> : upazilasOf(dist!).map((u) => (
                  <ListRow
                    key={u.id}
                    title={isBangla() ? u.bn : u.en}
                    onClick={() => (withUnions ? setUpz(u.id) : onPicked({ district: dist!, upazilaId: u.id, unionId: null }))}
                    trailing={withUnions ? undefined : <span />}
                  />
                ))}
              </>
            ) : null}
            {step === 'union' ? (
              <>
                <ListRow icon="check_circle" title={tr(`All of ${areaLabel(dist, upz)}`, `পুরো ${areaLabel(dist, upz)}`)} onClick={() => onPicked({ district: dist!, upazilaId: upz, unionId: null })} trailing={<span />} />
                {unionsOf(upz!).map((n) => (
                  <ListRow key={n.id} title={isBangla() ? n.bn : n.en} onClick={() => onPicked({ district: dist!, upazilaId: upz, unionId: n.id })} trailing={<span />} />
                ))}
              </>
            ) : null}
          </div>
        </>
      )}
    </Dialog>
  );
}

/** সেবা/ডাক্তারের তালিকার এলাকা (এই ব্রাউজারে মনে থাকে); প্রথমবার নিজের এলাকার জেলা */
const AREA_KEY = 'lf.filterArea';
export function useFilterArea(): [AreaValue | null, (v: AreaValue) => void] {
  const { place } = useSession();
  const [v, setV] = useState<AreaValue | null>(() => store.getJson<AreaValue>(AREA_KEY));
  useEffect(() => {
    if (!v && place?.placeName && findDistrict(place.placeName)) setV({ district: place.placeName, upazilaId: null, unionId: null });
  }, [v, place]);
  useEffect(() => {
    if (v?.upazilaId != null) ensureAreas().catch(() => {});
  }, [v]);
  const set = (nv: AreaValue) => {
    store.setJson(AREA_KEY, nv);
    setV(nv);
  };
  return [v && DISTRICTS.some((d) => d.bn === v.district) ? v : null, set];
}
