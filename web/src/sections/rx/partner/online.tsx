// ফার্মেসি — অনলাইন অর্ডার (ক্যাশ অন ডেলিভারি): নেওয়া (দাম, কখন পৌঁছাবে), পথে, পারব না; অনলাইনে বিক্রির সেটিং
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import { Button, Dialog, ErrorText, Loading, PillTabs, SkeletonCards, Switch, Tag, TextInput, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { Panel, useInvalidate, useWideWithSide } from './common';
import { isOpenOrder, orderStatusShop, orderTone, type OnlineSettings, type PharmOrder, type PharmacyHome } from './model';

const BASE = '/work/pharmacy/orders';

/** অর্ডারের অবস্থা বদল (rx_order_update) — রোগী নোটিফিকেশন পান */
export function useOrderUpdate() {
  const invalidate = useInvalidate();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  async function update(o: PharmOrder, status: 'ready' | 'accepted' | 'out' | 'cancelled', extra: Record<string, unknown> = {}) {
    setBusyId(o.id);
    setErr(null);
    try {
      await call('rx_order_update', { id: o.id, status, ...extra });
      toast(tr('The patient was told', 'রোগীকে জানানো হয়েছে'));
      await invalidate('rx_orders_pharmacy', 'rx_pharmacy_home');
      return true;
    } catch (e) {
      setErr(errorText(e));
      return false;
    } finally {
      setBusyId(null);
    }
  }
  return { busyId, err, update };
}

/** "পারব না" — কারণ (রোগী দেখেন) */
export function CancelOrderDialog({ order, onClose }: { order: PharmOrder; onClose: () => void }) {
  const up = useOrderUpdate();
  const [reason, setReason] = useState(tr('Medicines not available', 'ওষুধ নেই'));
  return (
    <Dialog
      size="small"
      onClose={onClose}
      title={tr("Can't provide the medicines?", 'ওষুধ দিতে পারবেন না?')}
      actions={
        <>
          <Button variant="text" onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button variant="danger" busy={!!up.busyId} onClick={async () => { if (await up.update(order, 'cancelled', { reason: reason.trim() })) onClose(); }}>
            {tr("Can't", 'পারব না')}
          </Button>
        </>
      }
    >
      <p className="t-body-m">{order.patientName || '—'}</p>
      <TextInput label={tr('Reason (the patient sees it)', 'কারণ (রোগী দেখবেন)')} value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
      <ErrorText>{up.err}</ErrorText>
    </Dialog>
  );
}

/** অ্যাপের AcceptOrderDialog: মোট দাম আর কখন পৌঁছাবে (দুটোই ঐচ্ছিক) */
function AcceptDialog({ order, onClose }: { order: PharmOrder; onClose: () => void }) {
  const up = useOrderUpdate();
  const [price, setPrice] = useState('');
  const [eta, setEta] = useState('');
  async function accept() {
    if (await up.update(order, 'accepted', { price: price.trim(), eta: eta.trim() })) onClose();
  }
  return (
    <Dialog
      onClose={onClose}
      title={tr('Accept order', 'অর্ডার নিন')}
      actions={
        <>
          <Button variant="text" onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={!!up.busyId} icon="check_circle" onClick={accept}>{tr('Accept', 'নিলাম')}</Button>
        </>
      }
    >
      <div className="col gap-4">
        <span className="t-title-m">{order.patientName || '—'}</span>
        {order.address ? <span className="icon-line"><Icon name="location_on" /><span>{order.address}</span></span> : null}
        {order.deliveryFee ? <span className="icon-line"><Icon name="local_shipping" /><span>{tr('Delivery charge: ', 'ডেলিভারি চার্জ: ')}{order.deliveryFee}</span></span> : null}
      </div>
      <form
        className="col gap-12"
        onSubmit={(e) => {
          e.preventDefault();
          accept();
        }}
      >
        <TextInput label={tr('Total price (optional)', 'মোট দাম (ঐচ্ছিক)')} value={price} maxLength={40} onChange={(e) => setPrice(e.target.value)} placeholder={tr('e.g. Tk 640', 'যেমন: ৬৪০ টাকা')} />
        <TextInput label={tr('When will it arrive (optional)', 'কখন পৌঁছাবে (ঐচ্ছিক)')} value={eta} maxLength={60} onChange={(e) => setEta(e.target.value)} placeholder={tr('e.g. by 7 PM today', 'যেমন: আজ সন্ধ্যা ৭টার মধ্যে')} />
        <button type="submit" hidden />
      </form>
      <p className="t-body-s muted">{tr('The patient pays you on delivery.', 'রোগী ওষুধ পেয়ে আপনাকে দাম দেবেন।')}</p>
      <ErrorText>{up.err}</ErrorText>
    </Dialog>
  );
}

export function OnlineTab({ orders }: { orders: UseQueryResult<{ items: PharmOrder[] }, Error> }) {
  const wide = useWideWithSide();
  const nav = useNavigate();
  const up = useOrderUpdate();
  const [view, setView] = useState<'open' | 'past'>('open');
  const [accepting, setAccepting] = useState<PharmOrder | null>(null);
  const [cancelling, setCancelling] = useState<PharmOrder | null>(null);
  const all = (orders.data?.items ?? []).filter((o) => o.mode === 'online');
  const open = all.filter(isOpenOrder);
  const past = all.filter((o) => !isOpenOrder(o));
  const rows = view === 'open' ? open : past;

  const statusCell = (o: PharmOrder) => (
    <div className="col gap-4">
      <Tag tone={orderTone(o.status)}>{orderStatusShop(o.status)}</Tag>
      {o.price || o.eta ? <span className="t-body-s">{[o.price, o.eta].filter(Boolean).join(' · ')}</span> : null}
      {o.missed.length ? <span className="t-body-s c-error">{tr('Not available: ', 'নেই: ')}{o.missed.join(', ')}</span> : null}
    </div>
  );
  const addressCell = (o: PharmOrder) =>
    o.address || o.phone ? (
      <div className="col gap-2">
        {o.address ? <span>{o.address}</span> : null}
        {o.phone ? <a className="rxp-phone num" href={`tel:${o.phone}`}>{num(o.phone)}</a> : null}
        {o.deliveryFee ? <span className="t-body-s muted">{tr('Delivery charge: ', 'ডেলিভারি চার্জ: ')}{o.deliveryFee}</span> : null}
      </div>
    ) : <span className="muted">—</span>;
  const actions = (o: PharmOrder) =>
    isOpenOrder(o) ? (
      <div className="row wrap gap-6 rxp-row-actions">
        <Button small onClick={() => nav(`${BASE}/rx/${o.code}`)}>{tr('Open & note medicines', 'খুলে ওষুধ লিখুন')}</Button>
        {o.status === 'sent' ? <Button small variant="tonal" disabled={!!up.busyId} onClick={() => setAccepting(o)}>{tr('Accept', 'অর্ডার নিলাম')}</Button> : null}
        {o.status === 'sent' || o.status === 'accepted' ? (
          <Button small variant="outline" busy={up.busyId === o.id} disabled={!!up.busyId} onClick={() => up.update(o, 'out')}>{tr('Sent out', 'পাঠিয়ে দিয়েছি')}</Button>
        ) : null}
        <Button small variant="danger-text" disabled={!!up.busyId} onClick={() => setCancelling(o)}>{tr("Can't", 'পারব না')}</Button>
      </div>
    ) : null;

  return (
    <div className="rxp-grid">
      <div className="col gap-12 rxp-main">
        <div className="row wrap gap-8">
          <div className="rxp-sub-pills">
            <PillTabs
              secondary
              value={view}
              onChange={(k) => setView(k as 'open' | 'past')}
              label={tr('Online orders', 'অনলাইন অর্ডার')}
              tabs={[
                { key: 'open', label: tr('Active', 'চালু'), count: open.length },
                { key: 'past', label: tr('Earlier', 'আগের') },
              ]}
            />
          </div>
          <span className="grow" />
          <Button variant="text" small icon="refresh" busy={orders.isFetching && !orders.isLoading} onClick={() => orders.refetch()}>{tr('Refresh', 'নতুন করে দেখুন')}</Button>
        </div>
        {view === 'open' ? (
          <p className="t-body-s muted">{tr('After giving the medicines, use "Open & note medicines" to write what you gave — the order is then complete.', 'ওষুধ দেওয়ার পর "খুলে ওষুধ লিখুন" থেকে যা দিলেন লিখুন — অর্ডার সম্পন্ন হবে।')}</p>
        ) : null}
        <ErrorText>{up.err}</ErrorText>
        {orders.isLoading ? <SkeletonCards n={3} h={96} /> : orders.error ? <ErrorText>{errorText(orders.error)}</ErrorText> : !rows.length ? (
          <div className="card rxp-empty-card">
            <Icon name="local_shipping" />
            <span>{view === 'open' ? tr('No online orders now', 'এখন কোনো অনলাইন অর্ডার নেই') : tr('Nothing yet', 'এখনো কিছু নেই')}</span>
          </div>
        ) : wide ? (
          <div className="table-wrap">
            <table className="table rxp-table">
              <thead>
                <tr>
                  <th>{tr('Patient', 'রোগী')}</th>
                  <th>{tr('Address & phone', 'ঠিকানা ও ফোন')}</th>
                  <th>{tr('Status', 'অবস্থা')}</th>
                  {view === 'open' ? <th><span className="sr-only">{tr('Actions', 'কাজ')}</span></th> : null}
                </tr>
              </thead>
              <tbody>
                {rows.map((o) => (
                  <tr key={o.id}>
                    <td className="rxp-td-who">
                      <div className="col gap-2">
                        <span className="semi">{o.patientName || '—'}</span>
                        <span className="t-body-s muted" title={formatDateTime(o.createdAt)}>{timeAgo(o.createdAt)}</span>
                        {o.note ? <span className="t-body-s">{o.note}</span> : null}
                      </div>
                    </td>
                    <td className="rxp-td-addr">{addressCell(o)}</td>
                    <td>{statusCell(o)}</td>
                    {view === 'open' ? <td className="rxp-td-actions">{actions(o)}</td> : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="rxp-cards">
            {rows.map((o) => (
              <article key={o.id} className={cx('card col gap-8')}>
                <div className="row">
                  <span className="t-title-s grow">{o.patientName || '—'}</span>
                  <span className="t-body-s muted">{timeAgo(o.createdAt)}</span>
                </div>
                {addressCell(o)}
                {o.note ? <span className="t-body-s">{o.note}</span> : null}
                {statusCell(o)}
                {actions(o)}
              </article>
            ))}
          </div>
        )}
      </div>
      <aside className="col gap-16 rxp-side">
        <OnlineSettingsPanel />
      </aside>
      {accepting ? <AcceptDialog order={accepting} onClose={() => setAccepting(null)} /> : null}
      {cancelling ? <CancelOrderDialog order={cancelling} onClose={() => setCancelling(null)} /> : null}
    </div>
  );
}

/** অ্যাপের OnlineSettingsDialog: চালু/বন্ধ, কত কিমি, ডেলিভারি চার্জ, নোট */
function OnlineSettingsPanel() {
  const home = useCall<PharmacyHome>('rx_pharmacy_home', {});
  const h = home.data;
  return (
    <Panel
      title={tr('Selling online', 'অনলাইনে বিক্রি')}
      icon="local_shipping"
      actions={h ? <Tag tone={h.online.enabled ? 'good' : undefined}>{h.online.enabled ? tr('Selling online', 'অনলাইনে বিক্রি চালু') : tr('In-shop only', 'শুধু দোকানে')}</Tag> : null}
    >
      {!h ? (home.error ? <ErrorText>{errorText(home.error)}</ErrorText> : <Loading />) : <OnlineForm key={JSON.stringify(h.online)} start={h.online} hasLocation={h.hasLocation} />}
    </Panel>
  );
}

function OnlineForm({ start, hasLocation }: { start: OnlineSettings; hasLocation: boolean }) {
  const invalidate = useInvalidate();
  const [enabled, setEnabled] = useState(start.enabled);
  const [radius, setRadius] = useState(String(start.radiusKm || 3));
  const [fee, setFee] = useState(start.fee);
  const [note, setNote] = useState(start.note);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function save() {
    setBusy(true);
    setErr(null);
    try {
      const r = Math.min(30, Math.max(1, parseInt(toAsciiDigits(radius), 10) || 3));
      await call('rx_pharmacy_online_set', { enabled, radiusKm: r, fee: fee.trim(), note: note.trim() });
      toast(tr('Saved', 'সেভ হয়েছে'));
      await invalidate('rx_pharmacy_home');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="col gap-12"
      onSubmit={(e) => {
        e.preventDefault();
        save();
      }}
    >
      <Switch checked={enabled} onChange={setEnabled} label={tr('Take prescription orders and deliver', 'প্রেসক্রিপশনের অর্ডার নিই, বাসায় পৌঁছে দিই')} />
      <TextInput
        label={tr('Up to how many km', 'কত কিমি পর্যন্ত')}
        inputMode="numeric"
        value={num(radius)}
        onChange={(e) => setRadius(toAsciiDigits(e.target.value).replace(/\D/g, '').slice(0, 2))}
        hint={tr('1 to 30 km', '১ থেকে ৩০ কিমি')}
      />
      <TextInput label={tr('Delivery charge (e.g. Tk 30)', 'ডেলিভারি চার্জ (যেমন: ৩০ টাকা)')} value={fee} maxLength={40} onChange={(e) => setFee(e.target.value)} />
      <TextInput label={tr('Note (e.g. delivery 10 AM – 9 PM)', 'নোট (যেমন: সকাল ১০টা–রাত ৯টা)')} value={note} maxLength={120} onChange={(e) => setNote(e.target.value)} />
      <p className="t-body-s muted">{tr('Orders come only from verified prescriptions. Customers pay you directly — Lifolo takes nothing.', 'অর্ডার আসবে শুধু যাচাই করা প্রেসক্রিপশন থেকে। ক্রেতা টাকা সরাসরি আপনাকে দেবেন — Lifolo কিছু নেয় না।')}</p>
      {!hasLocation ? (
        <p className="t-body-s c-error">
          {tr("Add your shop's location first (edit your pharmacy).", 'আগে দোকানের লোকেশন দিন (ফার্মেসির তথ্য বদলে)।')}{' '}
          <Link to="/work/pharmacy/edit" className="c-primary">{tr('Edit details', 'তথ্য বদলান')}</Link>
        </p>
      ) : null}
      <ErrorText>{err}</ErrorText>
      <div className="row">
        <span className="grow" />
        <Button type="submit" busy={busy}>{tr('Save', 'সেভ করুন')}</Button>
      </div>
    </form>
  );
}
