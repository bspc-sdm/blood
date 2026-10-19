// "ডাক" (২.২৮.০) — অ্যাম্বুলেন্সের মালিক/চালকের পাতার উপরে (অ্যাপের AmbulanceCallsSection):
// খোলা অনুরোধ (দূরত্ব, ঠিকানা, নোট, কতক্ষণ আগে) — "আসছি" / "পারব না"; নিজের নেওয়াটা — রোগীর নাম, ফোন,
// "ম্যাপে খুলুন" (ফোনের/ব্রাউজারের নিজের ম্যাপ, আলাদা খরচ নেই), "পৌঁছেছি" / "শেষ" / "বাতিল"।
// রাজির আগে রোগীর ফোন, নাম, ঠিক জায়গা আসেই না (সার্ভার দেয় না)।
import { useState } from 'react';
import { Button, ErrorText, IconLine, InfoText, Tag } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { timeAgo } from '@/lib/format';
import { DRIVER_REASONS, ReasonDialog, reqTypeLabel } from '@/sections/services/AmbRequest';

interface DriverReq {
  id: string;
  status: 'open' | 'accepted' | 'arrived';
  createdAt: number;
  ambulanceId: string | null;
  km: number | null;
  kmApprox?: boolean;
  type: string | null;
  district: string;
  upazilaId: number | null;
  unionId?: number | null;
  pickup: string;
  note: string;
  called: boolean;
  patientName?: string;
  phone?: string;
  lat?: number;
  lng?: number;
}

/** ম্যাপের লিংক: স্থানাঙ্ক থাকলে সেটা, নইলে "চেনা জায়গা, ইউনিয়ন, উপজেলা, জেলা, বাংলাদেশ" দিয়ে খোঁজা */
export function mapLink(r: Pick<DriverReq, 'lat' | 'lng' | 'pickup' | 'district' | 'upazilaId' | 'unionId'>): string {
  const q = r.lat != null && r.lng != null ? `${r.lat},${r.lng}` : [r.pickup, areaLabel(r.district, r.upazilaId, r.unionId), 'Bangladesh'].filter(Boolean).join(', ');
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(q)}`;
}

export function AmbulanceCalls({ names, onChanged }: { names: Record<string, string>; onChanged: () => void }) {
  // পাতা খোলা থাকলে ৫ সেকেন্ড পরপর (পেছনের ট্যাবে ব্রাউজার নিজেই থামায়)
  const q = useCall<{ items: DriverReq[] }>('ambreq_driver', {}, { refetchInterval: 5000, staleTime: 0 });
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [cancel, setCancel] = useState<DriverReq | null>(null);
  const items = q.data?.items ?? [];

  async function act(r: DriverReq, f: () => Promise<unknown>, changed = false, done?: string) {
    setBusy(r.id);
    setErr(null);
    setInfo(null);
    try {
      await f();
      if (done) setInfo(done);
      if (changed) onChanged();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(null);
      q.refetch();
    }
  }

  return (
    <section className="col gap-8 amb-calls" aria-label={tr('Calls', 'ডাক')}>
      <h2 className="t-title-m row gap-8"><Icon name="notifications_active" />{tr('Calls', 'ডাক')}</h2>
      <ErrorText>{err}</ErrorText>
      <InfoText>{info}</InfoText>
      {q.data && !items.length ? (
        <p className="t-body-s muted">{tr('When a patient nearby calls an ambulance, it shows here. Keep "Available now" on.', 'কাছের কোনো রোগী অ্যাম্বুলেন্স ডাকলে এখানে আসবে। "এখন খালি আছি" চালু রাখুন।')}</p>
      ) : null}
      {items.map((r) => {
        const mine = r.status !== 'open';
        const km = r.km == null ? null : r.kmApprox ? tr(`about ${Math.max(1, r.km)} km`, `প্রায় ${num(Math.max(1, r.km))} কিমি`) : r.km < 1 ? tr('< 1 km', '১ কিমির কম') : `${num(r.km)}${tr(' km', ' কিমি')}`;
        return (
          <article key={r.id} className={mine ? 'amb-call mine' : 'amb-call'} data-testid="amb-call">
            <div className="row">
              <span className="t-title-m grow">
                {mine ? (r.status === 'arrived' ? tr('You arrived', 'আপনি পৌঁছেছেন') : tr("You're going", 'আপনি যাচ্ছেন')) : tr('Ambulance needed', 'অ্যাম্বুলেন্স দরকার')}
              </span>
              <span className="t-body-s muted">{timeAgo(r.createdAt)}</span>
            </div>
            {r.called ? <div><Tag tone="error">{tr('The patient called you', 'রোগী আপনাকে ফোন করেছেন')}</Tag></div> : null}
            <IconLine icon="map">{(km ? km + tr(' away', ' দূরে') + ' · ' : '') + areaLabel(r.district, r.upazilaId, r.unionId)}</IconLine>
            <IconLine icon="location_on">{r.pickup}</IconLine>
            {r.note ? <IconLine icon="info">{r.note}</IconLine> : null}
            <IconLine icon="ambulance">{tr('Type: ', 'ধরন: ') + reqTypeLabel(r.type)}</IconLine>
            {r.ambulanceId && names[r.ambulanceId] ? <IconLine icon="ambulance">{tr('Your vehicle: ', 'আপনার গাড়ি: ') + names[r.ambulanceId]}</IconLine> : null}
            {mine ? (
              <>
                {r.patientName ? <IconLine icon="person_outline">{r.patientName}</IconLine> : null}
                <div className="row wrap gap-8">
                  {r.phone ? <a className="btn square has-icon" href={`tel:${r.phone}`}><Icon name="call" />{num(r.phone)}</a> : null}
                  {/* নিজের ম্যাপ (খরচ নেই): ঠিক জায়গা থাকলে সেটা, নইলে ঠিকানা আর এলাকার লেখা দিয়ে খোঁজা */}
                  <a className="btn outline square has-icon" href={mapLink(r)} target="_blank" rel="noopener noreferrer" data-testid="amb-open-map">
                    <Icon name="map" />
                    {tr('Open in map', 'ম্যাপে খুলুন')}
                  </a>
                </div>
                <div className="row wrap gap-8">
                  {r.status === 'accepted' ? <Button busy={busy === r.id} disabled={!!busy} onClick={() => act(r, () => call('ambreq_step', { id: r.id, step: 'arrived' }))}>{tr('Arrived', 'পৌঁছেছি')}</Button> : null}
                  <Button busy={busy === r.id} disabled={!!busy} onClick={() => act(r, () => call('ambreq_step', { id: r.id, step: 'done' }), true, tr("Trip finished. You're available again.", 'ট্রিপ শেষ। আপনি আবার খালি।'))}>{tr('Finish', 'শেষ')}</Button>
                  <span className="grow" />
                  <Button variant="danger-text" disabled={!!busy} onClick={() => setCancel(r)}>{tr('Cancel', 'বাতিল')}</Button>
                </div>
              </>
            ) : (
              <div className="row gap-8">
                <Button icon="check_circle" className="grow" busy={busy === r.id} disabled={!!busy} onClick={() => act(r, () => call('ambreq_accept', { id: r.id }), true)} data-testid="amb-accept">{tr('Coming', 'আসছি')}</Button>
                <Button variant="outline" disabled={!!busy} onClick={() => act(r, () => call('ambreq_decline', { id: r.id }))}>{tr("Can't", 'পারব না')}</Button>
              </div>
            )}
          </article>
        );
      })}
      {cancel ? (
        <ReasonDialog
          title={tr('Why cancel?', 'কেন বাতিল করছেন?')}
          reasons={DRIVER_REASONS()}
          onClose={() => setCancel(null)}
          onPick={async (reason) => {
            await call('ambreq_step', { id: cancel.id, step: 'cancel', reason });
            setCancel(null);
            onChanged();
            q.refetch();
          }}
        />
      ) : null}
    </section>
  );
}
