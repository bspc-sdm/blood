// "আমার কাজ" — সব ব্যবসার সাধারণ অংশ (অ্যাপের Work.kt, Verify.kt, Photo.kt, Ambulance.kt-এর মালিকের অংশ)
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Check, ChoiceChips, Dialog, EmptyState, ErrorText, Field, Loading, PillTabs, Tag, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { AreaPicker, type AreaValue } from '@/ui/AreaPicker';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { num, tr } from '@/lib/i18n';
import { areaLabel, areasReady, ensureAreas } from '@/lib/geo';
import { formatTimeHM } from '@/lib/format';
import { SubPageBar, Workspace } from '@/shell/templates';
import { useMobile } from '@/shell/chrome';

// ---------- ধরন ----------
export type Kind = 'ambulance' | 'pharmacy' | 'bloodbank' | 'lab';
export const KINDS: Kind[] = ['ambulance', 'pharmacy', 'bloodbank', 'lab'];
export const MAX_AMBULANCES = 10;
export const MAX_BLOODBANKS = 3;

export const businessName = (k: Kind) =>
  k === 'ambulance' ? tr('Ambulance', 'অ্যাম্বুলেন্স') : k === 'pharmacy' ? tr('Pharmacy', 'ফার্মেসি') : k === 'bloodbank' ? tr('Blood bank', 'ব্লাড ব্যাংক') : tr('Lab', 'প্যাথলজি');
export const businessIcon = (k: Kind) => (k === 'ambulance' ? 'ambulance' : k === 'pharmacy' ? 'storefront' : k === 'bloodbank' ? 'bloodtype' : 'local_hospital');
export const myBusinessTitle = (k: Kind) =>
  k === 'ambulance' ? tr('My ambulances', 'আমার অ্যাম্বুলেন্স') : k === 'pharmacy' ? tr('My pharmacy', 'আমার ফার্মেসি') : k === 'bloodbank' ? tr('My blood banks', 'আমার ব্লাড ব্যাংক') : tr('My lab', 'আমার প্যাথলজি');

export const badgeLabel = (t: string) =>
  t === 'doctor' ? tr('Doctor', 'ডাক্তার') : t === 'nurse' ? tr('Nurse', 'নার্স') : t === 'nutritionist' ? tr('Nutritionist', 'পুষ্টিবিদ') : t === 'trainer' ? tr('Fitness trainer', 'ফিটনেস ট্রেইনার') : tr('Health Expert', 'স্বাস্থ্য বিশেষজ্ঞ');
export const badgeIcon = (t: string) =>
  t === 'doctor' ? 'stethoscope' : t === 'nurse' ? 'medical_services' : t === 'nutritionist' ? 'restaurant' : t === 'trainer' ? 'fitness_center' : 'health_and_safety_filled';

// ---------- সার্ভারের আকার ----------
export interface DocFlags { docsMissing?: boolean; docsRequested?: boolean; docsRequestNote?: string }
export interface Ambulance extends DocFlags {
  id: string; name: string; phone: string; types: string[]; district: string; upazilaId: number | null; area: string; fare: string;
  available: boolean; availableAt: number; rating: number; ratingCount: number; vehicleNo: string; status: string; statusReason: string;
  /** খালি থাকলে কখন পর্যন্ত (১২ ঘণ্টা); গাড়ির জায়গা (GPS) দেওয়া আছে কি না */
  availableUntil?: number; hasBase?: boolean;
  /** ভাড়ার তালিকা (২.৩০.০) */
  fareChart?: import('../services/fare').FareChart | null;
}
export interface Pharmacy extends DocFlags {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; openAt: string; closeAt: string;
  open24: boolean; openNow: boolean; closedUntil: number; closedDays: number[]; delivery: boolean; deliveryNote: string; discount: number;
  rating: number; ratingCount: number; licenseNo: string; status: string; statusReason: string; hasLocation: boolean;
  /** ছাড়ের শর্ত (২.৩২.০) — ০/খালি = নেই */
  discountMin?: number; freeDeliveryOver?: number; discountNote?: string;
}
export interface BloodBank extends DocFlags {
  id: string; name: string; kind: string; phone: string; district: string; upazilaId: number | null; address: string; open24: boolean;
  openAt: string; closeAt: string; openNow: boolean; services: string[]; closedDays: number[]; licenseNo: string; status: string; statusReason: string;
}
export interface Lab extends DocFlags {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null;
  homeCollection: boolean; hours: string; licenseNo: string; status: string; statusReason: string;
}
export interface CallSummary { calls: { 7: number; 30: number; 90: number; total: number }; callers: number; daily: number[] }
export interface ListingStats extends CallSummary {
  id: string; name: string; status: string; reports: number;
  rating?: number; ratingCount?: number; comments?: { rating: number; comment: string; at: number }[];
}

// ---------- অবস্থা ----------
/** অ্যাপের statusText */
export const statusText = (s: string) =>
  s === 'approved' ? tr('Listed', 'তালিকায় আছে') : s === 'pending' ? tr('Waiting for approval', 'অনুমোদনের অপেক্ষায়') : s === 'rejected' ? tr('Not approved', 'অনুমোদন হয়নি') : s === 'suspended' ? tr('Suspended by the Lifolo team', 'Lifolo টিম বন্ধ করেছে') : s;
/** ছোট লেবেল (অ্যাপের statusNote; চালু থাকলে "তালিকায় আছে") */
export const statusShort = (s: string) =>
  s === 'approved' ? tr('Listed', 'তালিকায় আছে') : s === 'pending' ? tr('Under review', 'যাচাই চলছে') : s === 'rejected' ? tr('Not approved', 'অনুমোদন হয়নি') : s === 'suspended' ? tr('Suspended', 'বন্ধ করা হয়েছে') : s;

export function StatusTag({ status, closed }: { status: string; closed?: boolean }) {
  if (status === 'approved' && closed) return <Tag>{tr('Closed', 'বন্ধ')}</Tag>;
  const tone = status === 'approved' ? 'good' : status === 'pending' ? 'info' : 'error';
  return <Tag tone={tone}>{statusShort(status)}</Tag>;
}

/** একাধিক থাকলে: কয়টা যাচাই চলছে / বাতিল / বন্ধ */
export function statusCounts(statuses: string[]): string[] {
  return (['pending', 'rejected', 'suspended'] as const).flatMap((s) => {
    const n = statuses.filter((x) => x === s).length;
    if (!n) return [];
    return [s === 'pending' ? tr(`${n} under review`, `${num(n)}টা যাচাই চলছে`) : s === 'rejected' ? tr(`${n} not approved`, `${num(n)}টা অনুমোদন হয়নি`) : tr(`${n} suspended`, `${num(n)}টা বন্ধ`)];
  });
}

export function StatusReason({ status, reason }: { status: string; reason?: string }) {
  if (!reason || !['rejected', 'suspended'].includes(status)) return null;
  return <p className="t-body-s c-error">{tr('Reason: ', 'কারণ: ') + reason}</p>;
}

/** বাতিল/বন্ধ হলে: আপিল (সাহায্য পাতা) */
export function AppealButton({ category }: { category?: 'ambulance' | 'pharmacy' | 'bloodbank' | 'other' }) {
  return (
    <Link to={`/help/new?category=${category ?? 'other'}`} className="btn outline has-icon small">
      <Icon name="help" />
      {tr('Appeal / contact us', 'আপিল করুন / আমাদের লিখুন')}
    </Link>
  );
}

// ---------- সময় ----------
export const WEEK_ORDER = [6, 0, 1, 2, 3, 4, 5];
export const weekdayShort = (d: number) =>
  [tr('Sun', 'রবি'), tr('Mon', 'সোম'), tr('Tue', 'মঙ্গল'), tr('Wed', 'বুধ'), tr('Thu', 'বৃহস্পতি'), tr('Fri', 'শুক্র'), tr('Sat', 'শনি')][d] ?? '';
export function hhmmText(hhmm: string): string {
  const [h, m] = hhmm.split(':').map((x) => parseInt(x, 10));
  return Number.isFinite(h) ? formatTimeHM(h, Number.isFinite(m) ? m : 0) : hhmm;
}
export const closedDaysText = (days: number[]) =>
  days.length ? tr('Closed every ', 'প্রতি ') + WEEK_ORDER.filter((d) => days.includes(d)).map(weekdayShort).join(', ') + tr('', ' বন্ধ') : '';
export function hoursText(x: { open24: boolean; openAt: string; closeAt: string }) {
  if (x.open24) return tr('Open 24 hours', '২৪ ঘণ্টা খোলা');
  return x.openAt ? `${hhmmText(x.openAt)} – ${hhmmText(x.closeAt)}` : '';
}

// ---------- ফোন ----------
/** মোবাইল, ল্যান্ডলাইন (02…, 031…) বা হটলাইন (16XXX) — সার্ভারের contactPhone-এর মতো */
export function isContactPhone(raw: string): boolean {
  const d = raw.replace(/[০-৯]/g, (c) => String('০১২৩৪৫৬৭৮৯'.indexOf(c))).replace(/\D/g, '');
  const local = d.startsWith('880') ? '0' + d.slice(3) : d;
  return /^0?1[3-9]\d{8}$/.test(local) || /^1\d{4}$/.test(d) || /^0[2-9]\d{7,9}$/.test(local);
}
export const phoneInput = (v: string) => v.replace(/[০-৯]/g, (c) => String('০১২৩৪৫৬৭৮৯'.indexOf(c))).replace(/[^\d+]/g, '').slice(0, 15);

// ---------- এলাকা ----------
/** উপজেলার নাম দেখাতে তালিকা আনা (একবার) */
export function useAreas() {
  const [, set] = useState(areasReady());
  useEffect(() => {
    if (!areasReady()) ensureAreas().then(() => set(true), () => {});
  }, []);
}

export function AreaField({ label, value, onChange, error }: { label: string; value: AreaValue | null; onChange: (v: AreaValue) => void; error?: string }) {
  const [open, setOpen] = useState(false);
  useAreas();
  return (
    <Field label={label} error={error}>
      <button type="button" className="area-btn work-area-btn" onClick={() => setOpen(true)}>
        <Icon name="location_on" />
        <span>{value ? areaLabel(value.district, value.upazilaId) : tr('Choose area', 'এলাকা বেছে নিন')}</span>
        <Icon name="expand_more_round" />
      </button>
      {open ? (
        <AreaPicker
          value={value}
          onPicked={(v) => {
            onChange({ district: v.district, upazilaId: v.upazilaId ?? null });
            setOpen(false);
          }}
          onClose={() => setOpen(false)}
        />
      ) : null}
    </Field>
  );
}

export function WeekdayChips({ value, onChange }: { value: number[]; onChange: (v: number[]) => void }) {
  return (
    <Field label={tr('Weekly closed days (if any)', 'সাপ্তাহিক বন্ধ (থাকলে)')}>
      <ChoiceChips
        multi
        options={WEEK_ORDER.map((d) => ({ value: d, label: weekdayShort(d) }))}
        value={value}
        onChange={(v: number[]) => onChange(v.length > 6 ? value : v)}
      />
    </Field>
  );
}

export function TimeRange({ openAt, closeAt, onChange }: { openAt: string; closeAt: string; onChange: (o: string, c: string) => void }) {
  return (
    <div className="work-two">
      <Field label={tr('Opens at', 'খোলে')}>
        <input type="time" className="input" value={openAt} onChange={(e) => onChange(e.target.value, closeAt)} />
      </Field>
      <Field label={tr('Closes at', 'বন্ধ হয়')}>
        <input type="time" className="input" value={closeAt} onChange={(e) => onChange(openAt, e.target.value)} />
      </Field>
    </div>
  );
}

// ---------- ক্যাটালগ (অ্যাম্বুলেন্সের ধরন, ব্লাড ব্যাংকের সেবা) ----------
interface CatItem { code: string; en: string; bn: string; hidden?: boolean }
const BUILT_IN: Record<string, CatItem[]> = {
  ambulanceType: [
    { code: 'general', en: 'General', bn: 'সাধারণ' }, { code: 'ac', en: 'AC', bn: 'এসি' },
    { code: 'icu', en: 'ICU', bn: 'আইসিইউ' }, { code: 'freezer', en: 'Freezer van', bn: 'ফ্রিজার ভ্যান' },
  ],
  bloodBankService: [
    { code: 'whole_blood', en: 'Whole blood', bn: 'পূর্ণ রক্ত' }, { code: 'components', en: 'Plasma & components', bn: 'প্লাজমা ও অন্যান্য অংশ' },
    { code: 'platelets', en: 'Platelets', bn: 'প্লাটিলেট' }, { code: 'screening', en: 'Screening & cross-match', bn: 'স্ক্রিনিং ও ক্রস-ম্যাচ' },
    { code: 'donate_here', en: 'You can donate here', bn: 'এখানে রক্ত দেওয়া যায়' },
  ],
};
/** অ্যাডমিনের তালিকা (catalog_get); না এলে অ্যাপের নিজের তালিকা */
export function useCatalog(kind: 'ambulanceType' | 'bloodBankService') {
  const q = useCall<{ lists: Record<string, { all?: CatItem[] }> }>('catalog_get', {}, { staleTime: 10 * 60_000 });
  const list = q.data?.lists?.[kind]?.all ?? BUILT_IN[kind];
  const label = (code: string) => {
    const it = list.find((i) => i.code === code) ?? BUILT_IN[kind].find((i) => i.code === code);
    return it ? tr(it.en, it.bn) : code;
  };
  return { options: list.filter((i) => !i.hidden).map((i) => ({ value: i.code, label: tr(i.en, i.bn) })), label };
}

// ---------- কাগজের ছবি (Verify.kt) ----------
export interface DocSpec { type: string; label: string; hint: string; required: boolean }
export const ambulanceDocs = (): DocSpec[] => [
  { type: 'reg', label: tr('Registration certificate or smart card', 'রেজিস্ট্রেশন সনদ বা স্মার্ট কার্ড'), hint: tr('BRTA paper — vehicle number and type readable', 'BRTA-র কাগজ — গাড়ির নম্বর আর ধরন পরিষ্কার পড়া যায়'), required: true },
  { type: 'fitness', label: tr('Fitness certificate', 'ফিটনেস সনদ'), hint: tr('Expiry date readable', 'মেয়াদের তারিখ পড়া যায় এমন'), required: true },
  { type: 'front', label: tr('Photo of the vehicle (with number plate)', 'গাড়ির ছবি (নম্বর প্লেটসহ)'), hint: tr('From front or back, plate readable', 'সামনে বা পেছন থেকে, প্লেট পড়া যায়'), required: true },
];
export const pharmacyDocs = (): DocSpec[] => [
  { type: 'license', label: tr('Drug licence', 'ড্রাগ লাইসেন্স'), hint: tr('Whole paper, number and expiry readable', 'পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়'), required: true },
  { type: 'front', label: tr('Shop front (with signboard)', 'দোকানের সামনের ছবি (সাইনবোর্ডসহ)'), hint: tr('Shop name readable', 'দোকানের নাম পড়া যায়'), required: true },
  { type: 'pharmacist', label: tr('Pharmacist registration certificate', 'ফার্মাসিস্টের নিবন্ধন সনদ'), hint: tr('If you have it', 'থাকলে দিন'), required: false },
];
export const bloodBankDocs = (kind: string): DocSpec[] => [
  { type: 'front', label: tr('Photo of the blood bank (signboard or entrance)', 'ব্লাড ব্যাংকের ছবি (সাইনবোর্ড বা প্রবেশপথ)'), hint: tr('Name readable', 'নাম পড়া যায়'), required: true },
  { type: 'license', label: tr('DGHS blood bank licence', 'স্বাস্থ্য অধিদপ্তরের ব্লাড ব্যাংক লাইসেন্স'), hint: tr('Needed for private blood banks', 'বেসরকারি হলে লাগবে'), required: kind === 'private' },
  { type: 'authority', label: tr("Letter on the hospital's or organisation's pad", 'হাসপাতাল বা সংগঠনের প্যাডে প্রত্যয়ন'), hint: tr('For government hospitals or organisations', 'সরকারি হাসপাতাল বা সংগঠন হলে দিন'), required: false },
];
export const labDocs = (): DocSpec[] => [
  { type: 'license', label: tr('DGHS clinic/diagnostic licence', 'স্বাস্থ্য অধিদপ্তরের ক্লিনিক/ডায়াগনস্টিক লাইসেন্স'), hint: tr('Whole paper, number and expiry readable', 'পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়'), required: true },
  { type: 'front', label: tr('Centre front (with signboard)', 'সেন্টারের সামনের ছবি (সাইনবোর্ডসহ)'), hint: tr('Name readable', 'নাম পড়া যায়'), required: true },
];
export function missingDocs(specs: DocSpec[], photos: Record<string, string>): string | null {
  const miss = specs.filter((d) => d.required && !photos[d.type]);
  return miss.length ? tr('Add photos of: ', 'এই কাগজের ছবি দিন: ') + miss.map((d) => d.label).join(', ') : null;
}
export const PAPERS_CHANGED = () => tr('Add new photos only if the papers changed.', 'কাগজ বদলালে তবেই নতুন ছবি দিন।');

/** কাগজের ছবি: লম্বা দিক সর্বোচ্চ ১৪০০ পিক্সেল, JPEG; base64 ২৮০ KB-র মধ্যে না আসা পর্যন্ত মান কমানো (অ্যাপের Photo.toDocumentBase64) */
export async function compressDocument(file: File): Promise<string> {
  const fail = () => new Error(tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'));
  if (!file.type.startsWith('image/')) throw fail();
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((res, rej) => {
      const i = new Image();
      i.onload = () => res(i);
      i.onerror = () => rej(fail());
      i.src = url;
    });
    const w0 = img.naturalWidth;
    const h0 = img.naturalHeight;
    if (!w0 || !h0) throw fail();
    let maxSide = 1400;
    let quality = 0.75;
    for (;;) {
      const scale = Math.min(1, maxSide / Math.max(w0, h0));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(w0 * scale));
      canvas.height = Math.max(1, Math.round(h0 * scale));
      const ctx = canvas.getContext('2d');
      if (!ctx) throw fail();
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      const b64 = canvas.toDataURL('image/jpeg', quality).replace(/^data:image\/jpeg;base64,/, '');
      if (b64.length <= 280 * 1024 || maxSide <= 700) return b64;
      if (quality > 0.55) quality -= 0.1;
      else maxSide -= 200;
    }
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function DocPhotosField({ specs, photos, onChange, note }: { specs: DocSpec[]; photos: Record<string, string>; onChange: (p: Record<string, string>) => void; note?: string | null }) {
  const input = useRef<HTMLInputElement>(null);
  const [picking, setPicking] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  async function picked(file: File | undefined) {
    const type = picking;
    setPicking(null);
    if (!file || !type) return;
    setBusy(type);
    try {
      const b = await compressDocument(file);
      onChange({ ...photos, [type]: b });
      setErr(null);
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(null);
    }
  }
  return (
    <div className="col gap-8">
      <span className="t-label-l">{tr('Document photos', 'কাগজের ছবি')}</span>
      <span className="t-body-s muted">{note ?? tr('Clear photo of the whole paper.', 'পুরো কাগজের পরিষ্কার ছবি দিন।')}</span>
      <input
        ref={input}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          picked(e.target.files?.[0]);
          e.target.value = '';
        }}
      />
      <div className="work-docs">
        {specs.map((d) => {
          const img = photos[d.type];
          return (
            <div key={d.type} className="work-doc">
              {img ? <img src={`data:image/jpeg;base64,${img}`} alt="" className="work-doc-thumb" /> : <span className="work-doc-thumb blank"><Icon name="photo_camera" /></span>}
              <div className="col gap-2 grow">
                <span className="t-title-s">{d.label + (d.required ? ' *' : '')}</span>
                <span className="t-body-s muted">{d.hint}</span>
              </div>
              <Button
                variant="text"
                small
                busy={busy === d.type}
                disabled={busy != null}
                onClick={() => {
                  setPicking(d.type);
                  input.current?.click();
                }}
              >
                {img ? tr('Change', 'বদলান') : tr('Add photo', 'ছবি দিন')}
              </Button>
            </div>
          );
        })}
      </div>
      <ErrorText>{err}</ErrorText>
    </div>
  );
}

/** মালিকের কার্ডে: অ্যাডমিন ছবি চাইলে বা দরকারি ছবি বাকি থাকলে — কারণসহ "ছবি দিন" */
export function DocsNeededNotice({ kind, id, specs, item, onDone, title }: { kind: Kind; id: string; specs: DocSpec[]; item: DocFlags; onDone: () => void; title?: string }) {
  const [open, setOpen] = useState(false);
  const [photos, setPhotos] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  if (!item.docsMissing && !item.docsRequested) return null;
  async function send() {
    setBusy(true);
    setErr(null);
    try {
      await call('verify_docs_upload', { kind, id, docs: photos });
      setOpen(false);
      setPhotos({});
      onDone();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="card tonal work-notice">
      <Icon name="photo_camera" />
      <div className="col gap-4 grow">
        <span className="t-title-s">
          {item.docsRequested ? tr('The Lifolo team asked for document photos', 'Lifolo টিম কাগজের ছবি চেয়েছে') : tr('Document photos are needed for checking', 'যাচাইয়ের জন্য কাগজের ছবি লাগবে')}
        </span>
        {title ? <span className="t-body-s">{title}</span> : null}
        {item.docsRequestNote ? <span className="t-body-m pre">{item.docsRequestNote}</span> : null}
      </div>
      <Button variant="outline" small onClick={() => setOpen(true)}>{tr('Add photos', 'ছবি দিন')}</Button>
      {open ? (
        <Dialog
          onClose={() => !busy && setOpen(false)}
          title={tr('Add photos', 'ছবি দিন')}
          actions={
            <>
              <Button variant="text" onClick={() => setOpen(false)} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
              <Button busy={busy} disabled={!Object.keys(photos).length} onClick={send}>{tr('Send', 'পাঠান')}</Button>
            </>
          }
        >
          {title ? <p className="t-title-s">{title}</p> : null}
          <DocPhotosField specs={specs} photos={photos} onChange={setPhotos} />
          <ErrorText>{err}</ErrorText>
        </Dialog>
      ) : null}
    </div>
  );
}

// ---------- শর্ত ----------
export const TERMS_URL = 'https://github.com/bspc-sdm/blood/blob/main/TERMS.md';
export function TermsCheck({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="col gap-2">
      <Check checked={checked} onChange={onChange}>{tr('I accept the terms for service providers', 'সেবাদাতাদের শর্ত মেনে নিচ্ছি')}</Check>
      <a href={TERMS_URL} target="_blank" rel="noreferrer" className="t-body-s c-primary work-terms-link">{tr('Read the terms', 'শর্তগুলো পড়ুন')}</a>
    </div>
  );
}

// ---------- হিসাব ----------
/** ছোট বার চার্ট: গত ৩০ দিনে দিনে কতবার ফোন */
export function MiniBars({ values }: { values: number[] }) {
  const max = Math.max(1, ...values);
  const top = values.length ? Math.max(...values) : 0;
  return (
    <div className="col gap-4">
      <div className="work-bars" role="img" aria-label={tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন')}>
        {values.map((v, i) => (
          <span key={i} title={num(v)} style={{ height: v ? `${Math.max(4, (v / max) * 100)}%` : 0 }} />
        ))}
      </div>
      <div className="row t-label-s muted">
        <span className="grow">{tr('30 days ago', '৩০ দিন আগে')}</span>
        <span>{tr('Today', 'আজ')}</span>
      </div>
      <span className="t-body-s muted">{tr(`Highest in a day: ${top}`, `এক দিনে সর্বোচ্চ: ${num(top)}`)}</span>
    </div>
  );
}

export const callsLine = (c?: CallSummary | null) =>
  c ? tr(`${c.calls[7]} in 7 days · ${c.calls[30]} in 30 days`, `৭ দিনে ${num(c.calls[7])} · ৩০ দিনে ${num(c.calls[30])}`) : '—';

export function Stat({ value, label, icon }: { value: ReactNode; label: string; icon?: string }) {
  return (
    <div className="stat work-stat">
      {icon ? <Icon name={icon} className="work-stat-ic" /> : null}
      <span className="v">{value}</span>
      <span className="l">{label}</span>
    </div>
  );
}

/** একটা তালিকার পুরো হিসাব (ডায়ালগে): ফোন, চার্ট, রেটিং, মন্তব্য, রিপোর্ট */
export function StatsDialog({ title, st, onClose }: { title: string; st: ListingStats; onClose: () => void }) {
  const rc = st.ratingCount ?? 0;
  return (
    <Dialog onClose={onClose} title={title} size="wide">
      <div className="stats">
        <Stat value={num(st.calls[7])} label={tr('Calls in 7 days', '৭ দিনে ফোন')} />
        <Stat value={num(st.calls[30])} label={tr('Calls in 30 days', '৩০ দিনে ফোন')} />
        <Stat value={num(st.callers)} label={tr('Different callers', 'আলাদা মানুষ')} />
        {st.ratingCount != null ? <Stat value={rc ? num(String(st.rating)) : '—'} label={tr('Rating', 'রেটিং') + (rc ? ` (${num(rc)})` : '')} /> : null}
      </div>
      <p className="t-title-s">{tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন')}</p>
      <MiniBars values={st.daily} />
      <p className="t-body-s muted">{tr(`${st.callers} different people called from the app in total.`, `মোট ${num(st.callers)} জন আলাদা মানুষ অ্যাপ থেকে ফোন করেছেন।`)}</p>
      {st.reports > 0 ? <p className="t-body-m c-error">{tr(`${st.reports} report(s)`, `রিপোর্ট ${num(st.reports)}টি`)}</p> : null}
      {st.comments && st.comments.length ? (
        <div className="col gap-6">
          <p className="t-title-s">{tr('Recent ratings', 'সাম্প্রতিক রেটিং')}</p>
          {st.comments.map((c, i) => (
            <p key={i} className="t-body-s">
              <span className="work-stars">{'★'.repeat(Math.max(0, Math.min(5, c.rating)))}</span>
              {c.comment ? '  ' + c.comment : ''}
            </p>
          ))}
        </div>
      ) : null}
    </Dialog>
  );
}

// ---------- পাতার সাধারণ অংশ ----------
/** সেভের পর আগের পাতায় বার্তা (navigation state) */
export function useFlash(): [string | null, (m: string | null) => void] {
  const loc = useLocation();
  const nav = useNavigate();
  const [msg, setMsg] = useState<string | null>((loc.state as { flash?: string } | null)?.flash ?? null);
  useEffect(() => {
    const f = (loc.state as { flash?: string } | null)?.flash;
    if (f) {
      setMsg(f);
      nav(loc.pathname, { replace: true, state: null });
    }
  }, [loc.state, loc.pathname, nav]);
  return [msg, setMsg];
}
export function Flash({ msg, error }: { msg?: string | null; error?: string | null }) {
  if (!msg && !error) return null;
  return (
    <div className={cx('work-flash', error ? 'err' : 'ok')} role={error ? 'alert' : 'status'}>
      <Icon name={error ? 'warning' : 'check_circle'} />
      <span>{error ?? msg}</span>
    </div>
  );
}

export const sentForApproval = (kind: Kind) =>
  kind === 'pharmacy' || kind === 'lab'
    ? tr('Sent for approval. An admin will check the licence.', 'অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন।')
    : tr('Sent for approval. It will be listed after an admin checks it.', 'অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন যাচাই করলে তালিকায় আসবে।');

/** সেভ/মোছার পর: নিচের বার আর হিসাব নতুন করে */
export function useAfterChange() {
  const s = useSession();
  const qc = useQueryClient();
  return async () => {
    qc.invalidateQueries({ predicate: (q) => ['ambulance_stats', 'pharmacy_stats', 'bloodbank_stats', 'med_find_requests'].includes(String(q.queryKey[0])) });
    await s.refreshWork();
  };
}

/** লগইন ছাড়া বা কাজ না আসা পর্যন্ত */
export function useWorkGate(): ReactNode | null {
  const s = useSession();
  const nav = useNavigate();
  if (!s.ready) return <Loading />;
  if (s.guest) {
    return (
      <WorkEmpty
        icon="storefront"
        title={tr('Do you have a business?', 'আপনার কি কোনো ব্যবসা আছে?')}
        text={tr('Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।')}
      >
        <Button icon="add" onClick={() => s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav('/work/add'))}>
          {tr('Yes, add it', 'হ্যাঁ, যোগ করুন')}
        </Button>
      </WorkEmpty>
    );
  }
  if (!s.workLoaded) {
    if (s.workError) {
      return (
        <div className="col" style={{ alignItems: 'center', padding: 24 }}>
          <ErrorText>{s.workError}</ErrorText>
          <Button variant="text" onClick={() => s.refreshWork()}>{tr('Try again', 'আবার চেষ্টা করুন')}</Button>
        </div>
      );
    }
    return <Loading />;
  }
  return null;
}


/** একটা ব্যবসার পাতা: উপরে "পেছনে" বার ("আমার কাজ"-এ ফেরা), নিচে চওড়া কাজের পাতা */
export function BizPage({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  const mobile = useMobile();
  return (
    <>
      <SubPageBar title={title} backTo="/work" actions={!mobile && actions ? <div className="row gap-8 work-bar-actions">{actions}</div> : undefined} />
      <Workspace title={title} actions={mobile ? actions : undefined}>{children}</Workspace>
    </>
  );
}

/** খালি অবস্থা (ভেতরের বাটনের আইকন ছোট থাকে) */
export function WorkEmpty(props: { icon: string; title: string; text?: string; children?: ReactNode }) {
  return (
    <div className="work-empty">
      <EmptyState {...props} />
    </div>
  );
}

// ---------- ফার্মেসি আর প্যাথলজির দুই ট্যাব (২.২৭.০, অ্যাপের MyWorkTabs.kt) ----------
// ফার্মেসি (২.৩০.০ থেকে তিন ট্যাব): "অর্ডার" (/work/pharmacy/orders — শুরুতে) · "ওষুধ খোঁজা" (/work/pharmacy/finds) ·
//   "দোকান" — তথ্য (/work/pharmacy/shop) · বিক্রি (/shop/sales) · স্টক (/shop/stock, ?v=buy মাল কেনা) · এনালাইটিক্স (/shop/analytics)।
//   পুরোনো হিসাব-খাতার লিংক (/work/pharmacy/orders/stock|buy|sales|books) নতুন জায়গায় চলে যায় (rx/partner/pharmacy.tsx)।
// প্যাথলজি: "অনুরোধ" (/work/lab/orders) · "ল্যাব" (/work/lab/info, পরীক্ষা ও দাম, স্বাভাবিক মাত্রা)
// পুরোনো লিংক (/work/pharmacy, /work/lab, …/orders/online, নোটিফিকেশন) আগের মতোই খোলে — index.tsx।
export type PharmTab = 'orders' | 'finds' | 'info' | 'sales' | 'stock' | 'analytics';
export const PHARM_SHOP_TABS: PharmTab[] = ['info', 'sales', 'stock', 'analytics'];
/** দোকানের ভেতরের ভাগের ঠিকানা */
export const pharmTabPath = (k: PharmTab) => (k === 'orders' ? '/work/pharmacy/orders' : k === 'finds' ? '/work/pharmacy/finds' : k === 'info' ? '/work/pharmacy/shop' : `/work/pharmacy/shop/${k}`);

/** "ওষুধ খোঁজা"-র সংখ্যা: উত্তর না দেওয়া খোলা অনুরোধ (চালু থাকলে) */
export function useUnansweredFinds(enabled: boolean) {
  const q = useCall<{ items: { answer: string | null }[]; findOff: boolean }>('med_find_requests', {}, { enabled, refetchInterval: 60_000 });
  return q.data && !q.data.findOff ? q.data.items.filter((r) => !r.answer).length : 0;
}
export type LabTab = 'orders' | 'info' | 'prices' | 'ranges';

/** "নতুন" কয়টা — সার্ভারের counts (তালিকার পাতার একই চাবি, আবার আনা হয় না) */
function useNewCount(action: 'rx_orders_pharmacy' | 'lab_orders', enabled: boolean) {
  const q = useCall<{ items: { status: string; seg?: string }[]; counts?: Record<string, number> }>(action, {}, { enabled, refetchInterval: 60_000 });
  return q.data?.counts?.new ?? q.data?.items.filter((o) => (o.seg ? o.seg === 'new' : o.status === 'sent')).length ?? 0;
}

export function PharmacyTabs({ active }: { active: PharmTab }) {
  const nav = useNavigate();
  const s = useSession();
  const approved = (s.work.pharmacy as { status?: string } | null)?.status === 'approved';
  const fresh = useNewCount('rx_orders_pharmacy', approved);
  const finds = useUnansweredFinds(approved);
  if (!approved) return null;
  const top = active === 'orders' || active === 'finds' ? active : 'shop';
  return (
    <div className="col gap-8 work-tabs">
      <PillTabs
        label={myBusinessTitle('pharmacy')}
        value={top}
        onChange={(k) => nav(pharmTabPath(k === 'shop' ? 'info' : (k as PharmTab)))}
        tabs={[
          { key: 'orders', label: tr('Orders', 'অর্ডার'), icon: 'medication', count: fresh },
          { key: 'finds', label: tr('Medicine search', 'ওষুধ খোঁজা'), icon: 'search', count: finds },
          { key: 'shop', label: tr('Shop', 'দোকান'), icon: 'storefront' },
        ]}
      />
      {top === 'shop' ? (
        <PillTabs
          secondary
          label={tr('Shop', 'দোকান')}
          value={active}
          onChange={(k) => nav(pharmTabPath(k as PharmTab))}
          tabs={[
            { key: 'info', label: tr('Info', 'তথ্য'), icon: 'storefront' },
            { key: 'sales', label: tr('Sales', 'বিক্রি'), icon: 'payments' },
            { key: 'stock', label: tr('Stock', 'স্টক'), icon: 'list_alt' },
            { key: 'analytics', label: tr('Analytics', 'এনালাইটিক্স'), icon: 'history' },
          ]}
        />
      ) : null}
    </div>
  );
}

export function LabTabs({ active }: { active: LabTab }) {
  const nav = useNavigate();
  const s = useSession();
  const approved = (s.work.lab as { status?: string } | null)?.status === 'approved';
  const fresh = useNewCount('lab_orders', approved);
  if (!approved) return null;
  const top = active === 'orders' ? 'orders' : 'lab';
  return (
    <div className="col gap-8 work-tabs">
      <PillTabs
        label={myBusinessTitle('lab')}
        value={top}
        onChange={(k) => nav(k === 'orders' ? '/work/lab/orders' : '/work/lab/info')}
        tabs={[
          { key: 'orders', label: tr('Requests', 'অনুরোধ'), icon: 'list_alt', count: fresh },
          { key: 'lab', label: tr('Lab', 'ল্যাব'), icon: 'local_hospital' },
        ]}
      />
      {top === 'lab' ? (
        <PillTabs
          secondary
          label={tr('Lab', 'ল্যাব')}
          value={active}
          onChange={(k) => nav(k === 'info' ? '/work/lab/info' : `/work/lab/orders/${k}`)}
          tabs={[
            { key: 'info', label: tr('Lab details', 'ল্যাবের তথ্য'), icon: 'local_hospital' },
            { key: 'prices', label: tr('Tests & prices', 'পরীক্ষা ও দাম'), icon: 'payments' },
            { key: 'ranges', label: tr('Normal ranges', 'স্বাভাবিক মাত্রা'), icon: 'tune' },
          ]}
        />
      ) : null}
    </div>
  );
}

/** একদম নিচে, আলাদা করে: মুছে ফেলা */
export function DangerZone({ children }: { children: ReactNode }) {
  return (
    <section className="work-danger">
      <hr className="divider" />
      <div className="row wrap gap-8">{children}</div>
    </section>
  );
}
