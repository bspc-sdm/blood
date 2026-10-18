// প্রেসক্রিপশনের ভেতরে: ওষুধ কেনা (দোকানে / অনলাইনে, ফিরিয়ে নেওয়া), পরীক্ষা পাঠানো (কোথায় কয়টা হয়, আনুমানিক দাম,
// বাসা থেকে নমুনা), রিপোর্ট, ডাক্তারকে দেখানো —
// অ্যাপের RxNet.kt: RxActivitySection, PharmacyPickDialog, OnlineOrderDialog, WithdrawDialog, LabPickDialog,
// LabReportDialog/LabReportView, ReportReviewBox, PayDialog
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Action, Button, ChoiceChips, ConfirmDialog, Dialog, DirectoryCard, EmptyState, ErrorText, FilterBar, IconLine, ListRow, Loading, SkeletonCards, Tag, TextArea, TextInput, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { AreaPicker, type AreaValue } from '@/ui/AreaPicker';
import { call, errorText } from '@/lib/api';
import { num, tr, useLang } from '@/lib/i18n';
import { formatDate, formatKm, todayUtcMidnight } from '@/lib/format';
import { areaLabel } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import {
  NO_PRICE, SLOT_KEYS, breakdownText, coverEstimateText, dayWord, discountText, hereText, homeEditable, homeFeeText, homeStatusPatient, slotPassed, slotText, snapText, taka,
  type LabHome, type PriceSnap, type Slot,
} from '@/lib/labprice';
import { imgSrc } from './common';
import type { Rx } from './Prescriptions';

interface MedStat { i: number; name: string; form: string; dose: string; days: number; need: number | null; got: number; left: number | null; done: boolean }
interface Dispensing { name: string; at: number; items: { i: number; qty: number }[] }
interface RxOrder { id: string; pharmacyName: string; status: string; reason: string; createdAt: number; mode: string; price: string; eta: string; deliveryFee: string; missed: string[] }
interface LabResult { test: string; result: string; unit: string; range: string; flag: string; group?: string; code?: string; key?: string; long?: boolean }
interface LabOrder {
  id: string; rxId: string; code: string; labName: string; patientName: string; patientAge: number | null; patientGender: string; doctorName: string;
  tests: string[]; status: string; createdAt: number; report: { items: LabResult[]; note: string; reportedAt: number; photoCount: number; photos?: string[] } | null;
  prices?: PriceSnap | null; home?: LabHome | null;
}
interface RxReview { id: string; labOrderId: string; doctorName: string; status: string; fee: string; noFee: boolean; payNumber: string; payMethods: string[]; rejectNote: string; comment: string }
interface Activity { medicines: MedStat[]; dispensed: Dispensing[]; orders: RxOrder[]; labOrders: LabOrder[]; reviews: RxReview[]; nextOpen: number | null }
interface PharmOption {
  id: string; name: string; phone: string; address: string; km: number | null; openNow: boolean; keepsStock: boolean; have: number; total: number; missing: string[];
  online: boolean; deliveryFee: string; onlineNote: string;
  /** আনুমানিক খরচ (দোকানের হিসাব-খাতার দাম): total পুরো টাকা, priced কয়টার দাম জানা, of কেনা বাকি কয়টা — একটারও দাম না জানলে null */
  estimate?: { total: number; priced: number; of: number } | null;
}
/** "আনুমানিক ৳৪৮০ (৫টার মধ্যে ৪টার দাম জানা)" / "আনুমানিক ৳৪৮০" / "দাম দোকানে জেনে নিন" */
function pharmEstimateText(e: PharmOption['estimate']): string {
  if (!e) return tr('Ask the shop for the price', 'দাম দোকানে জেনে নিন');
  if (e.priced < e.of) return tr(`About ${taka(e.total)} (price known for ${e.priced} of ${e.of})`, `আনুমানিক ${taka(e.total)} (${num(e.of)}টার মধ্যে ${num(e.priced)}টার দাম জানা)`);
  return tr(`About ${taka(e.total)}`, `আনুমানিক ${taka(e.total)}`);
}
/** দোকানের পাতা থেকে: "এখানে আছে ৩/৫টা ওষুধ; নেই: X, Y" — স্টকের তালিকা না রাখলে "স্টকের তালিকা রাখে না — দোকানে জেনে নিন" */
function pharmHereText(p: PharmOption): string {
  if (!p.keepsStock) return tr("Doesn't keep a stock list — ask at the shop", 'স্টকের তালিকা রাখে না — দোকানে জেনে নিন');
  return tr(`Has ${p.have}/${p.total} medicines`, `এখানে আছে ${num(p.have)}/${num(p.total)}টা ওষুধ`) + (p.missing.length ? tr('; missing: ', '; নেই: ') + p.missing.join(', ') : '');
}
/** rx_lab_options — এই প্রেসক্রিপশনের পরীক্ষা কোথায় কয়টা হয়, আনুমানিক কত */
interface LabOption {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null; homeCollection: boolean; hours: string;
  suggested: boolean; priced: boolean; have: number | null; total: number; missing: string[]; items: { test: string; name: string; price: number; tat: string }[];
  subtotal: number | null; discountPct: number | null; estimate: number | null; homeFee: number | null; tat: string;
}
interface LastHome { address: string; phone: string }
/** সেবার তালিকার প্যাথলজি (services/data-র Lab) — পাতা থেকে পাঠানোর সময় */
export interface PlaceLab {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null;
  homeCollection: boolean; hours: string; prices?: { homeFee: number | null } | null;
}
/** সেবার তালিকার ফার্মেসি — যতটুকু লাগে */
export interface PlacePharmacy { id: string; name: string; district: string }
/** বাসা থেকে নমুনার অনুরোধ (rx_send_lab / lab_home_change-এর home): day ০ আজ, ১ কাল, ২ পরশু */
interface HomeReq { address: string; phone: string; day: number; slot: Slot; note: string }

/** মান বদলানোর পর ms পর্যন্ত থেমে থাকলে তবেই নতুন মান (পরীক্ষার চিপ টিপতে টিপতে প্রতিবার সার্ভার ডাকা নয়) */
function useDebounced<T>(v: T, ms: number): T {
  const [d, setD] = useState(v);
  useEffect(() => {
    const t = setTimeout(() => setD(v), ms);
    return () => clearTimeout(t);
  }, [v, ms]);
  return d;
}

// ---------- লেখা (অ্যাপের RxNetText) ----------
const payLabel = (m: string) => ({ bkash: 'bKash', nagad: 'Nagad', rocket: 'Rocket' })[m] ?? m;
function orderStatus(s: string) {
  switch (s) {
    case 'sent': return tr('Sent — waiting', 'পাঠানো হয়েছে — অপেক্ষায়');
    case 'ready': return tr('Ready to collect', 'তৈরি — নিয়ে আসুন');
    case 'accepted': return tr('Order accepted', 'অর্ডার নিয়েছে');
    case 'out': return tr('On the way', 'পথে আছে');
    case 'done': return tr('Given', 'দেওয়া হয়েছে');
    case 'cancelled': return tr("Can't provide", 'দিতে পারবে না');
    case 'withdrawn': return tr('Taken back', 'ফিরিয়ে নিয়েছেন');
    default: return s;
  }
}
function labStatus(s: string) {
  switch (s) {
    case 'sent': return tr('Sent — waiting', 'পাঠানো হয়েছে — অপেক্ষায়');
    case 'received': return tr('Sample taken', 'নমুনা নেওয়া হয়েছে');
    case 'reported': return tr('Report ready', 'রিপোর্ট এসেছে');
    case 'cancelled': return tr("Can't do", 'করতে পারবে না');
    default: return s;
  }
}
function reviewStatus(s: string) {
  switch (s) {
    case 'unpaid': return tr('Not paid', 'টাকা দেওয়া হয়নি');
    case 'claimed': return tr('Payment sent — doctor is checking', 'টাকা পাঠিয়েছেন — ডাক্তার মিলিয়ে দেখছেন');
    case 'paid': return tr('Doctor is checking the report', 'ডাক্তার রিপোর্ট দেখছেন');
    case 'done': return tr('Doctor checked', 'ডাক্তার দেখেছেন');
    default: return '';
  }
}
const flagLabel = (f: string) => (f === 'high' ? tr('High', 'বেশি') : f === 'low' ? tr('Low', 'কম') : f === 'abnormal' ? tr('Abnormal', 'অস্বাভাবিক') : '');
function boughtText(m: MedStat) {
  if (m.need != null) return tr(`Bought ${m.got}/${m.need}`, `কেনা ${num(m.got)}/${num(m.need)}`);
  if (m.got > 0) return tr(`Bought ${m.got}`, `কেনা ${num(m.got)}`) + (m.days === 0 ? tr(' (continue)', ' (চলবে)') : '');
  return tr('Not bought yet', 'এখনো কেনা হয়নি');
}
const OPEN = ['sent', 'ready', 'accepted', 'out'];
const callTel = (phone: string) => {
  if (phone) window.location.href = `tel:${phone}`;
};

/** এলাকা: প্রোফাইলের জেলা দিয়ে শুরু */
function useStartArea(): AreaValue | null {
  const s = useSession();
  const d = s.profile?.placeName || s.place?.placeName || '';
  return d ? { district: d, upazilaId: null } : null;
}

// ---------- মূল অংশ ----------
/** paper দিলে: উপরে বড় "ফার্মেসিতে পাঠান" / "প্যাথলজিতে পাঠান", তারপর কাগজ, তারপর বাকিটা — লম্বা কাগজের নিচে বাটন খুঁজে পাওয়া যেত না */
export function RxActivitySection({ rx, paper }: { rx: Rx; paper?: ReactNode }) {
  const lang = useLang();
  const care = useCareMode();
  const { uid } = useSession();
  const qc = useQueryClient();
  const key = ['rx_activity', rx.id, care?.uid ?? null, lang, uid];
  const q = useQuery({ queryKey: key, queryFn: () => call<Activity>('rx_activity', { rxId: rx.id }) });
  // কাজ হলে টোস্ট (পর্দার নিচে) — আগে উপরের এক লাইনে যেত, নিচ থেকে পাঠালে স্ক্রল না করে দেখা যেত না
  const setInfo = (m: string) => toast(m);
  const [err, setErr] = useState<string | null>(null);
  const [pick, setPick] = useState<'shop' | 'online' | null>(null);
  const [withdrawing, setWithdrawing] = useState<RxOrder | null>(null);
  const [pickLab, setPickLab] = useState(false);
  const [sendChoice, setSendChoice] = useState(false);
  const [homeEdit, setHomeEdit] = useState<LabOrder | null>(null);
  const [homeCancel, setHomeCancel] = useState<LabOrder | null>(null);
  const [homeBusy, setHomeBusy] = useState(false);
  const [homeErr, setHomeErr] = useState<string | null>(null);
  const [report, setReport] = useState<string | null>(null);
  const [paying, setPaying] = useState<RxReview | null>(null);
  const [serialBusy, setSerialBusy] = useState(false);
  const reload = () => qc.invalidateQueries({ queryKey: ['rx_activity', rx.id] });
  const cancelled = rx.status === 'cancelled';

  // লোড হওয়ার সময় উপরের বাটন নেই, কাগজ আগের মতোই দেখায়
  if (q.isPending) return <>{paper}<SkeletonCards n={1} h={120} /></>;
  if (q.isError) return <>{paper}<ErrorText>{errorText(q.error)}</ErrorText></>;
  const a = q.data;
  // নিচের বাটনের মতোই শর্ত: বাতিল নয়, আর কোনো ওষুধ এখনো পুরো কেনা হয়নি / পরীক্ষা আছে
  const canPharmacy = !cancelled && !!rx.medicines?.length && a.medicines.some((m) => !m.done);
  const canLab = !cancelled && !!rx.tests?.length;

  async function serial(date: number) {
    setSerialBusy(true);
    setErr(null);
    try {
      await call('book', { providerUid: rx.doctorUid, chamberIndex: rx.chamberIndex ?? 0, date, note: tr('Show the report', 'রিপোর্ট দেখানো') });
      setInfo(tr('Serial request sent', 'সিরিয়ালের অনুরোধ গেছে'));
    } catch (e) {
      setErr(errorText(e));
    }
    setSerialBusy(false);
  }

  return (
    <>
      {canPharmacy || canLab ? (
        <div className={canPharmacy && canLab ? 'health-two health-send' : 'col health-send'}>
          {canPharmacy ? <Button icon="medication" block onClick={() => setSendChoice(true)}>{tr('Send to a pharmacy', 'ফার্মেসিতে পাঠান')}</Button> : null}
          {canLab ? <Button icon="local_hospital" block onClick={() => setPickLab(true)}>{tr('Send to a lab', 'প্যাথলজিতে পাঠান')}</Button> : null}
        </div>
      ) : null}
      <ErrorText>{err}</ErrorText>
      {paper}
      {rx.medicines?.length ? (
        <section className="card col" aria-label={tr('Medicines bought', 'ওষুধ কেনা')}>
          <h3 className="t-title-m">{tr('Medicines bought', 'ওষুধ কেনা')}</h3>
          {a.medicines.map((m) => (
            <div key={m.i} className="row">
              <span className="t-body-l grow">{m.name}</span>
              <Tag tone={m.done ? 'good' : undefined}>{boughtText(m)}</Tag>
            </div>
          ))}
          {a.dispensed.map((d, i) => (
            <span key={i} className="t-body-s muted">{d.name + ' · ' + formatDate(d.at)}</span>
          ))}
          {a.orders.map((o) => {
            const extra = [o.price, o.eta, o.deliveryFee ? tr(`Delivery: ${o.deliveryFee}`, `ডেলিভারি: ${o.deliveryFee}`) : ''].filter(Boolean);
            return (
              <div key={o.id} className="col gap-4">
                <IconLine icon={o.mode === 'online' ? 'local_shipping' : 'storefront'}>
                  {o.pharmacyName + ' — ' + orderStatus(o.status) + (o.reason ? ` (${o.reason})` : '')}
                </IconLine>
                {OPEN.includes(o.status) && extra.length ? <span className="t-body-s muted">{extra.join(' · ')}</span> : null}
                {o.missed?.length ? <span className="t-body-s muted">{tr("Didn't have: ", 'ছিল না: ') + o.missed.join(', ')}</span> : null}
                {['sent', 'ready', 'accepted'].includes(o.status) ? (
                  <div><Action icon="close" onClick={() => setWithdrawing(o)}>{tr('Take back', 'ফিরিয়ে নিন')}</Action></div>
                ) : null}
              </div>
            );
          })}
          {!cancelled && a.medicines.some((m) => !m.done) ? (
            <>
              {/* উপরের বড় বাটনের মতোই একটা — একই বাছাই (দোকানে আছি / অনলাইনে অর্ডার); আগের দুই বাটন ভেঙে দুই লাইনে যেত */}
              <Button variant="outline" icon="medication" block onClick={() => setSendChoice(true)}>{tr('Send to a pharmacy', 'ফার্মেসিতে পাঠান')}</Button>
              <span className="t-body-s muted">
                {tr(
                  'At the shop: send the prescription to it in the app, or show the QR. They note what they give; get the rest from another shop.',
                  'দোকানে গিয়ে: অ্যাপে সেই দোকানে পাঠান, অথবা QR দেখান। যতটা দেবে লিখে রাখবে; বাকিটা অন্য দোকান থেকে নিন।',
                )}
              </span>
            </>
          ) : null}
        </section>
      ) : null}

      {rx.tests?.length ? (
        <section className="card col" aria-label={tr('Tests and reports', 'পরীক্ষা ও রিপোর্ট')}>
          <h3 className="t-title-m">{tr('Tests and reports', 'পরীক্ষা ও রিপোর্ট')}</h3>
          {a.labOrders.map((o) => {
            const r = a.reviews.find((x) => x.labOrderId === o.id) ?? null;
            return (
              <div key={o.id} className="col gap-6 health-lab-order">
                <div className="row">
                  <span className="t-body-l semi grow">{o.labName}</span>
                  <Tag tone={o.status === 'reported' ? 'good' : undefined}>{labStatus(o.status)}</Tag>
                </div>
                <span className="t-body-s muted">{o.tests.join(', ')}</span>
                {/* পাঠানোর সময়ের আনুমানিক দাম; তালিকায় না থাকা পরীক্ষা ("ECG দাম জানা নেই") */}
                {o.prices && o.status !== 'cancelled' ? <span className="t-body-s">{snapText(o.prices, o.home)}</span> : null}
                {o.home && ['sent', 'received'].includes(o.status) ? (
                  <IconLine icon="home_health" color={o.home.status === 'declined' || o.home.status === 'expired' ? 'var(--error)' : o.home.status === 'confirmed' ? 'var(--primary)' : undefined}>{homeStatusPatient(o.home)}</IconLine>
                ) : null}
                {o.home?.status === 'confirmed' && o.home.confirmNote && o.status === 'sent' ? <span className="t-body-s muted">{o.home.confirmNote}</span> : null}
                {/* প্যাথলজি সময় জানানোর আগে (সময় পেরিয়ে গেলেও): সময় বদলানো, বা বাসা থেকে নয় — প্যাথলজিতে গিয়ে */}
                {!cancelled && homeEditable(o.home, o.status) ? (
                  <div className="row wrap gap-8">
                    <Action primary={o.home?.status === 'expired'} icon="edit" onClick={() => setHomeEdit(o)}>{tr('Change time', 'সময় বদলান')}</Action>
                    <Action icon="close" onClick={() => { setHomeErr(null); setHomeCancel(o); }}>{tr("I'll go to the lab", 'প্যাথলজিতে গিয়ে দেব')}</Action>
                  </div>
                ) : null}
                {o.status === 'reported' ? (
                  <>
                    <div><Action primary icon="list_alt" onClick={() => setReport(o.id)}>{tr('See report', 'রিপোর্ট দেখুন')}</Action></div>
                    <ReportReviewBox rx={rx} r={r} nextOpen={a.nextOpen} busy={serialBusy} onPay={setPaying} onSerial={serial} />
                  </>
                ) : null}
              </div>
            );
          })}
          {!cancelled ? <Button variant="outline" icon="local_hospital" block onClick={() => setPickLab(true)}>{tr('Send tests to a lab', 'পরীক্ষা করাতে পাঠান')}</Button> : null}
        </section>
      ) : null}

      {sendChoice ? (
        <Dialog onClose={() => setSendChoice(false)} size="small" title={tr('Send to a pharmacy', 'ফার্মেসিতে পাঠান')}>
          <div className="col gap-4">
            <ListRow icon="storefront" title={tr("I'm at a pharmacy — send to this shop", 'দোকানে আছি — এই দোকানে পাঠান')} onClick={() => { setSendChoice(false); setPick('shop'); }} />
            <ListRow icon="local_shipping" title={tr('Order online — home delivery', 'অনলাইনে অর্ডার — বাসায় ডেলিভারি')} onClick={() => { setSendChoice(false); setPick('online'); }} />
          </div>
        </Dialog>
      ) : null}
      {pick ? (
        <PharmacyPickDialog
          rx={rx}
          mode={pick}
          onClose={() => setPick(null)}
          onSent={(name) => {
            setInfo(pick === 'online' ? tr(`Order sent to ${name}. Pay when the medicines arrive.`, `${name}-এ অর্ডার গেছে। ওষুধ পৌঁছালে দাম দেবেন।`) : tr(`Sent to ${name}`, `${name}-এ পাঠানো হয়েছে`));
            setPick(null);
            reload();
          }}
        />
      ) : null}
      {withdrawing ? (
        <WithdrawDialog
          o={withdrawing}
          onClose={() => setWithdrawing(null)}
          onDone={() => {
            setWithdrawing(null);
            setInfo(tr('Taken back. You can send it to another pharmacy.', 'ফিরিয়ে নেওয়া হয়েছে। অন্য ফার্মেসিতে পাঠাতে পারেন।'));
            reload();
          }}
        />
      ) : null}
      {homeEdit?.home ? (
        <LabSendDialog
          title={homeEdit.labName}
          change={homeEdit.home}
          homeFee={homeEdit.home.fee}
          last={null}
          onClose={() => setHomeEdit(null)}
          onSubmit={async (home) => {
            await call('lab_home_change', { id: homeEdit.id, home });
            setHomeEdit(null);
            setInfo(tr('The lab was told the new time', 'প্যাথলজিকে নতুন সময় জানানো হয়েছে'));
            reload();
          }}
        />
      ) : null}
      {homeCancel ? (
        <ConfirmDialog
          title={tr('No sample from home?', 'বাসা থেকে নমুনা লাগবে না?')}
          text={tr('The tests stay with this lab — go there to give the sample. The lab will be told.', 'পরীক্ষা এই প্যাথলজিতেই থাকবে — সেখানে গিয়ে নমুনা দেবেন। প্যাথলজিকে জানানো হবে।')}
          confirm={tr("I'll go to the lab", 'প্যাথলজিতে গিয়ে দেব')}
          busy={homeBusy}
          error={homeErr}
          onClose={() => !homeBusy && setHomeCancel(null)}
          onConfirm={async () => {
            setHomeBusy(true);
            setHomeErr(null);
            try {
              await call('lab_home_cancel', { id: homeCancel.id });
              setHomeCancel(null);
              setInfo(tr('The lab was told — go there to give the sample', 'প্যাথলজিকে জানানো হয়েছে — সেখানে গিয়ে নমুনা দিন'));
              reload();
            } catch (e) {
              setHomeErr(errorText(e));
            }
            setHomeBusy(false);
          }}
        />
      ) : null}
      {pickLab ? (
        <LabPickDialog
          rx={rx}
          onClose={() => setPickLab(false)}
          onSent={(name) => {
            setPickLab(false);
            setInfo(tr(`Sent to ${name}`, `${name}-এ পাঠানো হয়েছে`));
            reload();
          }}
        />
      ) : null}
      {report ? <LabReportDialog id={report} onClose={() => setReport(null)} /> : null}
      {paying ? (
        <PayDialog
          r={paying}
          onClose={() => setPaying(null)}
          onDone={() => {
            setPaying(null);
            setInfo(tr('Sent. The doctor will check the payment.', 'পাঠানো হয়েছে। ডাক্তার টাকা মিলিয়ে দেখবেন।'));
            reload();
          }}
        />
      ) : null}
    </>
  );
}

/** রিপোর্ট আসার পর: সিরিয়াল নিয়ে দেখানো, অথবা অনলাইনে (ফি দিয়ে) */
function ReportReviewBox({ rx, r, nextOpen, busy, onPay, onSerial }: { rx: Rx; r: RxReview | null; nextOpen: number | null; busy: boolean; onPay: (r: RxReview) => void; onSerial: (d: number) => void }) {
  const live = r && r.status && r.status !== 'off';
  return (
    <div className="col gap-6">
      {live && r ? (
        <>
          <span className="t-body-m c-primary">{reviewStatus(r.status)}</span>
          {r.status === 'unpaid' ? (
            <>
              <span className="t-body-m">
                {tr(`Show online: fee ${r.fee}. Send to ${r.payNumber} (`, `অনলাইনে দেখান: ফি ${r.fee}। ${num(r.payNumber)} নম্বরে (`) + r.payMethods.map(payLabel).join(', ') + tr('), then enter the TrxID.', ') পাঠিয়ে TrxID দিন।')}
              </span>
              {r.rejectNote ? <span className="t-body-s c-error">{tr("Doctor's note: ", 'ডাক্তারের নোট: ') + r.rejectNote}</span> : null}
              <div><Action primary icon="payments" onClick={() => onPay(r)}>{tr("I've sent the money", 'টাকা পাঠিয়েছি')}</Action></div>
            </>
          ) : null}
          {r.status === 'done' && r.comment ? (
            <div className="card tonal col gap-2">
              <span className="t-label-l semi">{r.doctorName}</span>
              <p className="t-body-m pre">{r.comment}</p>
            </div>
          ) : null}
        </>
      ) : null}
      {nextOpen != null && rx.chamberIndex != null && r?.status !== 'done' ? (
        <div>
          <Action icon="calendar_month" busy={busy} onClick={() => onSerial(nextOpen)}>
            {tr('Get a serial to show it (', 'দেখানোর সিরিয়াল নিন (') + formatDate(nextOpen) + ')'}
          </Action>
        </div>
      ) : null}
    </div>
  );
}

// ---------- ফার্মেসি বাছাই ----------
function PharmacyPickDialog({ rx, mode, onClose, onSent }: { rx: Rx; mode: 'shop' | 'online'; onClose: () => void; onSent: (name: string) => void }) {
  const s = useSession();
  const start = useStartArea();
  const [area, setArea] = useState<AreaValue | null>(start);
  const [showArea, setShowArea] = useState(false);
  const [here, setHere] = useState<{ lat: number; lng: number } | null>(null);
  const [located, setLocated] = useState(mode !== 'shop');
  const [name, setName] = useState('');
  const [nameQ, setNameQ] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [ordering, setOrdering] = useState<PharmOption | null>(null);

  // দোকানে দাঁড়িয়ে: অনুমতি আগেই দেওয়া থাকলে এখনকার লোকেশন (নতুন করে অনুমতি চাওয়া নয়)
  useEffect(() => {
    if (located) return;
    let off = false;
    const done = () => {
      if (!off) setLocated(true);
    };
    const perms = navigator.permissions;
    if (!perms || !navigator.geolocation) {
      done();
      return;
    }
    perms
      .query({ name: 'geolocation' as PermissionName })
      .then((p) => {
        if (p.state !== 'granted') return done();
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            if (!off) setHere({ lat: pos.coords.latitude, lng: pos.coords.longitude });
            done();
          },
          done,
          { timeout: 8000, maximumAge: 60000 },
        );
      })
      .catch(done);
    return () => {
      off = true;
    };
  }, [located]);

  useEffect(() => {
    const t = setTimeout(() => setNameQ(name.trim()), name.trim() ? 400 : 0);
    return () => clearTimeout(t);
  }, [name]);

  const gps = s.profile?.locationSource === 'gps' && s.profile.lat != null && s.profile.lng != null ? { lat: s.profile.lat, lng: s.profile.lng } : null;
  const loc = here ?? gps;
  const q = useQuery({
    queryKey: ['rx_pharmacy_options', rx.id, mode, area?.district, area?.upazilaId ?? null, nameQ, loc?.lat ?? null, loc?.lng ?? null],
    queryFn: () =>
      call<{ items?: PharmOption[] }>('rx_pharmacy_options', {
        rxId: rx.id, district: area!.district, mode, q: nameQ,
        ...(area?.upazilaId ? { upazilaId: area.upazilaId } : {}),
        ...(loc ? { lat: loc.lat, lng: loc.lng } : {}),
      }).then((r) => r.items ?? []),
    enabled: !!area && located,
    staleTime: 0,
  });

  async function send(id: string, nm: string) {
    if (busy) return;
    setBusy(id);
    setErr(null);
    try {
      await call('rx_send_pharmacy', { rxId: rx.id, pharmacyId: id, mode: 'shop' });
      onSent(nm);
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(null);
  }
  async function sendByCode() {
    setBusy('code');
    setErr(null);
    try {
      const r = await call<{ item: { id: string; name: string } }>('rx_shop_find', { code: shopCode });
      await call('rx_send_pharmacy', { rxId: rx.id, pharmacyId: r.item.id, mode: 'shop' });
      onSent(r.item.name);
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(null);
  }

  const online = mode === 'online';
  return (
    <Dialog onClose={() => !busy && onClose()} size="wide" title={online ? tr('Order online', 'অনলাইনে অর্ডার') : tr('Send to this shop', 'এই দোকানে দিন')}>
      {!online ? (
        <>
          <TextInput label={tr("Type the shop's name", 'দোকানের নাম লিখুন')} value={name} maxLength={60} onChange={(e) => setName(e.target.value)} type="search" />
          <div className="row top health-code-row">
            <TextInput
              label={tr('Or the shop code', 'অথবা দোকানের কোড')}
              value={shopCode}
              autoComplete="off"
              spellCheck={false}
              onChange={(e) => setShopCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6))}
              className="input health-code"
            />
            <Button variant="outline" square busy={busy === 'code'} disabled={!!busy || shopCode.length !== 6} onClick={sendByCode}>{tr('Send', 'পাঠান')}</Button>
          </div>
        </>
      ) : (
        <p className="t-body-s muted">{tr("Only pharmacies that deliver. You pay the shop when the medicines arrive — Lifolo doesn't take money.", 'শুধু যাঁরা বাসায় পৌঁছে দেন। ওষুধ পৌঁছালে দোকানকে দাম দেবেন — Lifolo টাকা নেয় না।')}</p>
      )}
      <FilterBar areaLabel={area ? areaLabel(area.district, area.upazilaId) : ''} onArea={() => setShowArea(true)} />
      {!online ? <span className="t-label-l">{nameQ ? tr('Shops with this name', 'এই নামের দোকান') : tr('Shops near you', 'কাছের দোকান')}</span> : null}
      <SendError>{err}</SendError>
      <div className="col gap-12">
        {!area ? (
          <EmptyState icon="location_on" title={tr('Choose area', 'এলাকা বেছে নিন')} />
        ) : q.isPending ? (
          <Loading />
        ) : q.isError ? (
          <ErrorText>{errorText(q.error)}</ErrorText>
        ) : !q.data.length ? (
          <EmptyState
            icon={online ? 'local_shipping' : 'storefront'}
            title={online ? tr('No pharmacy here sells online yet', 'এখানে এখনো কোনো ফার্মেসি অনলাইনে বিক্রি করে না') : nameQ ? tr('No shop with this name in the app', 'এই নামে অ্যাপে কোনো দোকান নেই') : tr('No pharmacy in the app here yet', 'এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই')}
            text={online ? tr('Go to a pharmacy and show the QR.', 'ফার্মেসিতে গিয়ে QR দেখিয়ে কিনুন।') : tr('Show the QR at the counter instead.', 'কাউন্টারে QR দেখিয়েও কিনতে পারেন।')}
          />
        ) : (
          q.data.map((p) => (
            <DirectoryCard
              key={p.id}
              title={p.name}
              icon={online ? 'local_shipping' : 'storefront'}
              status={p.openNow ? { text: tr('Open', 'খোলা'), good: true } : { text: tr('Closed', 'বন্ধ'), good: false }}
              meta={p.km != null ? formatKm(p.km) : null}
              subtitle={p.keepsStock ? tr(`Has ${p.have} of ${p.total} medicines`, `${num(p.total)}টার ${num(p.have)}টা ওষুধ আছে`) : tr('Stock not listed', 'স্টকের তালিকা নেই')}
              details={[
                ['location_on', p.address],
                ['local_shipping', online && p.deliveryFee ? tr('Delivery: ', 'ডেলিভারি: ') + p.deliveryFee : ''],
                ['info', online ? p.onlineNote : ''],
                ['warning', p.keepsStock && p.missing.length ? tr('Missing: ', 'নেই: ') + p.missing.join(', ') : ''],
                ['payments', pharmEstimateText(p.estimate)],
              ]}
              primary={{ label: online ? tr('Order', 'অর্ডার করুন') : tr('Send here', 'এই দোকানে দিন'), icon: online ? 'local_shipping' : 'send', busy: busy === p.id, onClick: () => (online ? setOrdering(p) : send(p.id, p.name)) }}
              secondary={p.phone ? { label: tr('Call', 'ফোন'), icon: 'call', onClick: () => callTel(p.phone) } : null}
            />
          ))
        )}
      </div>
      {showArea ? (
        <AreaPicker
          value={area}
          onClose={() => setShowArea(false)}
          onPicked={(v) => {
            setArea(v);
            setShowArea(false);
          }}
        />
      ) : null}
      {ordering ? (
        <OnlineOrderDialog
          rx={rx}
          p={ordering}
          onClose={() => setOrdering(null)}
          onSent={() => {
            const nm = ordering.name;
            setOrdering(null);
            onSent(nm);
          }}
        />
      ) : null}
    </Dialog>
  );
}

/** অনলাইন অর্ডার: কোথায় পৌঁছাবে আর ফোন (দাম পৌঁছালে, দোকানকে সরাসরি) */
function OnlineOrderDialog({ rx, p, onClose, onSent }: { rx: Rx; p: PharmOption; onClose: () => void; onSent: () => void }) {
  const s = useSession();
  const [address, setAddress] = useState('');
  const [phone, setPhone] = useState(s.profile?.phone ?? '');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={p.name}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            disabled={!address.trim() || !phone.trim()}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await call('rx_send_pharmacy', { rxId: rx.id, pharmacyId: p.id, mode: 'online', address: address.trim(), phone: phone.trim(), note: note.trim() });
                onSent();
              } catch (e) {
                setErr(errorText(e));
                setBusy(false);
              }
            }}
          >
            {tr('Place order', 'অর্ডার দিন')}
          </Button>
        </>
      }
    >
      {p.deliveryFee ? <IconLine icon="local_shipping">{tr('Delivery: ', 'ডেলিভারি: ') + p.deliveryFee}</IconLine> : null}
      {p.keepsStock && p.missing.length ? <span className="t-body-s muted">{tr('May not have: ', 'না-ও থাকতে পারে: ') + p.missing.join(', ')}</span> : null}
      <IconLine icon="payments">{pharmEstimateText(p.estimate)}</IconLine>
      <TextArea label={tr('Delivery address', 'কোথায় পৌঁছাবে (ঠিকানা)')} rows={2} maxLength={200} value={address} onChange={(e) => setAddress(e.target.value)} />
      <TextInput label={tr('Mobile number', 'মোবাইল নম্বর')} type="tel" inputMode="tel" maxLength={20} value={phone} onChange={(e) => setPhone(e.target.value)} />
      <TextInput label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
      <span className="t-body-s muted">{tr('Pay the shop when it arrives (cash on delivery). The shop sees your address and number only for this order.', 'ওষুধ পৌঁছালে দোকানকে দাম দেবেন (ক্যাশ অন ডেলিভারি)। ঠিকানা আর নম্বর দোকান শুধু এই অর্ডারের জন্য দেখবে।')}</span>
      <SendError>{err}</SendError>
    </Dialog>
  );
}

/** দোকান থেকে প্রেসক্রিপশন ফিরিয়ে নেওয়া */
function WithdrawDialog({ o, onClose, onDone }: { o: RxOrder; onClose: () => void; onDone: () => void }) {
  const [why, setWhy] = useState<'na' | 'other'>('na');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      size="small"
      onClose={() => !busy && onClose()}
      title={tr(`Take back from ${o.pharmacyName}?`, `${o.pharmacyName} থেকে ফিরিয়ে নেবেন?`)}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await call('rx_order_withdraw', { id: o.id, reason: why === 'na' ? 'not_available' : 'other' });
                onDone();
              } catch (e) {
                setErr(errorText(e));
                setBusy(false);
              }
            }}
          >
            {tr('Take back', 'ফিরিয়ে নিন')}
          </Button>
        </>
      }
    >
      <span className="t-label-l">{tr('Why?', 'কেন?')}</span>
      <div className="health-stack-chips">
        <ChoiceChips
          value={why}
          onChange={setWhy}
          options={[
            { value: 'na', label: tr("They don't have the medicines", 'ওদের কাছে ওষুধ নেই') },
            { value: 'other', label: tr('Another reason', 'অন্য কারণ') },
          ]}
        />
      </div>
      <span className="t-body-s muted">{tr('Medicines not bought yet stay on the prescription — send it to another shop.', 'যে ওষুধ কেনা বাকি, তা প্রেসক্রিপশনে থাকবে — অন্য দোকানে পাঠান।')}</span>
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- প্যাথলজি ----------
/**
 * প্যাথলজি বাছাই: প্রতিটায় কয়টা পরীক্ষা হয় ("৩টার ৩টা"), আনুমানিক কত, কোনটা নেই, রিপোর্ট কতক্ষণে —
 * বেশি পরীক্ষা → কম দাম → কাছের আগে (সার্ভার সাজায়)। দামের তালিকা না থাকলে "দাম জানা নেই — ফোনে জেনে নিন"।
 * বাসা থেকে নমুনা নিলে পাঠানোর আগে বেছে নেওয়া যায় (ঠিকানা, ফোন, দিন, সময়)।
 */
function LabPickDialog({ rx, only, onClose, onSent }: { rx: Rx; only?: PlaceLab; onClose: () => void; onSent: (name: string, home: boolean) => void }) {
  const start = useStartArea();
  const care = useCareMode();
  // only: প্যাথলজির পাতা থেকে এলে শুধু এই প্যাথলজি — তার জেলায় খোঁজা, এলাকা বদলানো নেই
  const [area, setArea] = useState<AreaValue | null>(only?.district ? { district: only.district, upazilaId: null } : start);
  const [showArea, setShowArea] = useState(false);
  const [chosen, setChosen] = useState<string[]>(rx.tests ?? []);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [homeFor, setHomeFor] = useState<LabOption | null>(null);
  // চিপ টিপতে টিপতে প্রতিবার নয় — থামার ৪৫০ মিলিসেকেন্ড পর (সার্ভারে দিনের সীমাও আছে)
  const asked = useDebounced(chosen, 450);
  const q = useQuery({
    queryKey: ['rx_lab_options', rx.id, area?.district, area?.upazilaId ?? null, asked.join('|'), care?.uid ?? null, only?.id ?? null],
    queryFn: () =>
      call<{ items?: LabOption[]; lastHome?: LastHome | null }>('rx_lab_options', {
        rxId: rx.id, district: area!.district, tests: asked, ...(area?.upazilaId ? { upazilaId: area.upazilaId } : {}),
        // প্যাথলজির পাতা থেকে: শুধু সেটা (জেলার তালিকার সীমার বাইরে হলেও)
        ...(only ? { placeId: only.id } : {}),
      }),
    enabled: !!area && asked.length > 0,
    placeholderData: (prev) => prev,
  });
  const testOptions = useMemo(() => (rx.tests ?? []).map((t) => ({ value: t, label: t })), [rx.tests]);
  const all = q.data?.items ?? [];
  // শুধু এই প্যাথলজি; তালিকায় না এলে (বা আনা না গেলে) দামের হিসাব ছাড়াই — পাঠানো তবু যায়, সার্ভার মিলিয়ে নেয়
  const mine = only ? all.filter((l) => l.id === only.id) : all;
  const items: LabOption[] = !only || mine.length || !(q.data || q.isError) ? mine : [{
    id: only.id, name: only.name, phone: only.phone, address: only.address, district: only.district, upazilaId: only.upazilaId, facilityId: only.facilityId,
    homeCollection: only.homeCollection, hours: only.hours, suggested: false, priced: false, have: null, total: chosen.length, missing: [], items: [],
    subtotal: null, discountPct: null, estimate: null, homeFee: only.homeCollection ? only.prices?.homeFee ?? null : null, tat: '',
  }];

  async function send(lab: LabOption) {
    if (busy || !chosen.length) {
      if (!chosen.length) setErr(tr('Choose the tests', 'পরীক্ষা বেছে নিন'));
      return;
    }
    if (lab.homeCollection) {
      setHomeFor(lab);
      return;
    }
    setBusy(lab.id);
    setErr(null);
    try {
      await call('rx_send_lab', { rxId: rx.id, labId: lab.id, tests: chosen });
      onSent(lab.name, false);
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(null);
  }

  return (
    <Dialog onClose={() => !busy && onClose()} size="wide" title={only?.name ?? tr('Choose a lab', 'প্যাথলজি বাছুন')}>
      <span className="t-label-l">{tr('Tests to send', 'কোন পরীক্ষা পাঠাবেন')}</span>
      <ChoiceChips multi options={testOptions} value={chosen} onChange={setChosen} />
      {!only ? <FilterBar areaLabel={area ? areaLabel(area.district, area.upazilaId) : ''} onArea={() => setShowArea(true)} /> : null}
      <SendError>{err}</SendError>
      <div className="col gap-12">
        {!area ? (
          <EmptyState icon="location_on" title={tr('Choose area', 'এলাকা বেছে নিন')} />
        ) : !chosen.length ? (
          <EmptyState icon="list_alt" title={tr('Choose the tests', 'পরীক্ষা বেছে নিন')} />
        ) : q.isPending ? (
          <Loading />
        ) : q.isError && !only ? (
          <ErrorText>{errorText(q.error)}</ErrorText>
        ) : !items.length ? (
          <EmptyState icon="local_hospital" title={tr('No lab in the app here yet', 'এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই')} text={tr('You can still take the prescription to any lab.', 'যেকোনো প্যাথলজিতে প্রেসক্রিপশন নিয়ে গিয়েও করাতে পারেন।')} />
        ) : (
          items.map((lab) => (
            <DirectoryCard
              key={lab.id}
              title={lab.name}
              icon="local_hospital"
              status={lab.suggested ? { text: tr('Doctor suggested', 'ডাক্তারের সুপারিশ'), good: true } : null}
              // প্যাথলজির পাতা থেকে এলে: "এখানে হয় ৩/৪টা পরীক্ষা; হয় না: X" আর দামের ভাঙা হিসাব (দাম, ছাড়, মোট)
              subtitle={!lab.priced ? NO_PRICE() : only ? hereText(lab.have ?? 0, lab.total, lab.missing) : coverEstimateText(lab.have ?? 0, lab.total, lab.estimate)}
              meta={lab.priced && lab.discountPct && !only ? discountText(lab.discountPct) : null}
              details={[
                ['payments', lab.priced && only ? breakdownText(lab.subtotal, lab.discountPct, lab.estimate) : ''],
                ['warning', lab.priced && !only && lab.missing.length ? tr('Not here: ', 'এখানে হয় না: ') + lab.missing.join(', ') : ''],
                ['schedule', lab.tat ? tr('Report: ', 'রিপোর্ট: ') + lab.tat : ''],
                ['home_health', lab.homeCollection ? [tr('Sample from home', 'বাসা থেকে নমুনা নেয়'), lab.homeFee != null ? (lab.homeFee ? taka(lab.homeFee) : tr('free', 'ফ্রি')) : ''].filter(Boolean).join(' · ') : ''],
                ['location_on', lab.address],
              ]}
              primary={{ label: tr('Send tests', 'পরীক্ষা পাঠান'), icon: 'send', busy: busy === lab.id, onClick: () => send(lab) }}
              secondary={lab.phone ? { label: tr('Call', 'ফোন'), icon: 'call', onClick: () => callTel(lab.phone) } : null}
            />
          ))
        )}
      </div>
      {showArea ? (
        <AreaPicker
          value={area}
          onClose={() => setShowArea(false)}
          onPicked={(v) => {
            setArea(v);
            setShowArea(false);
          }}
        />
      ) : null}
      {homeFor ? (
        <LabSendDialog
          title={homeFor.name}
          header={homeFor.priced ? <span className="t-body-m semi">{coverEstimateText(homeFor.have ?? 0, homeFor.total, homeFor.estimate)}</span> : <span className="t-body-s muted">{NO_PRICE()}</span>}
          homeFee={homeFor.homeFee}
          last={q.data?.lastHome ?? null}
          onClose={() => setHomeFor(null)}
          onSubmit={async (home) => {
            await call('rx_send_lab', { rxId: rx.id, labId: homeFor.id, tests: chosen, ...(home ? { home } : {}) });
            const nm = homeFor.name;
            setHomeFor(null);
            onSent(nm, !!home);
          }}
        />
      ) : null}
    </Dialog>
  );
}

/**
 * বাসা থেকে নমুনা নেয় এমন প্যাথলজিতে পাঠানো: প্যাথলজিতে গিয়ে, অথবা বাসা থেকে (ঠিকানা, ফোন, দিন, সময়, নোট)।
 * change: পাঠানো অনুরোধের সময় বদল (প্যাথলজি সময় জানানোর আগে) — শুধু বাসার ঘরগুলো, আগেরটা বসানো।
 */
function LabSendDialog({ title, header, homeFee, last, change, onClose, onSubmit }: {
  title: string; header?: ReactNode; homeFee: number | null; last: LastHome | null; change?: LabHome;
  onClose: () => void; onSubmit: (home: HomeReq | null) => Promise<void>;
}) {
  const s = useSession();
  const care = useCareMode();
  const DAYMS = 24 * 60 * 60 * 1000;
  const today = todayUtcMidnight();
  const was = change ? Math.round((change.date - today) / DAYMS) : 1;
  const [how, setHow] = useState<'lab' | 'home'>('home');
  const [address, setAddress] = useState(change?.address ?? last?.address ?? '');
  const [phone, setPhone] = useState(change?.phone || last?.phone || (care ? '' : s.profile?.phone ?? ''));
  // সময় পেরিয়ে যাওয়া অনুরোধ বদলালে কাল থেকে
  const [day, setDay] = useState(change && change.status !== 'expired' && was >= 0 && was <= 2 ? was : 1);
  const [slot, setSlot] = useState<Slot>(change && SLOT_KEYS.includes(change.slot as Slot) ? (change.slot as Slot) : 'early');
  const [note, setNote] = useState(change?.note ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const slots = SLOT_KEYS.filter((k) => !slotPassed(day, k));
  const home = !!change || how === 'home';
  async function send() {
    setBusy(true);
    setErr(null);
    try {
      await onSubmit(home ? { address: address.trim(), phone: phone.trim(), day, slot: slots.includes(slot) ? slot : slots[0], note: note.trim() } : null);
    } catch (e) {
      setErr(errorText(e));
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={change ? tr(`${title} — change the time`, `${title} — সময় বদলান`) : title}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} icon="send" disabled={home && (!address.trim() || !phone.trim() || !slots.length)} onClick={send}>{change ? tr('Change', 'বদলান') : tr('Send', 'পাঠান')}</Button>
        </>
      }
    >
      {header}
      {!change ? (
        <div className="health-stack-chips">
          <ChoiceChips
            value={how}
            onChange={setHow}
            options={[
              { value: 'home', label: tr('Come and take the sample from home', 'বাসা থেকে নমুনা নিতে আসবে') },
              { value: 'lab', label: tr("I'll go to the lab", 'প্যাথলজিতে গিয়ে দেব') },
            ]}
          />
        </div>
      ) : null}
      {home ? (
        <>
          {homeFee != null ? <IconLine icon="payments">{homeFeeText(homeFee)}</IconLine> : null}
          <TextArea label={tr('Home address', 'বাসার ঠিকানা')} rows={2} maxLength={200} value={address} onChange={(e) => setAddress(e.target.value)} />
          <TextInput label={tr('Mobile number', 'মোবাইল নম্বর')} type="tel" inputMode="tel" maxLength={20} value={phone} onChange={(e) => setPhone(e.target.value)} />
          <span className="t-label-l">{tr('When', 'কবে')}</span>
          <ChoiceChips value={day} onChange={setDay} options={[0, 1, 2].map((k) => ({ value: k, label: dayWord(today + k * DAYMS) }))} />
          {slots.length ? (
            <ChoiceChips value={slots.includes(slot) ? slot : slots[0]} onChange={setSlot} options={slots.map((k) => ({ value: k, label: slotText(k) }))} />
          ) : (
            <span className="t-body-s muted">{tr('No time left today — choose tomorrow', 'আজ আর সময় নেই — কাল বেছে নিন')}</span>
          )}
          <TextInput label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')} maxLength={120} value={note} placeholder={tr('e.g. I will be fasting', 'যেমন: খালি পেটে থাকব')} onChange={(e) => setNote(e.target.value)} />
          <span className="t-body-s muted">{tr('The lab will confirm the time.', 'প্যাথলজি সময় নিশ্চিত করে জানাবে।')}</span>
        </>
      ) : null}
      <SendError>{err}</SendError>
    </Dialog>
  );
}

/** রিপোর্ট: ফলের তালিকা (বেশি/কম চিহ্নসহ), আগের ফল, নোট, ছবি */
function LabReportDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const care = useCareMode();
  const lang = useLang();
  const q = useQuery({
    queryKey: ['lab_order_get', id, care?.uid ?? null, lang],
    queryFn: () => call<{ item: LabOrder; history?: Record<string, { result: string; flag: string; at: number }[]> }>('lab_order_get', { id }),
  });
  return (
    <Dialog onClose={onClose} size="wide" title={tr('Test report', 'পরীক্ষার রিপোর্ট')}>
      {q.isPending ? <Loading /> : q.isError ? <ErrorText>{errorText(q.error)}</ErrorText> : <LabReportView o={q.data.item} history={q.data.history ?? {}} />}
    </Dialog>
  );
}

function LabReportView({ o, history }: { o: LabOrder; history: Record<string, { result: string; flag: string; at: number }[]> }) {
  const r = o.report;
  const groups: { name: string; rows: LabResult[] }[] = [];
  for (const it of r?.items ?? []) {
    const g = it.group ?? '';
    if (groups.length && groups[groups.length - 1].name === g) groups[groups.length - 1].rows.push(it);
    else groups.push({ name: g, rows: [it] });
  }
  return (
    <div className="col gap-12">
      <div className="col gap-2">
        <span className="t-title-l">{o.labName}</span>
        <span className="t-body-m muted">{[o.patientName, o.patientAge != null ? tr(`${o.patientAge} yrs`, `${num(o.patientAge)} বছর`) : null, r?.reportedAt ? formatDate(r.reportedAt) : null].filter(Boolean).join(' · ')}</span>
      </div>
      {groups.map((g, gi) => (
        <div key={gi} className="card col">
          {g.name ? <span className="t-title-s c-primary">{g.name}</span> : null}
          {g.rows.map((it, i) => {
            if (it.long) {
              return (
                <div key={i} className="col gap-2">
                  <span className="t-body-l semi">{it.test}</span>
                  <p className="t-body-m pre">{it.result}</p>
                </div>
              );
            }
            const prev = it.code ? history[`${it.code}.${it.key}`] ?? [] : [];
            return (
              <div key={i} className="row health-result">
                <div className="col gap-2 grow">
                  <span className="t-body-l semi">{it.test}</span>
                  {it.range ? <span className="t-body-s muted">{tr('Normal: ', 'স্বাভাবিক: ') + it.range}</span> : null}
                  {prev.length ? (
                    <span className="t-body-s muted">
                      {tr('Before: ', 'আগে: ') + prev.slice(0, 3).map((h) => num(h.result) + (h.flag === 'high' ? '↑' : h.flag === 'low' ? '↓' : '') + ' (' + formatDate(h.at) + ')').join(', ')}
                    </span>
                  ) : null}
                </div>
                <span className={it.flag ? 't-body-l semi c-error none' : 't-body-l semi none'}>{it.result + (it.unit ? ' ' + it.unit : '')}</span>
                {it.flag ? <Tag tone="error">{flagLabel(it.flag)}</Tag> : null}
              </div>
            );
          })}
        </div>
      ))}
      {r?.note ? <p className="t-body-m pre">{r.note}</p> : null}
      {(r?.photos ?? []).map((p, i) => (
        <img key={i} src={imgSrc(p)} alt={tr('Report photo', 'রিপোর্টের ছবি')} className="health-report-photo" />
      ))}
    </div>
  );
}

/** রোগী: ডাক্তারের নম্বরে টাকা পাঠিয়ে TrxID */
function PayDialog({ r, onClose, onDone }: { r: RxReview; onClose: () => void; onDone: () => void }) {
  const [method, setMethod] = useState(r.payMethods[0] ?? '');
  const [trx, setTrx] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={tr('Payment for showing the report', 'রিপোর্ট দেখানোর টাকা')}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            disabled={trx.length < 6}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await call('review_pay', { id: r.id, method, trxId: trx });
                onDone();
              } catch (e) {
                setErr(errorText(e));
                setBusy(false);
              }
            }}
          >
            {tr('Send', 'পাঠান')}
          </Button>
        </>
      }
    >
      <p className="t-body-l">{tr(`Send ${r.fee} to ${r.payNumber} with `, `${r.fee} পাঠান ${num(r.payNumber)} নম্বরে — `) + r.payMethods.map(payLabel).join(' / ')}</p>
      <span className="t-body-s muted">{tr("The money goes straight to the doctor. Lifolo doesn't hold or take any of it.", 'টাকা সরাসরি ডাক্তারের কাছে যায়। Lifolo টাকা রাখে না, কিছু কাটে না।')}</span>
      {r.payMethods.length > 1 ? <ChoiceChips value={method} onChange={setMethod} options={r.payMethods.map((m) => ({ value: m, label: payLabel(m) }))} /> : null}
      <TextInput
        label={tr('Transaction ID (TrxID)', 'ট্রানজেকশন আইডি (TrxID)')}
        value={trx}
        autoComplete="off"
        spellCheck={false}
        onChange={(e) => setTrx(e.target.value.replace(/[^A-Za-z0-9]/g, '').slice(0, 30))}
      />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- প্যাথলজি/ফার্মেসির পাতা থেকেই প্রেসক্রিপশন পাঠানো (অ্যাপের RxSendFlow) ----------
// আগে কোন প্রেসক্রিপশন (রোগীর চালু — প্যাথলজিতে পরীক্ষা লেখা, ফার্মেসিতে ওষুধ লেখা), তারপর আগের পথই:
// প্যাথলজি → LabPickDialog (শুধু এই প্যাথলজি); ফার্মেসি → দোকানে গিয়ে নেব / অনলাইনে অর্ডার (OnlineOrderDialog)।
// সব ওষুধ কেনা হয়ে গেলে সার্ভারের ৪০৯ "সব ওষুধ কেনা হয়ে গেছে" দেখায় (প্রতিটার rx_activity আনা হয় না)। কেয়ারগিভার মোডেও চলে।

/** পাঠানোর পর যা জানানো হয়: কোন প্রেসক্রিপশন (ডাক্তার, তারিখ), কোথায়, কীভাবে */
type SentMode = 'shop' | 'online' | 'lab' | 'lab_home';
interface RxSent { rxId: string; doctorName: string; date: number; place: string; mode: SentMode }

function sentModeText(m: SentMode) {
  switch (m) {
    case 'online': return tr('Home delivery', 'বাসায় ডেলিভারি');
    case 'lab_home': return tr('Sample from home', 'বাসা থেকে নমুনা');
    case 'lab': return tr("You'll give the sample at the lab", 'প্যাথলজিতে গিয়ে নমুনা দেবেন');
    default: return tr("You'll collect at the shop", 'দোকানে গিয়ে নেবেন');
  }
}
/**
 * "এরপর কী" — সার্ভারের আসল নোটিফিকেশনের সাথে মিলিয়ে (server/lib/rxnet.js): দোকান — "আপনার ওষুধ তৈরি" (ready);
 * অনলাইন — "অর্ডার নিয়েছে" (দাম · সময়); প্যাথলজি — নমুনা নেওয়ায় নোটিফিকেশন যায় না, বাসা থেকে হলে "… আসবে",
 * আর রিপোর্ট এলে "আপনার পরীক্ষার রিপোর্ট এসেছে"।
 */
function sentNextText(m: SentMode) {
  switch (m) {
    case 'online': return tr("You'll get a notification when the pharmacy tells you the price and time", 'ফার্মেসি দাম আর সময় জানালে নোটিফিকেশন পাবেন');
    case 'lab_home': return tr("You'll get a notification when the lab sets the time to come, and when the report is ready", 'প্যাথলজি বাসায় আসার সময় জানালে নোটিফিকেশন পাবেন; রিপোর্ট এলেও জানাবে');
    case 'lab': return tr("Go to the lab to give the sample; you'll get a notification when the report is ready", 'প্যাথলজিতে গিয়ে নমুনা দিন; রিপোর্ট এলে নোটিফিকেশন পাবেন');
    default: return tr("You'll get a notification when the pharmacy has your medicines ready", 'ফার্মেসি ওষুধ তৈরি করলে নোটিফিকেশন পাবেন');
  }
}
const sentModeIcon = (m: SentMode) => (m === 'online' ? 'local_shipping' : m === 'lab_home' ? 'home_health' : m === 'lab' ? 'local_hospital' : 'storefront');

/** পাঠানোর পর: বড় টিক, কী কোথায় গেল, এরপর কী — আর "প্রেসক্রিপশন খুলুন" (সেখানে অবস্থা আর "ফিরিয়ে নিন") */
function RxSentDialog({ s, onOpen, onClose }: { s: RxSent; onOpen: () => void; onClose: () => void }) {
  return (
    <Dialog
      onClose={onClose}
      size="small"
      labelledBy="rx-sent-title"
      actions={
        <>
          <Button variant="text" onClick={onClose}>{tr('OK', 'ঠিক আছে')}</Button>
          <Button icon="medication" onClick={onOpen}>{tr('Open prescription', 'প্রেসক্রিপশন খুলুন')}</Button>
        </>
      }
    >
      <div className="health-sent">
        <Icon name="check_circle" size={64} className="health-sent-icon" />
        <h2 id="rx-sent-title" className="t-title-l">{tr('Prescription sent', 'প্রেসক্রিপশন পাঠানো হয়েছে')}</h2>
      </div>
      <div className="col gap-6">
        <IconLine icon="medication" color="var(--on-surface)">{[s.doctorName || tr('Doctor', 'ডাক্তার'), formatDate(s.date)].join(' · ')}</IconLine>
        <IconLine icon={s.mode.startsWith('lab') ? 'local_hospital' : 'storefront'} color="var(--on-surface)">{s.place}</IconLine>
        <IconLine icon={sentModeIcon(s.mode)} color="var(--on-surface)">{sentModeText(s.mode)}</IconLine>
      </div>
      {/* এরপর কী */}
      <div className="card tonal row gap-8 health-sent-next">
        <Icon name="notifications" size={20} />
        <span className="t-body-m">{sentNextText(s.mode)}</span>
      </div>
    </Dialog>
  );
}

/** পাঠানোর ভুল: যেখানে চাপা হয়েছে সেই ডায়ালগেই, লাল ঘরে আইকনসহ — আর চোখের সামনে স্ক্রল করে আনা (লম্বা তালিকার নিচে চাপলেও দেখা যায়) */
function SendError({ children }: { children?: string | null }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (children) ref.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  }, [children]);
  if (!children) return null;
  return (
    <div ref={ref} className="card danger row gap-8 health-send-err" role="alert">
      <Icon name="warning" size={20} />
      <span className="t-body-m">{children}</span>
    </div>
  );
}

/** বিস্তারিতের শেষ ভাগ: "এই প্যাথলজিতে/ফার্মেসিতে প্রেসক্রিপশন পাঠান" — অতিথি হলে আগে লগইন; পাঠানোর পর RxSentDialog */
export function RxSendFromPlace({ lab, pharmacy }: { lab?: PlaceLab; pharmacy?: PlacePharmacy }) {
  const s = useSession();
  const qc = useQueryClient();
  const nav = useNavigate();
  const [picking, setPicking] = useState(false);
  const [rx, setRx] = useState<Rx | null>(null);
  const [sent, setSent] = useState<RxSent | null>(null);
  const close = () => {
    setRx(null);
    setPicking(false);
  };
  const done = (place: string, mode: SentMode) => {
    if (rx) {
      qc.invalidateQueries({ queryKey: ['rx_activity', rx.id] });
      setSent({ rxId: rx.id, doctorName: rx.doctor?.name ?? '', date: rx.createdAt, place, mode });
    }
    close();
  };
  return (
    <section className="services-sec">
      <hr className="divider" />
      <h3 className="t-title-s">{lab ? tr('Tests from a prescription', 'প্রেসক্রিপশনের পরীক্ষা') : tr('Prescription', 'প্রেসক্রিপশন')}</h3>
      <Button icon="send" block onClick={() => s.requireLogin(tr('Log in to send a prescription', 'প্রেসক্রিপশন পাঠাতে লগইন করুন'), () => setPicking(true))}>
        {lab ? tr('Send a prescription to this lab', 'এই প্যাথলজিতে প্রেসক্রিপশন পাঠান') : tr('Send a prescription to this pharmacy', 'এই ফার্মেসিতে প্রেসক্রিপশন পাঠান')}
      </Button>
      {picking && !rx ? <RxPickDialog forLab={!!lab} onClose={close} onPick={setRx} /> : null}
      {rx && lab ? <LabPickDialog rx={rx} only={lab} onClose={() => setRx(null)} onSent={(name, home) => done(name, home ? 'lab_home' : 'lab')} /> : null}
      {rx && pharmacy ? <PharmacySendDialog rx={rx} p={pharmacy} onClose={() => setRx(null)} onSent={(mode) => done(pharmacy.name, mode)} /> : null}
      {sent ? (
        <RxSentDialog
          s={sent}
          onClose={() => setSent(null)}
          // প্রেসক্রিপশনের পাতা (Prescriptions.tsx-এর পথ) — দোকান/প্যাথলজির বিস্তারিত পথ বদলালেই বন্ধ; কেয়ারগিভার মোড এই পথে থাকে
          onOpen={() => {
            setSent(null);
            nav(`/prescriptions/${sent.rxId}`);
          }}
        />
      ) : null}
    </section>
  );
}

/** রোগীর চালু প্রেসক্রিপশন — ডাক্তার, তারিখ, আর পরীক্ষা (forLab) বা ওষুধের নাম */
function RxPickDialog({ forLab, onClose, onPick }: { forLab: boolean; onClose: () => void; onPick: (rx: Rx) => void }) {
  const lang = useLang();
  const care = useCareMode();
  const { uid } = useSession();
  // প্রেসক্রিপশনের পাতার একই চাবি — একবার আনা থাকলে আবার নয়
  const q = useQuery({
    queryKey: ['rx_mine', care?.uid ?? null, lang, uid],
    queryFn: () => call<{ items?: Rx[] }>('rx_mine').then((r) => r.items ?? []),
  });
  const list = (q.data ?? []).filter((r) => r.status !== 'cancelled' && (forLab ? (r.tests ?? []).length > 0 : (r.medicines ?? []).length > 0));
  return (
    <Dialog onClose={onClose} title={tr('Which prescription?', 'কোন প্রেসক্রিপশন পাঠাবেন?')}>
      {q.isPending ? (
        <Loading />
      ) : q.isError ? (
        <SendError>{errorText(q.error)}</SendError>
      ) : !list.length ? (
        <EmptyState icon="list_alt" title={forLab ? tr('No prescription with tests', 'পরীক্ষা লেখা কোনো প্রেসক্রিপশন নেই') : tr('No prescription with medicines', 'ওষুধ লেখা কোনো প্রেসক্রিপশন নেই')} />
      ) : (
        <div className="col gap-4">
          {list.map((r) => (
            <ListRow
              key={r.id}
              icon={forLab ? 'local_hospital' : 'medication'}
              title={r.doctor?.name || tr('Doctor', 'ডাক্তার')}
              sub={
                <>
                  {formatDate(r.createdAt)}
                  <br />
                  {forLab ? (r.tests ?? []).join(', ') : (r.medicines ?? []).map((m) => m.name).join(', ')}
                </>
              }
              onClick={() => onPick(r)}
            />
          ))}
        </div>
      )}
    </Dialog>
  );
}

/**
 * ফার্মেসির পাতা থেকে: আগে এই দোকানের হিসাব (কয়টা ওষুধ আছে, কোনটা নেই, আনুমানিক খরচ), তারপর দোকানে গিয়ে নেব (সাথে সাথে পাঠানো),
 * অথবা — দোকান অনলাইনে অর্ডার নিলে — অনলাইনে অর্ডার।
 * rx_pharmacy_options-এ placeId: শুধু এই দোকান (দূরে হলেও) — অনলাইনে নেয় কিনা, ডেলিভারি চার্জ, স্টক আর দাম একসাথে আসে।
 */
function PharmacySendDialog({ rx, p, onClose, onSent }: { rx: Rx; p: PlacePharmacy; onClose: () => void; onSent: (mode: 'shop' | 'online') => void }) {
  const care = useCareMode();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [ordering, setOrdering] = useState<PharmOption | null>(null);
  const q = useQuery({
    queryKey: ['rx_pharmacy_options', rx.id, 'place', p.district, null, '', null, null, p.id, care?.uid ?? null],
    queryFn: () =>
      call<{ items?: PharmOption[] }>('rx_pharmacy_options', { rxId: rx.id, district: p.district, mode: 'shop', placeId: p.id })
        .then((r) => (r.items ?? []).find((x) => x.id === p.id) ?? null),
    staleTime: 0,
  });
  async function shop() {
    if (busy) return;
    setBusy(true);
    setErr(null);
    try {
      await call('rx_send_pharmacy', { rxId: rx.id, pharmacyId: p.id, mode: 'shop' });
      onSent('shop');
    } catch (e) {
      setErr(errorText(e));
      setBusy(false);
    }
  }
  const here = q.data ?? null;
  const online = here?.online ? here : null;
  return (
    <Dialog onClose={() => !busy && onClose()} size="small" title={p.name}>
      {/* পাঠানোর আগে এই দোকানের হিসাব */}
      {here ? (
        <div className="col gap-4">
          <IconLine icon={here.keepsStock ? 'check_circle' : 'info'}>{pharmHereText(here)}</IconLine>
          <IconLine icon="payments">{pharmEstimateText(here.estimate)}</IconLine>
        </div>
      ) : null}
      <div className="col gap-4">
        <ListRow icon="storefront" title={tr("I'll go to the shop", 'দোকানে গিয়ে নেব')} trailing={busy ? <span className="spinner small" /> : undefined} onClick={shop} />
        {q.isPending ? (
          <span className="t-body-s muted">{tr('Checking this shop…', 'এই দোকানের হিসাব দেখা হচ্ছে…')}</span>
        ) : online ? (
          <ListRow
            icon="local_shipping"
            title={tr('Order online — home delivery', 'অনলাইনে অর্ডার — বাসায় ডেলিভারি')}
            sub={online.deliveryFee ? tr('Delivery: ', 'ডেলিভারি: ') + online.deliveryFee : undefined}
            onClick={() => !busy && setOrdering(online)}
          />
        ) : null}
      </div>
      <SendError>{err}</SendError>
      {ordering ? <OnlineOrderDialog rx={rx} p={ordering} onClose={() => setOrdering(null)} onSent={() => onSent('online')} /> : null}
    </Dialog>
  );
}
