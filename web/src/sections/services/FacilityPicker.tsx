// "কোথায় যাবেন" (২.৩১.০): হাসপাতাল/ক্লিনিকের তালিকা থেকে বাছা, নয়তো "অন্য জায়গা লিখুন"।
// তালিকা সার্ভারের amb_facilities (DGHS Facility Registry, ডায়াগনস্টিক বাদ; lib/ambplace.js): রোগীর জেলারটা আগে
// (জায়গা থাকলে কাছেরটা আগে), তারপর সারা দেশের বড় হাসপাতাল; খোঁজা সারা দেশে, বাংলা/ইংরেজিতে ("য়" একরকম করে)।
// মালিকের ভাড়ার তালিকার হাসপাতালের সারিও এখান থেকে (fare.tsx)।
import { useEffect, useMemo, useState } from 'react';
import { Button, Dialog, ErrorText, ListRow, Loading, TextInput } from '@/ui/kit';
import { call } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr, isBangla } from '@/lib/i18n';
import { bnNorm, upazila, districtLabel } from '@/lib/geo';
import type { Point } from './place';

/** সার্ভারের ছোট রূপ: [id, ইংরেজি, বাংলা, ধরন, জেলা, উপজেলা] */
export type FacilityRow = [string, string, string, string, string, number | null];
export interface Facility { facilityId: string; name: string; upazilaId: number | null; district: string }

const nameOf = (r: FacilityRow) => (isBangla() ? r[2] || r[1] : r[1]);
const TYPE_LABEL: Record<string, [string, string]> = {
  govt_mch: ['Medical college', 'মেডিকেল কলেজ'], govt_specialized: ['Specialised (govt)', 'বিশেষায়িত (সরকারি)'],
  govt_district: ['District hospital', 'জেলা হাসপাতাল'], govt_uhc: ['Upazila health complex', 'উপজেলা স্বাস্থ্য কমপ্লেক্স'],
  govt_hospital: ['Govt hospital', 'সরকারি হাসপাতাল'], private_hospital: ['Private hospital', 'বেসরকারি হাসপাতাল'], clinic: ['Clinic', 'ক্লিনিক'],
};
function subOf(r: FacilityRow): string {
  const t = TYPE_LABEL[r[3]];
  const u = upazila(r[5]);
  return [t ? tr(t[0], t[1]) : '', u ? tr(u.en, u.bn) : '', districtLabel(r[4])].filter(Boolean).join(' · ');
}
export const toFacility = (r: FacilityRow): Facility => ({ facilityId: r[0], name: nameOf(r), upazilaId: r[5], district: r[4] });

/**
 * তালিকার ডায়ালগ। onPick(facility) বা onOther(লেখা) — "অন্য জায়গা লিখুন" (allowOther হলে)।
 * near: রোগীর জায়গা (কাছেরটা আগে)।
 */
export function FacilityDialog({
  title, district, near, onPick, onOther, onClose, otherLabel,
}: {
  title: string; district: string; near?: Point | null; onPick: (f: Facility) => void; onOther?: (text: string) => void; onClose: () => void; otherLabel?: string;
}) {
  const [q, setQ] = useState('');
  const [other, setOther] = useState<string | null>(null);
  const base = useCall<{ items: FacilityRow[]; top: FacilityRow[] }>('amb_facilities', { district, ...(near ? { lat: near.lat, lng: near.lng } : {}) }, { staleTime: 10 * 60_000 });
  // খোঁজা: আগে জেলার তালিকায় সাথে সাথে, তারপর সার্ভারে সারা দেশে (৩০০ মি.সে. থেমে)
  const [remote, setRemote] = useState<FacilityRow[] | null>(null);
  const t = q.trim();
  useEffect(() => {
    setRemote(null);
    if (t.length < 2) return;
    let live = true;
    const h = setTimeout(() => {
      call<{ items: FacilityRow[] }>('amb_facilities', { district, q: t }).then((r) => live && setRemote(r.items), () => {});
    }, 300);
    return () => {
      live = false;
      clearTimeout(h);
    };
  }, [t, district]);
  const shown = useMemo(() => {
    const items = base.data?.items ?? [];
    const top = base.data?.top ?? [];
    if (!t) {
      const ids = new Set(items.map((r) => r[0]));
      return { local: items.slice(0, 300), top: top.filter((r) => !ids.has(r[0])) };
    }
    const words = bnNorm(t).split(/\s+/).filter(Boolean);
    const hit = (r: FacilityRow) => {
      const hay = `${bnNorm(r[2])} ${r[1].toLowerCase()}`;
      return words.every((w) => hay.includes(w));
    };
    const local = [...items, ...top].filter(hit);
    const seen = new Set(local.map((r) => r[0]));
    return { local: [...local, ...(remote ?? []).filter((r) => !seen.has(r[0]))].slice(0, 80), top: [] as FacilityRow[] };
  }, [base.data, t, remote]);

  if (other != null) {
    const ok = other.trim().length >= 3;
    return (
      <Dialog onClose={onClose} title={otherLabel ?? tr('Another place', 'অন্য জায়গা')} size="small"
        actions={<><Button variant="text" onClick={() => setOther(null)}>{tr('Back', 'ফিরে যান')}</Button><Button disabled={!ok} onClick={() => onOther?.(other.trim())} data-testid="fac-other-ok">{tr('OK', 'ঠিক আছে')}</Button></>}>
        <TextInput label={tr('Hospital, clinic or place name', 'হাসপাতাল, ক্লিনিক বা জায়গার নাম')} value={other} maxLength={80} autoFocus onChange={(e) => setOther(e.target.value)} data-testid="fac-other-input" />
      </Dialog>
    );
  }
  const row = (r: FacilityRow) => (
    <ListRow key={r[0]} icon="local_hospital" title={nameOf(r)} sub={subOf(r)} onClick={() => onPick(toFacility(r))} trailing={<span />} />
  );
  return (
    <Dialog onClose={onClose} title={title}>
      <TextInput
        label={tr('Search hospital or clinic', 'হাসপাতাল বা ক্লিনিক খুঁজুন')}
        value={q}
        onChange={(e) => setQ(e.target.value)}
        data-testid="fac-search"
      />
      {onOther ? (
        <Button variant="outline" icon="edit" onClick={() => setOther(t)} data-testid="fac-other">{otherLabel ?? tr('Write another place', 'অন্য জায়গা লিখুন')}</Button>
      ) : null}
      {base.isLoading ? <Loading inline /> : null}
      <ErrorText>{base.error ? base.error.message : null}</ErrorText>
      <div className="col gap-2 fac-list" data-testid="fac-list">
        {shown.local.length ? (
          <span className="t-label-l muted fac-head">{t ? tr('Results', 'খোঁজার ফল') : near ? tr('In your district — nearest first', 'আপনার জেলায় — কাছেরটা আগে') : tr('In your district', 'আপনার জেলায়')}</span>
        ) : null}
        {shown.local.map(row)}
        {shown.top.length ? <span className="t-label-l muted fac-head">{tr('Major hospitals in the country', 'দেশের বড় হাসপাতাল')}</span> : null}
        {shown.top.map(row)}
        {t && !shown.local.length && remote ? <span className="t-body-s muted">{tr('Not found — write it with "Write another place".', 'পাওয়া যায়নি — "অন্য জায়গা লিখুন" দিয়ে লিখুন।')}</span> : null}
      </div>
    </Dialog>
  );
}
