// ফার্মেসি — "অর্ডার" ট্যাব (২.২৭.০, পারভেজ "দুটোই সাজাও"; অ্যাপের MyWorkTabs.kt-এর PharmacyOrdersTab):
// উপরে "কোড দিয়ে প্রেসক্রিপশন খুলুন" (কোড লিখে/পেস্ট করে সাথে সাথে খোলে), তারপর নতুন · চলছে/তৈরি · শেষ (সংখ্যাসহ),
// দোকানে আসা আর অনলাইন অর্ডার একই তালিকায় — প্রতিটায় পরিষ্কার লেখা "দোকানে এসেছেন" / "অনলাইন অর্ডার", নতুন আগে।
// ভাগ সার্ভার ঠিক করে (rx_orders_pharmacy-এর seg, counts)। ?s=active|closed — নইলে "নতুন"।
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import { Button, ErrorText, PillTabs, SkeletonCards, Tag, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { CodeBox, ModeTag } from './common';
import { RxPanel } from './dispense';
import { AcceptDialog, CancelOrderDialog, useOrderUpdate } from './online';
import {
  PHARM_SEGS, isOpenOrder, orderStatusShop, orderTone, pharmSegLabel, pharmSegOf, segCounts,
  type PharmOrder, type PharmOrders, type PharmSeg,
} from './model';

const BASE = '/work/pharmacy/orders';

export function OrdersTab({ code, orders }: { code: string | null; orders: UseQueryResult<PharmOrders, Error> }) {
  const nav = useNavigate();
  const [sp, setSp] = useSearchParams();
  const up = useOrderUpdate();
  const [accepting, setAccepting] = useState<PharmOrder | null>(null);
  const [cancelling, setCancelling] = useState<PharmOrder | null>(null);
  const items = orders.data?.items ?? [];
  const counts = segCounts(PHARM_SEGS, orders.data?.counts, pharmSegOf as (x: never) => PharmSeg, items);
  const asked = sp.get('s') as PharmSeg | null;
  const seg: PharmSeg = asked && PHARM_SEGS.includes(asked) ? asked : 'new';
  const rows = items.filter((o) => pharmSegOf(o) === seg).sort((a, c) => c.createdAt - a.createdAt);
  const keep = seg === 'new' ? '' : `?s=${seg}`;

  const open = (o: PharmOrder) => nav(`${BASE}/rx/${o.code}${keep}`);
  const actions = (o: PharmOrder) => {
    if (!isOpenOrder(o)) return null;
    const online = o.mode === 'online';
    return (
      <div className="row wrap gap-6 rxp-row-actions">
        <Button small onClick={() => open(o)}>{online ? tr('Open & note medicines', 'খুলে ওষুধ লিখুন') : tr('Open', 'খুলুন')}</Button>
        {!online && o.status === 'sent' ? (
          <Button small variant="tonal" busy={up.busyId === o.id} disabled={!!up.busyId} onClick={() => up.update(o, 'ready')}>{tr('Ready', 'তৈরি')}</Button>
        ) : null}
        {online && o.status === 'sent' ? <Button small variant="tonal" disabled={!!up.busyId} onClick={() => setAccepting(o)}>{tr('Accept', 'অর্ডার নিলাম')}</Button> : null}
        {online && (o.status === 'sent' || o.status === 'accepted') ? (
          <Button small variant="outline" busy={up.busyId === o.id} disabled={!!up.busyId} onClick={() => up.update(o, 'out')}>{tr('Sent out', 'পাঠিয়ে দিয়েছি')}</Button>
        ) : null}
        <Button small variant="danger-text" disabled={!!up.busyId} onClick={() => setCancelling(o)}>{tr("Can't", 'পারব না')}</Button>
      </div>
    );
  };

  return (
    <div className="col gap-16">
      <section className="card rxp-panel rxp-open-code">
        <div className="row gap-8">
          <span className="rxp-panel-ic"><Icon name="search" /></span>
          <h2 className="t-title-m grow">{tr('Open a prescription by code', 'কোড দিয়ে প্রেসক্রিপশন খুলুন')}</h2>
        </div>
        <CodeBox
          key={code ?? ''}
          autoFocus={!code}
          label={tr('Code under the prescription QR (or paste the link)', 'প্রেসক্রিপশনের QR-এর নিচের কোড (বা লিংক পেস্ট করুন)')}
          onOpen={(c) => nav(`${BASE}/rx/${c}${keep}`)}
        />
        <p className="t-body-s muted">
          {tr('Open with the code and note how many you gave. The patient can get the rest from another pharmacy.', 'কোড দিয়ে খুলে কয়টা দিলেন লিখুন। বাকিটা রোগী অন্য ফার্মেসি থেকে নিতে পারবেন।')}
        </p>
      </section>
      {code ? <RxPanel key={code} code={code} onNext={() => nav(`${BASE}${keep}`)} /> : null}

      <div className="row wrap gap-8">
        <div className="rxp-sub-pills">
          <PillTabs
            secondary
            label={tr('Orders', 'অর্ডার')}
            value={seg}
            onChange={(k) => setSp(k === 'new' ? {} : { s: k }, { replace: true })}
            tabs={PHARM_SEGS.map((s) => ({ key: s, label: pharmSegLabel(s), count: counts[s] }))}
          />
        </div>
        <span className="grow" />
        <Button variant="text" small icon="refresh" busy={orders.isFetching && !orders.isLoading} onClick={() => orders.refetch()}>{tr('Refresh', 'নতুন করে দেখুন')}</Button>
      </div>
      <ErrorText>{up.err}</ErrorText>

      {orders.isLoading ? <SkeletonCards n={3} h={120} /> : orders.error ? <ErrorText>{errorText(orders.error)}</ErrorText> : !rows.length ? (
        <div className="card rxp-empty-card">
          <Icon name={seg === 'new' ? 'check_circle' : seg === 'active' ? 'local_shipping' : 'history'} />
          <span className="t-title-s">
            {seg === 'new' ? tr('No new orders now', 'এখন নতুন কোনো অর্ডার নেই') : seg === 'active' ? tr('Nothing in progress', 'চলমান কোনো অর্ডার নেই') : tr('Nothing finished yet', 'এখনো শেষ হওয়া কোনো অর্ডার নেই')}
          </span>
          {seg === 'new' ? <span className="t-body-s">{tr('When a customer scans your shop QR or sends a prescription, it comes here.', 'ক্রেতা দোকানের QR স্ক্যান করলে বা প্রেসক্রিপশন পাঠালে এখানে আসবে।')}</span> : null}
          {seg === 'active' ? <span className="t-body-s">{tr("Orders you've accepted or made ready stay here until the medicines are given.", 'যে অর্ডার নিয়েছেন বা তৈরি করেছেন, ওষুধ দেওয়া পর্যন্ত এখানে থাকবে।')}</span> : null}
        </div>
      ) : (
        <>
          <div className="rxp-cards">
            {rows.map((o) => {
              const online = o.mode === 'online';
              return (
                <article key={o.id} className={cx('card col gap-6 rxp-ocard', code === o.code && 'on')}>
                  <div className="row gap-8">
                    <span className="t-title-s grow">{o.patientName || '—'}</span>
                    <ModeTag remote={online} icon={online ? 'local_shipping' : 'storefront'}>{online ? tr('Online order', 'অনলাইন অর্ডার') : tr('In the shop', 'দোকানে এসেছেন')}</ModeTag>
                  </div>
                  <div className="row wrap gap-6">
                    <Tag tone={orderTone(o.status)}>{orderStatusShop(o.status)}</Tag>
                    <span className="t-body-s muted" title={formatDateTime(o.createdAt)}>{timeAgo(o.createdAt)}</span>
                  </div>
                  {online && (o.price || o.eta) ? <span className="icon-line"><Icon name="payments" /><span>{[o.price, o.eta].filter(Boolean).join(' · ')}</span></span> : null}
                  {online && o.address ? <span className="icon-line"><Icon name="location_on" /><span>{o.address}</span></span> : null}
                  {online && o.phone ? <span className="icon-line"><Icon name="call" /><a className="rxp-phone num" href={`tel:${o.phone}`}>{num(o.phone)}</a></span> : null}
                  {online && o.deliveryFee && isOpenOrder(o) ? <span className="icon-line"><Icon name="local_shipping" /><span>{tr('Delivery charge: ', 'ডেলিভারি চার্জ: ')}{o.deliveryFee}</span></span> : null}
                  {o.note ? <span className="icon-line"><Icon name="edit_note" /><span>{o.note}</span></span> : null}
                  {o.missed.length ? <span className="t-body-s c-error">{tr('Not available: ', 'নেই: ')}{o.missed.join(', ')}</span> : null}
                  {actions(o)}
                </article>
              );
            })}
          </div>
          {counts[seg] > rows.length ? (
            <p className="t-body-s muted">{tr(`Showing the latest ${rows.length} of ${counts[seg]}`, `মোট ${num(counts[seg])}টার সাম্প্রতিক ${num(rows.length)}টা দেখানো হলো`)}</p>
          ) : null}
        </>
      )}
      {accepting ? <AcceptDialog order={accepting} onClose={() => setAccepting(null)} /> : null}
      {cancelling ? <CancelOrderDialog order={cancelling} onClose={() => setCancelling(null)} /> : null}
    </div>
  );
}
