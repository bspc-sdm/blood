// অ্যাম্বুলেন্স এক চাপে ডাকুন (২.২৮.০) — অ্যাপের AmbulanceRequest.kt-এর ওয়েব রূপ। সার্ভার: server/lib/ambreq.js
// রোগী: তালিকার উপরে "অ্যাম্বুলেন্স ডাকুন" + "ফোন" → নিশ্চিত করার ঘর → অপেক্ষার পাতা (/services/ambulance/call/:id)।
// অপেক্ষার সময় ফোন করলে অনুরোধে ওঠে; পাতায় ফিরলে একবার "ফোনে কোনো গাড়ি পেয়েছেন?"
// ভাড়া (২.৩০.০): ঘরে "কোথায় যাবেন" (বাধ্যতামূলক), অক্সিজেন, "ভাড়া সর্বোচ্চ ৳…" (চিপ + নিজে লেখা), সাথে সাথে
// "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে" (ambreq_count); অপেক্ষার পাতায় চালকদের প্রস্তাব (ভাড়া কম আগে) — "রাজি" চাপলে নিশ্চিত।
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Check, ChoiceChips, Dialog, EmptyState, ErrorText, IconLine, Loading, TextArea, TextInput, useNow } from '@/ui/kit';
import { DestField, FARE_LEVELS, parseTaka, taka, type Dest } from './fare';
import { LocationInput, locationError, type LocationValue } from './LocationInput';
import { Icon } from '@/ui/Icon';
import { FormPage } from '@/shell/templates';
import { call, errorText, ServerError } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { RatingDialog } from './ui';

export interface AmbCar { id: string; name: string; phone: string; km: number | null; kmApprox?: boolean }
export interface AmbReq {
  id: string;
  status: 'open' | 'accepted' | 'arrived' | 'done' | 'byPhone' | 'cancelled' | 'expired';
  createdAt: number;
  widenAt: number;
  expiresAt: number;
  wave: number;
  notified: number;
  pickup: string;
  note: string;
  type: string | null;
  district: string;
  upazilaId: number | null;
  unionId?: number | null;
  gps?: boolean;
  ambulance?: AmbCar;
  cancelledBy: string | null;
  cancelReason: string | null;
  fallback: AmbCar[];
  called: AmbCar[];
  canRate: boolean;
  /** ভাড়ার অনুরোধ (২.৩০.০); পুরোনো অ্যাপে ডাকা হলে নেই */
  quote?: boolean;
  dest?: Dest | null;
  destLabel?: string;
  maxFare?: number | null;
  includeUnknown?: boolean;
  oxygen?: boolean;
  night?: boolean;
  quotes?: AmbQuote[];
  agreedFare?: number | null;
  raiseTo?: number | null;
  unknownFree?: number;
}
export interface AmbQuote { id: string; name: string; types: string[]; km: number | null; kmApprox?: boolean; fare: number; rating: number; ratingCount: number; at: number; expiresAt: number }
interface FareCount { count: number; total: number; unknown: number; levels: Record<string, number>; raiseTo: number | null; hospitals: string[]; night: boolean; upazilaId?: number }
export const isActive = (r: AmbReq | null | undefined) => !!r && ['open', 'accepted', 'arrived'].includes(r.status);

const LAST_PICKUP = 'lf.amb.pickup';
/** শেষবারের এলাকা (একই জেলায় উপজেলা না বাছা থাকলে আগে থেকে) */
const LAST_AREA = 'lf.amb.area';
/** শেষবারের গন্তব্য আর বাজেট (২.৩০.০) */
const LAST_DEST = 'lf.amb.dest';
const LAST_BUDGET = 'lf.amb.budget';

/**
 * ডাকার সময় গাড়ির ধরন (২.২৯.০): "যেকোনো" (ফ্রিজার ছাড়া) আগে থেকে; সার্ভারের ধরনের চাবি (server/lib/ambulance.js TYPES)।
 * নন-এসি = general। ফ্রিজার শুধু এখানে নিজে বাছলে।
 */
export const REQ_TYPES = () => [
  { value: 'any', label: tr('Any', 'যেকোনো') },
  { value: 'ac', label: tr('AC', 'এসি') },
  { value: 'general', label: tr('Non-AC', 'নন-এসি') },
  { value: 'icu', label: tr('ICU', 'আইসিইউ') },
  { value: 'freezer', label: tr('Freezer (deceased)', 'ফ্রিজার (মরদেহ)') },
];
export const reqTypeLabel = (t: string | null | undefined) =>
  t ? REQ_TYPES().find((x) => x.value === t)?.label ?? t : tr('Any (not freezer)', 'যেকোনো (ফ্রিজার ছাড়া)');
/** অনুরোধ চলার সময় ফোন করা হয়েছে — পাতায় ফিরলে একবার জিজ্ঞেস (এই ব্রাউজারেই) */
const ASK_KEY = 'lf.amb.askAfterCall';
export const markCalled = (reqId: string | null | undefined) => reqId && store.set(ASK_KEY, reqId);

const kmText = (km: number | null | undefined, approx?: boolean) =>
  km == null ? null : approx ? tr(`about ${Math.max(1, km)} km`, `প্রায় ${num(Math.max(1, km))} কিমি`) : km < 1 ? tr('< 1 km', '১ কিমির কম') : `${num(km)}${tr(' km', ' কিমি')}`;

/** নিজের চালু অনুরোধ (লগইন থাকলে) — তালিকার ব্যানার, ফোনকে অনুরোধে তোলা */
export function useActiveRequest() {
  const { guest } = useSession();
  const q = useCall<{ request: AmbReq | null }>('ambreq_status', {}, { enabled: !guest, staleTime: 5_000, refetchOnWindowFocus: true });
  const r = q.data?.request;
  return isActive(r) ? r! : null;
}

/** পাতায় ফিরলে (ট্যাব আবার দেখা গেলে) */
function useResumeTick() {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const f = () => document.visibilityState === 'visible' && setTick((n) => n + 1);
    document.addEventListener('visibilitychange', f);
    window.addEventListener('focus', f);
    return () => {
      document.removeEventListener('visibilitychange', f);
      window.removeEventListener('focus', f);
    };
  }, []);
  return tick;
}

/** তালিকা থেকে ফোন করে ফিরলেন আর অনুরোধ চলছে: অপেক্ষার পাতায় (সেখানে একবার জিজ্ঞেস) */
export function useBackFromCall(active: AmbReq | null) {
  const nav = useNavigate();
  const tick = useResumeTick();
  useEffect(() => {
    if (active && store.get(ASK_KEY) === active.id) nav(`/services/ambulance/call/${active.id}`);
  }, [tick, active, nav]);
}

// ---------- তালিকার উপরে ----------
export function CallBar({ onRequest, nearest, nearestSub, onCallNearest }: { onRequest: () => void; nearest?: { phone: string } | null; nearestSub?: string | null; onCallNearest?: () => void }) {
  return (
    <div className="col gap-6">
      <div className="amb-callbar">
        <button type="button" className="services-nearest amb-call-main" onClick={onRequest} data-testid="amb-request">
          <span className="ic"><Icon name="ambulance" /></span>
          <span className="col gap-2 grow">
            <span className="t-title-m">{tr('Call an ambulance', 'অ্যাম্বুলেন্স ডাকুন')}</span>
            <span className="t-body-s sub">{tr('All free ones nearby at once', 'কাছের খালি গাড়িগুলোতে একসাথে')}</span>
          </span>
        </button>
        {nearest ? (
          <a className="btn outline square has-icon amb-call-phone" href={`tel:${nearest.phone}`} onClick={onCallNearest}>
            <Icon name="call" />
            {tr('Call', 'ফোন')}
          </a>
        ) : null}
      </div>
      {nearest && nearestSub ? <span className="t-body-s muted">{tr('Call: nearest free — ', 'ফোন: সবচেয়ে কাছের খালি — ') + nearestSub}</span> : null}
    </div>
  );
}

export function ActiveBanner({ r }: { r: AmbReq }) {
  const nav = useNavigate();
  const title = r.status === 'accepted' ? tr(`${r.ambulance?.name ?? 'Ambulance'} is coming`, `${r.ambulance?.name ?? 'অ্যাম্বুলেন্স'} আসছে`)
    : r.status === 'arrived' ? tr(`${r.ambulance?.name ?? 'Ambulance'} has arrived`, `${r.ambulance?.name ?? 'অ্যাম্বুলেন্স'} পৌঁছেছে`)
      : tr('Your ambulance request is running', 'আপনার অ্যাম্বুলেন্সের অনুরোধ চলছে');
  return (
    <button type="button" className="amb-banner" onClick={() => nav(`/services/ambulance/call/${r.id}`)}>
      <Icon name="ambulance" />
      <span className="col gap-2 grow">
        <span className="t-title-s">{title}</span>
        <span className="t-body-s">{tr('Tap to see', 'দেখতে চাপুন')}</span>
      </span>
      <Icon name="chevron_right" />
    </button>
  );
}

/** "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে" / ০ হলে "৳X পর্যন্ত বাড়ান" আর "ভাড়া জানা নেই এমন গাড়িও" */
function FareCountLine({ count, counting, dest, includeUnknown, onRaise, onUnknown }: {
  count: FareCount | null; counting: boolean; dest: boolean; includeUnknown: boolean; onRaise: (to: number | null) => void; onUnknown: () => void;
}) {
  if (!dest) return <span className="t-body-s muted">{tr('Choose where to go to see how many are free.', 'কয়টা খালি গাড়ি আছে দেখতে "কোথায় যাবেন" বেছে নিন।')}</span>;
  if (!count) return counting ? <span className="t-body-s muted">{tr('Counting…', 'গোনা হচ্ছে…')}</span> : null;
  const night = count.night ? tr(' (night fare included)', ' (রাতের ভাড়াসহ)') : '';
  if (count.count > 0) {
    return (
      <div className="amb-count ok" role="status" data-testid="amb-count">
        <Icon name="check_circle" />
        <span>{tr(`${count.count} free ambulance${count.count > 1 ? 's' : ''} within this fare`, `এই ভাড়ার মধ্যে ${num(count.count)}টা খালি গাড়ি আছে`) + night}</span>
      </div>
    );
  }
  return (
    <div className="amb-count none col gap-8" role="status" data-testid="amb-count">
      <span className="row gap-8"><Icon name="warning" /><span>{tr('No ambulance within this fare — raise the fare', 'এই ভাড়ায় কোনো গাড়ি নেই — ভাড়া বাড়ান')}</span></span>
      <div className="row wrap gap-8">
        {count.raiseTo != null ? (
          <Button small icon="add" onClick={() => onRaise(count.raiseTo)} data-testid="amb-raise">{tr(`Raise to ${taka(count.raiseTo)}`, `${taka(count.raiseTo)} পর্যন্ত বাড়ান`)}</Button>
        ) : null}
        {!includeUnknown && count.unknown > 0 ? (
          <Button small variant="outline" onClick={onUnknown}>{tr(`Include ${count.unknown} with unknown fare`, `ভাড়া জানা নেই এমন ${num(count.unknown)}টাও নিন`)}</Button>
        ) : null}
      </div>
    </div>
  );
}

// ---------- নিশ্চিত করার ঘর ----------
/**
 * উপরে গাড়ির ধরন (বাধ্যতামূলক, "যেকোনো" আগে থেকে; তালিকায় ছাঁকা ধরন থাকলে সেটা), তারপর "কোথা থেকে নেবে" (LocationInput:
 * এখনকার জায়গা / Google ম্যাপ থেকে / লোকেশন লিখুন + এলাকা), "কোথায় যাবেন" (হাসপাতাল/ক্লিনিকের তালিকা — DestField), নোট, মোবাইল।
 * gps: আগে থেকে পাওয়া ব্রাউজারের লোকেশন (বেছে নেওয়া জেলার ভেতরে); target: বিস্তারিত থেকে একটা গাড়ি — সেটা আগে
 */
export function RequestDialog({
  district, upazilaId, type, gps, target, onClose,
}: { district: string; upazilaId: number | null; type: string | null; gps: { lat: number; lng: number } | null; target: { id: string; name: string } | null; onClose: () => void }) {
  const s = useSession();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [kind, setKind] = useState<string>(type && REQ_TYPES().some((t) => t.value === type) ? type : 'any');
  const [loc, setLoc] = useState<LocationValue>(() => {
    const last = store.getJson<{ district: string; upazilaId: number | null; unionId: number | null }>(LAST_AREA);
    const same = last && last.district === district && (upazilaId == null || last.upazilaId === upazilaId);
    return {
      district,
      upazilaId: upazilaId ?? (same ? last!.upazilaId : null),
      unionId: same ? last!.unionId ?? null : null,
      landmark: store.get(LAST_PICKUP) ?? '',
      point: gps,
      pointFrom: gps ? 'gps' : null,
    };
  });
  const [note, setNote] = useState('');
  const [phone, setPhone] = useState(s.profile?.phone ?? '');
  const [busy, setBusy] = useState(false);
  const [tried, setTried] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // ভাড়া: গন্তব্য (শেষবারেরটা), অক্সিজেন, বাজেট (চিপ: ১০০০/২০০০/৫০০০/১০০০০/যেকোনো/নিজে লেখা), ভাড়া না জানা গাড়িও
  const [dest, setDest] = useState<Dest | null>(() => store.getJson<Dest>(LAST_DEST));
  const [oxygen, setOxygen] = useState(false);
  const [budget, setBudget] = useState<string>(() => {
    const b = store.get(LAST_BUDGET);
    return b && (b === 'any' || FARE_LEVELS.includes(Number(b))) ? b : b ? 'custom' : 'any';
  });
  const [custom, setCustom] = useState(() => {
    const b = store.get(LAST_BUDGET);
    return b && b !== 'any' && !FARE_LEVELS.includes(Number(b)) ? b : '';
  });
  const [unknown, setUnknown] = useState(false);
  const customFare = parseTaka(custom);
  const maxFare: number | null = budget === 'any' ? null : budget === 'custom' ? (customFare != null && !Number.isNaN(customFare) ? customFare : NaN) : Number(budget);
  const budgetBad = Number.isNaN(maxFare as number) || (maxFare != null && maxFare < 100);
  const carType = kind === 'any' ? null : kind;
  const where = { district: loc.district, upazilaId: loc.upazilaId, ...(loc.point ? { lat: loc.point.lat, lng: loc.point.lng, gps: true } : {}) };
  // সাথে সাথে গোনা (৩৫০ মি.সে. থেমে) — শুধু গন্তব্য আর ঠিক বাজেট থাকলে
  const [count, setCount] = useState<FareCount | null>(null);
  const [counting, setCounting] = useState(false);
  const countKey = JSON.stringify([where, carType, dest, maxFare, unknown, oxygen]);
  useEffect(() => {
    if (!dest || budgetBad) {
      setCount(null);
      return;
    }
    let live = true;
    setCounting(true);
    const t = setTimeout(() => {
      call<FareCount>('ambreq_count', { ...where, type: carType, dest, maxFare, includeUnknown: unknown, oxygen })
        .then((c) => live && setCount(c), () => live && setCount(null))
        .finally(() => live && setCounting(false));
    }, 350);
    return () => {
      live = false;
      clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [countKey, budgetBad]);
  const raise = (to: number | null) => {
    if (to == null) setBudget('any');
    else if (FARE_LEVELS.includes(to)) setBudget(String(to));
    else {
      setBudget('custom');
      setCustom(String(to));
    }
  };

  async function send() {
    setTried(true);
    const e = locationError(loc)
      ?? (!dest ? tr('Choose where to go', '"কোথায় যাবেন" বেছে নিন') : null)
      ?? (budgetBad ? tr('Write the fare limit in whole taka (৳100 or more)', 'সর্বোচ্চ ভাড়া পুরো টাকায় লিখুন (৳১০০ বা বেশি)') : null)
      ?? (count && count.count === 0 ? tr('No ambulance within this fare — raise the fare', 'এই ভাড়ায় কোনো গাড়ি নেই — ভাড়া বাড়ান') : null)
      ?? (!/^(\+?880|0)?1[3-9]\d{8}$/.test(phone.replace(/[\s-]/g, '')) ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন') : null);
    setErr(e);
    if (e) return;
    setBusy(true);
    try {
      const r = await call<{ id: string }>('ambreq_create', {
        district: loc.district, upazilaId: loc.upazilaId, unionId: loc.unionId, type: carType,
        dest, maxFare, includeUnknown: unknown, oxygen,
        pickup: loc.landmark.trim(), note: note.trim(), phone: phone.replace(/[\s-]/g, ''), ambulanceId: loc.district === district ? target?.id ?? null : null,
        ...(loc.point ? { lat: loc.point.lat, lng: loc.point.lng, gps: true } : {}),
      });
      store.set(LAST_PICKUP, loc.landmark.trim());
      store.setJson(LAST_DEST, dest);
      store.set(LAST_BUDGET, maxFare == null ? 'any' : String(maxFare));
      if (!loc.upazilaAuto) store.setJson(LAST_AREA, { district: loc.district, upazilaId: loc.upazilaId, unionId: loc.unionId });
      qc.invalidateQueries({ queryKey: ['ambreq_status'] });
      nav(`/services/ambulance/call/${r.id}`);
    } catch (x) {
      // আগেই একটা চালু: সেটার পাতা
      if (x instanceof ServerError && x.code === 'amb_req_active') {
        const a = await call<{ request: AmbReq | null }>('ambreq_status', {}).catch(() => null);
        if (a?.request) return nav(`/services/ambulance/call/${a.request.id}`);
      }
      setErr(errorText(x));
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={tr('Call an ambulance', 'অ্যাম্বুলেন্স ডাকুন')}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button icon="send" busy={busy} disabled={!!count && count.count === 0} onClick={send} data-testid="amb-send">{tr('Send', 'পাঠান')}</Button>
        </>
      }
    >
      <div className="col gap-6" data-testid="amb-type">
        <span className="t-title-s">{tr('Ambulance type', 'গাড়ির ধরন')}</span>
        <ChoiceChips options={REQ_TYPES()} value={kind} onChange={(v: string) => setKind(v)} />
        {kind === 'freezer' ? <span className="t-body-s muted">{tr('Only freezer vans (for the deceased) get this request.', 'শুধু ফ্রিজার ভ্যান (মরদেহ নেওয়ার গাড়ি) এই অনুরোধ পাবে।')}</span> : null}
      </div>
      <p className="t-body-m">
        {target
          ? tr(`Goes to ${target.name} first, and to the nearest free ambulances within your fare. Drivers offer a fare — tap "Agree" on one.`, `আগে ${target.name}-কে, সাথে আপনার ভাড়ার মধ্যে কাছের খালি গাড়িগুলোকে যাবে। চালকরা ভাড়া বলবেন — একটায় "রাজি" চাপুন।`)
          : tr('Goes to the nearest free ambulances within your fare. Drivers offer a fare — tap "Agree" on one.', 'আপনার ভাড়ার মধ্যে কাছের খালি গাড়িগুলোতে একসাথে যাবে। চালকরা ভাড়া বলবেন — একটায় "রাজি" চাপুন।')}
      </p>
      <LocationInput value={loc} onChange={(v) => { setLoc(v); setErr(null); }} showErrors={tried} />
      <div className="col gap-6" data-testid="amb-dest">
        <DestField
          label={tr('Where to go', 'কোথায় যাবেন')}
          district={loc.district}
          near={loc.point}
          value={dest}
          error={tried && !dest ? tr('Choose where to go', '"কোথায় যাবেন" বেছে নিন') : null}
          onChange={(d) => { setDest(d); setErr(null); }}
        />
        <Check checked={oxygen} onChange={setOxygen}>{tr('Oxygen needed', 'অক্সিজেন লাগবে')}</Check>
      </div>
      <div className="col gap-6 amb-budget" data-testid="amb-budget">
        <span className="t-title-s">{tr('Fare up to', 'ভাড়া সর্বোচ্চ')}</span>
        <ChoiceChips
          options={[...FARE_LEVELS.map((l) => ({ value: String(l), label: taka(l) })), { value: 'any', label: tr('Any', 'যেকোনো') }, { value: 'custom', label: tr('Other…', 'অন্য…') }]}
          value={budget}
          onChange={(v: string) => setBudget(v)}
        />
        {budget === 'custom' ? (
          <TextInput label={tr('Fare up to (৳)', 'সর্বোচ্চ ভাড়া (৳)')} inputMode="numeric" value={custom} maxLength={7} autoFocus onChange={(e) => setCustom(e.target.value)} error={custom && budgetBad ? tr('Whole taka, ৳100 or more', 'পুরো টাকায়, ৳১০০ বা বেশি') : undefined} />
        ) : null}
        <Check checked={unknown} onChange={setUnknown}>{tr('Also ambulances with unknown fare', 'ভাড়া জানা নেই এমন গাড়িও')}</Check>
        <FareCountLine count={count} counting={counting} dest={!!dest} includeUnknown={unknown} onRaise={raise} onUnknown={() => setUnknown(true)} />
      </div>
      <TextArea label={tr('Note (optional) — e.g. the patient can\'t walk', 'নোট (ঐচ্ছিক) — যেমন রোগী হাঁটতে পারেন না')} rows={2} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
      <TextInput label={tr('Your mobile (for the driver)', 'আপনার মোবাইল (চালকের জন্য)')} inputMode="tel" value={phone} maxLength={15} onChange={(e) => setPhone(e.target.value.replace(/[^\d+\s-]/g, ''))} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- অপেক্ষার পাতা ----------
const PATIENT_REASONS = () => [
  { value: 'found_other', label: tr('Got another ambulance', 'অন্য গাড়ি পেয়েছি') },
  { value: 'not_needed', label: tr('No longer needed', 'আর দরকার নেই') },
  { value: 'too_slow', label: tr('Taking too long', 'অনেক দেরি হচ্ছে') },
  { value: 'wrong', label: tr('Sent by mistake', 'ভুল করে পাঠিয়েছি') },
  { value: 'other', label: tr('Other', 'অন্য কারণ') },
];
export const DRIVER_REASONS = () => [
  { value: 'vehicle_problem', label: tr('Vehicle problem', 'গাড়িতে সমস্যা') },
  { value: 'cant_reach', label: tr("Can't reach in time", 'সময়মতো পৌঁছাতে পারব না') },
  { value: 'patient_unreachable', label: tr('Patient not reachable', 'রোগীকে পাওয়া যাচ্ছে না') },
  { value: 'other', label: tr('Other', 'অন্য কারণ') },
];
const reasonText = (r: string | null) =>
  [...PATIENT_REASONS(), ...DRIVER_REASONS()].find((x) => x.value === r)?.label ?? (r === 'patient_found_other' ? tr('Patient found another ambulance', 'রোগী অন্য গাড়ি পেয়েছেন') : '');

/** কারণ বেছে বাতিল (রোগী ও চালক) */
export function ReasonDialog({ title, reasons, onClose, onPick }: { title: string; reasons: { value: string; label: string }[]; onClose: () => void; onPick: (r: string) => Promise<void> }) {
  const [v, setV] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      size="small"
      onClose={() => !busy && onClose()}
      title={title}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Back', 'ফিরে যান')}</Button>
          <Button
            variant="danger"
            busy={busy}
            disabled={!v}
            onClick={async () => {
              setBusy(true);
              try {
                await onPick(v!);
              } catch (e) {
                setErr(errorText(e));
                setBusy(false);
              }
            }}
          >
            {tr('Cancel request', 'বাতিল করুন')}
          </Button>
        </>
      }
    >
      <ChoiceChips options={reasons} value={v} onChange={setV} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

/** ফোন করা গাড়ি থেকে একটা বাছা */
function PickCarDialog({ title, cars, noLabel, onClose, onPick }: { title: string; cars: AmbCar[]; noLabel: string; onClose: () => void; onPick: (c: AmbCar | null) => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const go = async (c: AmbCar | null) => {
    setBusy(true);
    try {
      await onPick(c);
    } catch (e) {
      setErr(errorText(e));
      setBusy(false);
    }
  };
  return (
    <Dialog size="small" onClose={() => !busy && onClose()} title={title} actions={<Button variant="text" busy={busy} onClick={() => go(null)}>{noLabel}</Button>}>
      <div className="col gap-8">
        {cars.map((c) => (
          <Button key={c.id} variant="outline" icon="check_circle" block disabled={busy} onClick={() => go(c)}>{tr('Yes — ', 'হ্যাঁ — ') + c.name}</Button>
        ))}
      </div>
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

export function RequestPage({ id }: { id: string }) {
  const nav = useNavigate();
  const qc = useQueryClient();
  const now = useNow(1000);
  const tick = useResumeTick();
  const [cancel, setCancel] = useState(false);
  const [asking, setAsking] = useState(false);
  const [replacing, setReplacing] = useState(false);
  const [okayed, setOkayed] = useState<string | null>(null);
  const [rating, setRating] = useState(false);
  const [agreeing, setAgreeing] = useState<string | null>(null);
  const [raising, setRaising] = useState(false);
  const [fareErr, setFareErr] = useState<string | null>(null);
  const [fareInfo, setFareInfo] = useState<string | null>(null);
  const q = useCall<{ request: AmbReq | null }>('ambreq_status', { id }, {
    staleTime: 0,
    // অপেক্ষায় ৪ সেকেন্ড পরপর, রাজির পরে ১০ সেকেন্ড, বন্ধ হলে থামে (পেছনের ট্যাবে ব্রাউজার নিজেই থামায়)
    refetchInterval: (query) => {
      const r = (query.state.data as { request: AmbReq | null } | undefined)?.request;
      return !r ? 4000 : r.status === 'open' ? 4000 : isActive(r) ? 10000 : false;
    },
  });
  const r = q.data?.request ?? null;
  const set = (x: AmbReq) => {
    qc.setQueryData(['ambreq_status', { id }], { request: x });
    qc.invalidateQueries({ queryKey: ['ambreq_status'] });
  };
  // ফোন করে ফিরে এলে একবার: "ফোনে কোনো গাড়ি পেয়েছেন?"
  const asked = useRef(0);
  useEffect(() => {
    if (r && r.status === 'open' && r.called.length && store.get(ASK_KEY) === id && asked.current !== tick + 1) {
      asked.current = tick + 1;
      store.set(ASK_KEY, null);
      setAsking(true);
    }
  }, [tick, r, id]);

  const title = tr('Calling an ambulance', 'অ্যাম্বুলেন্স ডাকা');
  if (q.isLoading) return <FormPage title={title} backTo="/services/ambulance"><Loading /></FormPage>;
  if (!r) {
    return (
      <FormPage title={title} backTo="/services/ambulance">
        <EmptyState icon="ambulance" title={q.isError ? q.error.message : tr('Not found', 'পাওয়া যায়নি')} />
      </FormPage>
    );
  }
  const left = Math.max(0, Math.round((r.expiresAt - now) / 1000));
  const quotes = r.status === 'open' ? (r.quotes ?? []).filter((x) => x.expiresAt > now) : [];
  async function agree(x: AmbQuote) {
    setAgreeing(x.id);
    setFareErr(null);
    try {
      set((await call<{ request: AmbReq }>('ambreq_agree', { id, ambulanceId: x.id })).request);
    } catch (e) {
      setFareErr(errorText(e));
      q.refetch();
    } finally {
      setAgreeing(null);
    }
  }
  async function raiseBudget(maxFare: number | null, includeUnknown = false) {
    setRaising(true);
    setFareErr(null);
    setFareInfo(null);
    try {
      const out = await call<{ added: number; request: AmbReq }>('ambreq_budget', { id, maxFare, includeUnknown: includeUnknown || !!r?.includeUnknown });
      set(out.request);
      setFareInfo(out.added
        ? tr(`Sent to ${out.added} more ambulance${out.added > 1 ? 's' : ''}`, `আরও ${num(out.added)}টা গাড়িকে জানানো হয়েছে`)
        : tr('No new ambulance within this fare — drivers already notified can now offer up to it', 'এই ভাড়ায় নতুন কোনো গাড়ি নেই — আগে জানানো চালকরা এখন এই পর্যন্ত বলতে পারবেন'));
    } catch (e) {
      setFareErr(errorText(e));
    } finally {
      setRaising(false);
    }
  }
  const others = r.called.filter((c) => c.id !== r.ambulance?.id);
  const callCar = (c: AmbCar) => {
    if (isActive(r)) markCalled(id);
    call('ambulance_called', { id: c.id, ...(isActive(r) ? { reqId: id } : {}) }).catch(() => {});
  };
  const head = (icon: string, t: string, sub?: string) => (
    <div className="row gap-12">
      <Icon name={icon} className="amb-head-ic" />
      <div className="col gap-2 grow">
        <h2 className="t-title-l">{t}</h2>
        {sub ? <span className="t-body-m muted">{sub}</span> : null}
      </div>
    </div>
  );

  return (
    <FormPage title={title} backTo="/services/ambulance">
      <div className="col gap-16 amb-wait" data-status={r.status}>
        {r.status === 'open' && quotes.length ? (
          <div className="col gap-8" data-testid="amb-quotes">
            <h2 className="t-title-l">{tr('Fare offers — pick one', 'ভাড়ার প্রস্তাব — একটা বেছে নিন')}</h2>
            <span className="t-body-s muted">{tr('Confirmed only when you tap "Agree". Each offer stays 2 minutes.', '"রাজি" চাপলে তবেই নিশ্চিত। প্রতিটা প্রস্তাব ২ মিনিট থাকে।')}</span>
            {quotes.map((x) => {
              const secs = Math.max(0, Math.round((x.expiresAt - now) / 1000));
              return (
                <div key={x.id} className="amb-quote" data-testid="amb-quote">
                  <div className="col gap-2 grow">
                    <span className="t-title-s">{x.name}</span>
                    <span className="t-body-s muted">
                      {[x.types.map((t) => reqTypeLabel(t)).join(', '), kmText(x.km, x.kmApprox), x.ratingCount ? `★ ${num(x.rating)} (${num(x.ratingCount)})` : null].filter(Boolean).join(' · ')}
                    </span>
                    <span className="t-body-s muted num">{tr('Expires in ', 'থাকবে আর ') + num(`${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`)}</span>
                  </div>
                  <div className="col gap-6 amb-quote-act">
                    <span className="amb-quote-fare">{taka(x.fare)}</span>
                    <Button small icon="check_circle" busy={agreeing === x.id} disabled={!!agreeing} onClick={() => agree(x)} data-testid="amb-agree">{tr('Agree', 'রাজি')}</Button>
                  </div>
                </div>
              );
            })}
          </div>
        ) : null}
        {r.status === 'open' ? (
          <div className="row gap-12">
            <span className="spinner" />
            <div className="col gap-2 grow">
              <h2 className={quotes.length ? 't-title-s' : 't-title-l'}>{quotes.length ? tr('Waiting for more offers…', 'আরও প্রস্তাবের অপেক্ষা…') : tr('Looking for an ambulance…', 'অ্যাম্বুলেন্স খোঁজা হচ্ছে…')}</h2>
              <span className="t-body-m muted">
                {r.wave > 1
                  ? tr(`Sent to ${r.notified} ambulances — more were added`, `${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে — আরও গাড়ি যোগ হয়েছে`)
                  : r.quote
                    ? tr(`Sent to ${r.notified} nearby ambulances within your fare. Offers will show here.`, `আপনার ভাড়ার মধ্যে কাছের ${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে। প্রস্তাব এলে এখানে দেখাবে।`)
                    : tr(`Sent to ${r.notified} nearby ambulances. We'll tell you as soon as one accepts.`, `কাছের ${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে। কেউ রাজি হলেই জানাবো।`)}
              </span>
              <span className="t-body-s muted num">{tr('Waiting: ', 'অপেক্ষা: ') + num(`${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`)}</span>
            </div>
          </div>
        ) : null}
        {(r.status === 'accepted' || r.status === 'arrived') && r.ambulance ? (
          <div className="amb-accepted">
            <div className="row gap-12">
              <span className="amb-accepted-ic"><Icon name="ambulance" /></span>
              <div className="col gap-2 grow">
                <span className="t-label-l">{r.status === 'arrived' ? tr('Arrived', 'পৌঁছেছে') : tr('Coming', 'আসছে')}</span>
                <h2 className="t-title-l">{r.ambulance.name}</h2>
                {kmText(r.ambulance.km, r.ambulance.kmApprox) ? <span className="t-body-m">{kmText(r.ambulance.km, r.ambulance.kmApprox) + tr(' away', ' দূরে')}</span> : null}
              </div>
            </div>
            {r.agreedFare != null ? (
              <div className="amb-agreed" data-testid="amb-agreed-fare">
                <span className="t-label-l">{tr('Agreed fare', 'ঠিক হওয়া ভাড়া')}</span>
                <span className="amb-agreed-fare">{taka(r.agreedFare)}</span>
                {r.destLabel ? <span className="t-body-s">{tr('To ', 'গন্তব্য: ') + r.destLabel}</span> : null}
              </div>
            ) : null}
            {r.ambulance.phone ? (
              <a className="btn square has-icon block" href={`tel:${r.ambulance.phone}`} onClick={() => callCar(r.ambulance!)}>
                <Icon name="call" />
                {tr('Call driver ', 'চালককে ফোন ') + num(r.ambulance.phone)}
              </a>
            ) : null}
          </div>
        ) : null}
        {r.status === 'done' ? (
          <>
            {head('check_circle', tr('Trip finished', 'ট্রিপ শেষ'), r.ambulance?.name)}
            {r.canRate && r.ambulance ? <Button icon="star" onClick={() => setRating(true)}>{tr('Rate the ambulance', 'অ্যাম্বুলেন্সকে রেটিং দিন')}</Button> : null}
          </>
        ) : null}
        {r.status === 'byPhone' ? (
          <>
            {head('check_circle', tr('You got an ambulance by phone', 'ফোনে গাড়ি পেয়েছেন'), r.ambulance?.name)}
            {r.ambulance?.phone ? <a className="btn outline square has-icon" href={`tel:${r.ambulance.phone}`}><Icon name="call" />{tr('Call ', 'ফোন: ') + num(r.ambulance.phone)}</a> : null}
          </>
        ) : null}
        {r.status === 'expired' ? head('warning', tr('No ambulance accepted', 'কোনো অ্যাম্বুলেন্স রাজি হয়নি'), tr('Please call one directly below.', 'নিচে থেকে সরাসরি ফোন করুন।')) : null}
        {r.status === 'cancelled'
          ? head('info', r.cancelledBy === 'driver' ? tr(`${r.ambulance?.name ?? 'The driver'} cancelled`, `${r.ambulance?.name ?? 'চালক'} বাতিল করেছেন`) : tr('You cancelled the request', 'আপনি অনুরোধ বাতিল করেছেন'), reasonText(r.cancelReason))
          : null}

        {r.status === 'open' && r.quote && r.wave > 1 && !quotes.length ? (
          <div className="amb-raise col gap-8" data-testid="amb-raise-box">
            <span className="t-body-m">{tr('Nobody has offered yet. Raise the fare to reach more ambulances:', 'এখনো কেউ ভাড়া বলেননি। আরও গাড়ি পেতে ভাড়া বাড়ান:')}</span>
            <div className="row wrap gap-8">
              {r.raiseTo != null ? <Button small icon="add" busy={raising} onClick={() => raiseBudget(r.raiseTo!)} data-testid="amb-raise">{tr(`Raise to ${taka(r.raiseTo)}`, `${taka(r.raiseTo)} পর্যন্ত বাড়ান`)}</Button> : null}
              {r.maxFare != null ? <Button small variant="outline" busy={raising} onClick={() => raiseBudget(null)}>{tr('Any fare', 'যেকোনো ভাড়া')}</Button> : null}
              {!r.includeUnknown && (r.unknownFree ?? 0) > 0 ? (
                <Button small variant="outline" busy={raising} onClick={() => raiseBudget(r.maxFare ?? null, true)}>{tr(`Include ${r.unknownFree} with unknown fare`, `ভাড়া জানা নেই এমন ${num(r.unknownFree!)}টাও`)}</Button>
              ) : null}
            </div>
          </div>
        ) : null}
        <ErrorText>{fareErr}</ErrorText>
        {fareInfo ? <span className="t-body-s muted" role="status">{fareInfo}</span> : null}

        {r.fallback.length && ['open', 'expired', 'cancelled'].includes(r.status) ? (
          <div className="col gap-8">
            <h3 className="t-title-s">{r.status === 'open' ? tr('Or call one now:', 'অথবা এখনই ফোন করুন:') : tr('Nearest free ambulances:', 'কাছের খালি অ্যাম্বুলেন্স:')}</h3>
            {r.fallback.map((c) => (
              <div key={c.id} className="amb-fallback">
                <div className="col gap-2 grow">
                  <span className="t-title-s">{c.name}</span>
                  {kmText(c.km, c.kmApprox) ? <span className="t-body-s muted">{kmText(c.km, c.kmApprox)}</span> : null}
                </div>
                <a className="btn square has-icon small" href={`tel:${c.phone}`} onClick={() => callCar(c)}><Icon name="call" />{tr('Call', 'ফোন করুন')}</a>
              </div>
            ))}
          </div>
        ) : null}

        {r.status === 'open' && r.called.length ? <Button variant="outline" icon="call" onClick={() => setAsking(true)}>{tr('Got an ambulance by phone?', 'ফোনে কোনো গাড়ি পেয়েছেন?')}</Button> : null}

        {(r.status === 'accepted' || r.status === 'arrived') && others.length && okayed !== r.ambulance?.id ? (
          <div className="amb-replace col gap-8">
            <span className="t-body-m">{tr(`${r.ambulance?.name} is coming. Did you also book another one by phone?`, `${r.ambulance?.name} আসছে। ফোনে অন্য গাড়িও ঠিক করেছেন?`)}</span>
            <div className="row wrap gap-8">
              <Button onClick={() => setOkayed(r.ambulance?.id ?? null)}>{tr('OK', 'ঠিক আছে')}</Button>
              <Button variant="outline" onClick={() => setReplacing(true)}>{tr('Got another by phone — cancel this one', 'ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল')}</Button>
            </div>
          </div>
        ) : null}

        {isActive(r) ? (
          <>
            <div className="card col gap-6" data-testid="amb-req-info">
              <IconLine icon="ambulance">{tr('Type: ', 'ধরন: ') + reqTypeLabel(r.type)}</IconLine>
              <IconLine icon="map">{areaLabel(r.district, r.upazilaId, r.unionId) + (r.gps ? tr(' · current location given', ' · এখনকার জায়গা দেওয়া') : '')}</IconLine>
              <IconLine icon="location_on">{r.pickup}</IconLine>
              {r.quote && r.destLabel ? <IconLine icon="local_hospital">{tr('Going to: ', 'যাবেন: ') + r.destLabel + (r.oxygen ? tr(' · oxygen', ' · অক্সিজেন') : '')}</IconLine> : null}
              {r.quote && r.status === 'open' ? <IconLine icon="tune">{tr('Fare up to: ', 'ভাড়া সর্বোচ্চ: ') + (r.maxFare != null ? taka(r.maxFare) : tr('any', 'যেকোনো'))}</IconLine> : null}
              {r.note ? <IconLine icon="info">{r.note}</IconLine> : null}
            </div>
            <Button variant="danger-text" onClick={() => setCancel(true)}>{tr('Cancel request', 'অনুরোধ বাতিল করুন')}</Button>
          </>
        ) : (
          <Button variant="outline" icon="arrow_back" onClick={() => nav('/services/ambulance')}>{tr('Back to the list', 'তালিকায় ফিরুন')}</Button>
        )}
        <a className="btn danger-text square has-icon small" href="tel:999"><Icon name="call" />{tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা')}</a>
      </div>

      {cancel ? (
        <ReasonDialog
          title={tr('Why cancel?', 'কেন বাতিল করছেন?')}
          reasons={PATIENT_REASONS()}
          onClose={() => setCancel(false)}
          onPick={async (reason) => {
            await call('ambreq_cancel', { id, reason });
            setCancel(false);
            await q.refetch();
            qc.invalidateQueries({ queryKey: ['ambreq_status'] });
          }}
        />
      ) : null}
      {asking ? (
        <PickCarDialog
          title={tr('Got an ambulance by phone?', 'ফোনে কোনো গাড়ি পেয়েছেন?')}
          cars={r.called}
          noLabel={tr('No, keep looking', 'না, খুঁজতে থাকুন')}
          onClose={() => setAsking(false)}
          onPick={async (c) => {
            if (c) set((await call<{ request: AmbReq }>('ambreq_byphone', { id, ambulanceId: c.id })).request);
            setAsking(false);
          }}
        />
      ) : null}
      {replacing ? (
        <PickCarDialog
          title={tr('Which one did you get by phone?', 'ফোনে কোন গাড়ি পেয়েছেন?')}
          cars={others}
          noLabel={tr('None — keep looking', 'কোনোটা না — আবার খুঁজুন')}
          onClose={() => setReplacing(false)}
          onPick={async (c) => {
            set((await call<{ request: AmbReq }>('ambreq_replace', { id, ...(c ? { ambulanceId: c.id } : {}) })).request);
            setReplacing(false);
          }}
        />
      ) : null}
      {rating && r.ambulance ? (
        <RatingDialog
          kind="ambulance"
          id={r.ambulance.id}
          name={r.ambulance.name}
          agreedFare={r.agreedFare ?? null}
          onClose={() => setRating(false)}
        />
      ) : null}
    </FormPage>
  );
}
