// ফার্মেসি — অনলাইন অর্ডারের কাজ (ক্যাশ অন ডেলিভারি): নেওয়া (দাম, কখন পৌঁছাবে), পথে, পারব না (তালিকা orders.tsx-এ); অনলাইনে বিক্রির সেটিং
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Dialog, ErrorText, Loading, Switch, Tag, TextInput, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { Panel, useInvalidate } from './common';
import { type OnlineSettings, type PharmOrder, type PharmacyHome } from './model';

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
export function AcceptDialog({ order, onClose }: { order: PharmOrder; onClose: () => void }) {
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

/** অ্যাপের OnlineSettingsDialog: চালু/বন্ধ, কত কিমি, ডেলিভারি চার্জ, নোট */
export function OnlineSettingsPanel() {
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
