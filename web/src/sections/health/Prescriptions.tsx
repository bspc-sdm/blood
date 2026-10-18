// রোগী: আমার প্রেসক্রিপশন — অ্যাপের MyPrescriptionsScreen, RxListCard, RxDetailDialog, RxPaper, RxClaimDialog;
// প্রিন্ট অ্যাপের RxPdf.kt-এর কাগজের মতো (শুধু কাগজটাই ছাপা হয়)।
import { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Dialog, DirectoryCard, EmptyState, ErrorBox, ErrorText, InfoText, Loading, SkeletonCards, Tag, TextInput } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { DetailPane, ListDetail } from '@/shell/templates';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { num, tr, useLang } from '@/lib/i18n';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { HealthTabs, useFlash } from './common';
import { qrMatrix } from './qr';
import { RxActivitySection } from './RxActivity';

export interface RxMed { name: string; form?: string; strength?: string; dose?: string; timing?: string; days?: number; note?: string }
export interface Rx {
  id: string; code: string; status: string; createdAt: number; doctorUid: string;
  doctor: { name?: string; degrees?: string; regNo?: string; specialties?: string[]; chamber?: { name?: string; address?: string; phone?: string } | null };
  patient: { name?: string; age?: number | null; gender?: string };
  complaints: string; findings: Record<string, string>; diagnosis: string; medicines: RxMed[]; tests: string[]; testPlace: string; testPlaceId?: string | null;
  advice: string; followUpDate: number | null; followUpBookDate: number | null; followUpNote: string; chamberIndex: number | null;
}

// ---------- লেখা (অ্যাপের RxText) ----------
const FINDINGS = ['bp', 'pulse', 'temp', 'weight', 'spo2', 'other'];
function findingLabel(k: string) {
  switch (k) {
    case 'bp': return tr('BP', 'প্রেসার');
    case 'pulse': return tr('Pulse', 'পালস');
    case 'temp': return tr('Temp', 'তাপমাত্রা');
    case 'weight': return tr('Weight', 'ওজন');
    case 'spo2': return 'SpO₂';
    default: return tr('Other', 'অন্যান্য');
  }
}
const timingLabel = (t?: string) => (t === 'before' ? tr('Before food', 'খাবারের আগে') : t === 'after' ? tr('After food', 'খাবারের পরে') : '');
export const genderLabel = (g?: string) => (g === 'male' ? tr('Male', 'পুরুষ') : g === 'female' ? tr('Female', 'মহিলা') : '');
function formShort(f?: string) {
  const l = (f ?? '').toLowerCase();
  if (!l.trim()) return '';
  if (l.includes('tablet')) return 'Tab.';
  if (l.includes('capsule')) return 'Cap.';
  if (l.includes('syrup') || l.includes('suspension') || l.includes('oral solution')) return 'Syr.';
  if (l.includes('injection') || l.includes('infusion')) return 'Inj.';
  if (l.includes('drop')) return 'Drop';
  if (l.includes('cream')) return 'Cream';
  if (l.includes('ointment')) return 'Oint.';
  if (l.includes('inhal')) return 'Inh.';
  return f ?? '';
}
export const medTitle = (m: RxMed) => [formShort(m.form), m.name].filter((x) => x && x.trim()).join(' ') + (m.strength ? ` (${m.strength})` : '');
export const medHowTo = (m: RxMed) =>
  [num(m.dose ?? ''), timingLabel(m.timing), m.days && m.days > 0 ? tr(`${m.days} days`, `${num(m.days)} দিন`) : tr('Continue', 'চলবে')].filter((x) => x.trim()).join(' · ');
export const patientLine = (rx: Rx) =>
  [rx.patient?.name, rx.patient?.age != null ? tr(`${rx.patient.age} yrs`, `${num(rx.patient.age)} বছর`) : null, genderLabel(rx.patient?.gender) || null].filter(Boolean).join(' · ');

const SPECIALTIES: Record<string, [string, string]> = {
  medicine: ['Medicine', 'মেডিসিন'], pediatrics: ['Child (Pediatrics)', 'শিশু রোগ'], gynecology: ['Gynae & Obstetrics', 'গাইনি ও প্রসূতি'],
  cardiology: ['Heart (Cardiology)', 'হৃদরোগ'], orthopedics: ['Bone (Orthopedics)', 'হাড় ও জোড়া'], ent: ['ENT', 'নাক-কান-গলা'], eye: ['Eye', 'চক্ষু'],
  dermatology: ['Skin (Dermatology)', 'চর্ম ও যৌন'], neurology: ['Neurology', 'স্নায়ুরোগ'], psychiatry: ['Psychiatry', 'মানসিক রোগ'], surgery: ['Surgery', 'সার্জারি'],
  nephrology: ['Kidney (Nephrology)', 'কিডনি রোগ'], urology: ['Urology', 'মূত্ররোগ'], gastro: ['Gastroenterology', 'পরিপাকতন্ত্র ও লিভার'], oncology: ['Cancer (Oncology)', 'ক্যান্সার'],
  dental: ['Dental', 'দন্ত'], endocrinology: ['Diabetes & Hormone', 'ডায়াবেটিস ও হরমোন'], chest: ['Chest & Lung', 'বক্ষব্যাধি'], physical_medicine: ['Physical medicine', 'ফিজিক্যাল মেডিসিন'],
  general: ['General practitioner', 'সাধারণ চিকিৎসক'],
};
const specialtyLabel = (c: string) => (SPECIALTIES[c] ? tr(SPECIALTIES[c][0], SPECIALTIES[c][1]) : c);

// ---------- ডেটা ----------
function useRxList() {
  const lang = useLang();
  const { uid, guest } = useSession();
  const care = useCareMode();
  return useQuery({
    queryKey: ['rx_mine', care?.uid ?? null, lang, uid],
    queryFn: () => call<{ items?: Rx[] }>('rx_mine').then((r) => r.items ?? []),
    enabled: !guest,
  });
}

export function PrescriptionsPage({ id }: { id: string | null }) {
  usePageTitle(tr('My prescriptions', 'আমার প্রেসক্রিপশন'));
  const nav = useNavigate();
  const s = useSession();
  const [params, setParams] = useSearchParams();
  const who = params.get('who');
  const q = useRxList();
  const [claiming, setClaiming] = useState(false);
  const [info, setInfo] = useFlash();
  const names = useMemo(() => [...new Set((q.data ?? []).map((r) => (r.patient?.name ?? '').trim()).filter(Boolean))], [q.data]);
  const shown = (q.data ?? []).filter((r) => !who || (r.patient?.name ?? '').trim() === who);
  const open = (rid: string) => nav({ pathname: `/prescriptions/${rid}`, search: params.toString() ? `?${params}` : '' });

  const list = (
    <div className="list-inner">
      <HealthTabs value="rx" />
      {s.guest ? (
        <EmptyState icon="medication" title={tr('My prescriptions', 'আমার প্রেসক্রিপশন')} text={tr('Prescriptions from doctors on Lifolo appear here. Got a paper one? Add it with its code.', 'Lifolo-তে ডাক্তারের দেওয়া প্রেসক্রিপশন এখানে আসে। কাগজেরটা কোড দিয়ে যোগ করুন।')}>
          <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
        </EmptyState>
      ) : (
        <>
          <div className="row">
            <h2 className="t-headline grow">{tr('My prescriptions', 'আমার প্রেসক্রিপশন')}</h2>
            {q.data?.length ? <Button variant="tonal" small icon="add" onClick={() => setClaiming(true)}>{tr('Add with code', 'কোড দিয়ে যোগ করুন')}</Button> : null}
          </div>
          <InfoText>{info}</InfoText>
          {names.length > 1 ? (
            <div className="chips scroll" role="group" aria-label={tr('Patient', 'রোগী')}>
              {['', ...names].map((n) => (
                <button
                  key={n || 'all'}
                  type="button"
                  className="chip"
                  aria-pressed={(who ?? '') === n}
                  onClick={() => {
                    const p = new URLSearchParams(params);
                    if (n) p.set('who', n);
                    else p.delete('who');
                    setParams(p, { replace: true });
                  }}
                >
                  {(who ?? '') === n ? <Icon name="check_circle" /> : null}
                  {n || tr('Everyone', 'সবাই')}
                </button>
              ))}
            </div>
          ) : null}
          {q.isPending ? (
            <SkeletonCards n={3} h={140} />
          ) : q.isError ? (
            <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
          ) : !shown.length ? (
            <EmptyState icon="medication" title={tr('No prescriptions yet', 'এখনো কোনো প্রেসক্রিপশন নেই')} text={tr('Prescriptions from doctors on Lifolo appear here. Got a paper one? Add it with its code.', 'Lifolo-তে ডাক্তারের দেওয়া প্রেসক্রিপশন এখানে আসে। কাগজেরটা কোড দিয়ে যোগ করুন।')}>
              <Button variant="outline" icon="add" onClick={() => setClaiming(true)}>{tr('Add with code', 'কোড দিয়ে যোগ করুন')}</Button>
            </EmptyState>
          ) : (
            shown.map((rx) => <RxListCard key={rx.id} rx={rx} selected={rx.id === id} onClick={() => open(rx.id)} />)
          )}
        </>
      )}
      {claiming ? (
        <RxClaimDialog
          onClose={() => setClaiming(false)}
          onAdded={(n) => {
            setClaiming(false);
            setInfo(tr(`Added ${n} prescription(s)`, `${num(n)}টা প্রেসক্রিপশন যোগ হয়েছে`));
            q.refetch();
          }}
        />
      ) : null}
    </div>
  );

  const fromList = id ? q.data?.find((r) => r.id === id) ?? null : null;
  return (
    <ListDetail
      list={list}
      detail={id && !s.guest ? <RxDetail key={id} id={id} initial={fromList} listLoading={q.isPending} back={() => nav({ pathname: '/prescriptions', search: params.toString() ? `?${params}` : '' })} /> : null}
      placeholder={s.guest ? null : <EmptyState icon="medication" title={tr('Pick a prescription', 'একটা প্রেসক্রিপশন বেছে নিন')} />}
    />
  );
}

function RxListCard({ rx, selected, onClick }: { rx: Rx; selected: boolean; onClick: () => void }) {
  const meds = rx.medicines ?? [];
  return (
    <DirectoryCard
      title={rx.doctor?.name || '-'}
      icon="medication"
      subtitle={rx.doctor?.degrees || null}
      status={rx.status === 'cancelled' ? { text: tr('Cancelled', 'বাতিল'), good: false } : null}
      meta={[formatDate(rx.createdAt), patientLine(rx)].filter(Boolean).join(' · ')}
      details={[
        ['medication', meds.slice(0, 3).map((m) => m.name).join(', ') + (meds.length > 3 ? ' +' + num(meds.length - 3) : '')],
        ['list_alt', rx.tests?.length ? tr(`${rx.tests.length} test(s)`, `${num(rx.tests.length)}টা পরীক্ষা`) : ''],
        ['calendar_month', rx.followUpDate ? tr('Come again: ', 'আবার দেখাবেন: ') + formatDate(rx.followUpDate) : ''],
      ]}
      selected={selected}
      onClick={onClick}
    />
  );
}

// ---------- একটা প্রেসক্রিপশন ----------
const PRINT_PATH = 'M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z';
function PrintButton() {
  const label = tr('Print', 'প্রিন্ট করুন');
  return (
    <button type="button" className="icon-btn" aria-label={label} title={label} onClick={() => window.print()}>
      <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d={PRINT_PATH} /></svg>
    </button>
  );
}

function RxDetail({ id, initial, listLoading, back }: { id: string; initial: Rx | null; listLoading: boolean; back: () => void }) {
  const lang = useLang();
  const care = useCareMode();
  const { uid } = useSession();
  // তালিকায় না থাকলে (সরাসরি লিংক) সার্ভার থেকে
  const r = useQuery({
    queryKey: ['rx_get', id, care?.uid ?? null, lang, uid],
    queryFn: () => call<{ item: Rx }>('rx_get', { id }).then((x) => x.item),
    enabled: !initial && !listLoading,
  });
  const rx = initial ?? r.data ?? null;
  return (
    <DetailPane title={tr('Prescription', 'প্রেসক্রিপশন')} onBack={back} actions={rx ? <PrintButton /> : null}>
      {rx ? (
        <>
          {/* কাগজ RxActivitySection-এর ভেতরে: উপরে পাঠানোর বাটন, তারপর কাগজ */}
          <RxActivitySection rx={rx} paper={<RxPaper rx={rx} />} />
          <FollowUpCard rx={rx} />
          {createPortal(
            <div className="health-print" aria-hidden="true">
              <RxPaper rx={rx} print />
            </div>,
            document.body,
          )}
        </>
      ) : listLoading || r.isPending ? (
        <Loading />
      ) : (
        <ErrorBox error={errorText(r.error)} onRetry={() => r.refetch()} />
      )}
    </DetailPane>
  );
}

/** আবার দেখানো: এক চাপে সেই চেম্বারে সিরিয়াল */
function FollowUpCard({ rx }: { rx: Rx }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const qc = useQueryClient();
  const book = rx.followUpBookDate;
  if (rx.status === 'cancelled' || book == null || rx.chamberIndex == null || book < todayUtcMidnight()) return null;
  return (
    <div className="card tonal col">
      <span className="t-title-m">{tr('See the doctor again', 'আবার ডাক্তার দেখানো')}</span>
      <span className="t-body-m">{formatDate(book) + (rx.doctor?.chamber?.name ? ' · ' + rx.doctor.chamber.name : '')}</span>
      <ErrorText>{err}</ErrorText>
      <InfoText>{info}</InfoText>
      <div>
        <Button
          square
          icon="calendar_month"
          busy={busy}
          disabled={!!info}
          onClick={async () => {
            setBusy(true);
            setErr(null);
            try {
              await call('book', { providerUid: rx.doctorUid, chamberIndex: rx.chamberIndex, date: book, note: rx.followUpNote || tr('Follow-up', 'ফলো-আপ') });
              setInfo(tr("Request sent. You'll get a notification when the doctor confirms.", 'অনুরোধ গেছে। ডাক্তার নিশ্চিত করলে নোটিফিকেশন পাবেন।'));
              qc.invalidateQueries({ queryKey: ['my_appointments'] });
            } catch (e) {
              setErr(errorText(e));
            }
            setBusy(false);
          }}
        >
          {tr('Get a serial', 'সিরিয়াল নিন')}
        </Button>
      </div>
    </div>
  );
}

function Qr({ text, size }: { text: string; size: number }) {
  const m = useMemo(() => qrMatrix(text), [text]);
  if (!m) return null;
  const n = m.length + 2;
  let d = '';
  m.forEach((row, y) => row.forEach((on, x) => {
    if (on) d += `M${x + 1} ${y + 1}h1v1h-1z`;
  }));
  return (
    <svg className="health-qr" viewBox={`0 0 ${n} ${n}`} width={size} height={size} role="img" aria-label={tr('Verification QR', 'যাচাইয়ের QR')} shapeRendering="crispEdges">
      <rect width={n} height={n} className="bg" />
      <path d={d} className="fg" />
    </svg>
  );
}

function PaperBlock({ title, children }: { title: string; children: string }) {
  return (
    <div className="col gap-2">
      <span className="t-label-l health-paper-h">{title}</span>
      <p className="t-body-m pre">{children}</p>
    </div>
  );
}

/** প্রেসক্রিপশনের কাগজ (অ্যাপের RxPaper; print = ছাপার কপি) */
export function RxPaper({ rx, print }: { rx: Rx; print?: boolean }) {
  const d = rx.doctor ?? {};
  const ch = d.chamber ?? null;
  const findings = FINDINGS.filter((k) => rx.findings?.[k]).map((k) => findingLabel(k) + ': ' + rx.findings[k]);
  const verifyUrl = `${window.location.origin}/rx/${rx.code}`;
  return (
    <article className={print ? 'health-paper print' : 'health-paper'} aria-label={tr('Prescription', 'প্রেসক্রিপশন')}>
      <div className="row top">
        <div className="col gap-2 grow">
          <h3 className="t-title-l">{d.name}</h3>
          {d.degrees ? <span className="t-body-m">{d.degrees}</span> : null}
          {d.specialties?.length ? <span className="t-body-m health-paper-h">{d.specialties.map(specialtyLabel).join(', ')}</span> : null}
          {d.regNo ? <span className="t-body-s muted">BMDC {d.regNo}</span> : null}
          {ch && (ch.name || ch.address) ? <span className="t-body-s muted">{[ch.name, ch.address, print ? ch.phone : ''].filter(Boolean).join(', ')}</span> : null}
        </div>
        <Qr text={verifyUrl} size={print ? 96 : 84} />
      </div>
      {rx.status === 'cancelled' ? <Tag tone="error">{tr('Cancelled', 'বাতিল')}</Tag> : null}
      <hr className="divider" />
      <div className="row wrap">
        <span className="t-title-s grow">{patientLine(rx)}</span>
        <span className="t-body-s muted">{(print ? tr('Date: ', 'তারিখ: ') : '') + formatDate(rx.createdAt)}</span>
      </div>
      {print ? <hr className="divider" /> : null}
      {rx.complaints ? <PaperBlock title={tr('Complaints', 'সমস্যা')}>{rx.complaints}</PaperBlock> : null}
      {findings.length ? <PaperBlock title={tr('Findings', 'পরীক্ষায় পাওয়া')}>{findings.join(' · ')}</PaperBlock> : null}
      {rx.diagnosis ? <PaperBlock title={tr('Diagnosis', 'রোগনির্ণয়')}>{rx.diagnosis}</PaperBlock> : null}
      {rx.medicines?.length ? (
        <div className="col">
          <span className="health-rx-sign" aria-hidden="true">℞</span>
          <ol className="col health-meds">
            {rx.medicines.map((m, i) => (
              <li key={i} className="row top">
                <span className="t-body-l health-med-n">{num(i + 1)}.</span>
                <div className="col gap-2 grow">
                  <span className="t-body-l semi">{medTitle(m)}</span>
                  <span className="t-body-m muted">{medHowTo(m)}</span>
                  {m.note ? <span className="t-body-s muted pre">{m.note}</span> : null}
                </div>
              </li>
            ))}
          </ol>
        </div>
      ) : null}
      {rx.tests?.length ? (
        <>
          <PaperBlock title={tr('Tests', 'পরীক্ষা')}>{rx.tests.map((t) => '• ' + t).join('\n')}</PaperBlock>
          {rx.testPlace ? <span className="t-body-s muted">{tr('Suggested place: ', 'করাতে পারেন: ') + rx.testPlace + tr(' (or anywhere you like)', ' (চাইলে অন্য জায়গাতেও)')}</span> : null}
        </>
      ) : null}
      {rx.advice ? <PaperBlock title={tr('Advice', 'পরামর্শ')}>{rx.advice}</PaperBlock> : null}
      {rx.followUpDate ? <PaperBlock title={tr('Come again', 'আবার দেখাবেন')}>{formatDate(rx.followUpDate) + (rx.followUpNote ? ' · ' + rx.followUpNote : '')}</PaperBlock> : null}
      <span className="t-label-s muted">{tr('Verify: ', 'যাচাই: ') + rx.code}</span>
      {print ? <span className="t-label-s muted health-paper-foot">{tr('Issued on Lifolo. Scan the QR to check it is genuine.', 'Lifolo-তে দেওয়া প্রেসক্রিপশন। আসল কি না দেখতে QR স্ক্যান করুন।')}</span> : null}
    </article>
  );
}

/** কাগজের প্রেসক্রিপশনের কোড + চেম্বারে দেওয়া ফোন নম্বর (অ্যাপের RxClaimDialog) */
function RxClaimDialog({ onClose, onAdded }: { onClose: () => void; onAdded: (n: number) => void }) {
  const s = useSession();
  const [code, setCode] = useState('');
  const [phone, setPhone] = useState(s.profile?.phone ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={tr('Add a prescription', 'প্রেসক্রিপশন যোগ করুন')}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            disabled={code.length !== 10 || !phone.trim()}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                const r = await call<{ added?: number }>('rx_claim', { code, phone: phone.trim() });
                onAdded(r.added ?? 0);
              } catch (e) {
                setErr(errorText(e));
                setBusy(false);
              }
            }}
          >
            {tr('Add', 'যোগ করুন')}
          </Button>
        </>
      }
    >
      <p className="t-body-m">
        {tr(
          "Write the code printed under the QR, and the mobile number given at the chamber — yours, or a family member's (add them in Profile → Caregivers & family).",
          'QR-এর নিচে লেখা কোড আর চেম্বারে যে মোবাইল নম্বর দেওয়া হয়েছিল — আপনার, অথবা পরিবারের সদস্যের (প্রোফাইল → কেয়ারগিভার ও পরিবার-এ যোগ করে রাখুন)।',
        )}
      </p>
      <TextInput
        label={tr('Code', 'কোড')}
        value={code}
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10))}
        className="input health-code"
      />
      <TextInput label={tr('Mobile number', 'মোবাইল নম্বর')} type="tel" inputMode="tel" value={phone} maxLength={20} onChange={(e) => setPhone(e.target.value)} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}
