// প্রেসক্রিপশন লেখা (শুধু ডাক্তার) — অ্যাপের RxEditorDialog + MedEditDialog, প্যানেলের writeView।
// কম্পিউটারে: ওষুধ এক সারিতে (নাম খুঁজে বাছা → নিয়ম → দিন → নোট; Enter-এ পরের ঘর, নোটে Enter-এ নতুন ওষুধ),
// Ctrl+Enter-এ দেওয়া; দেওয়ার আগে কাগজটা দেখে নিশ্চিত করা (পরে আর বদলানো যায় না)।
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, ChoiceChips, Dialog, ErrorText, IconButton, Select, SkeletonCards, TextArea, TextInput, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import { medHowTo, medTitle } from '../../health/Prescriptions';
import {
  COMMON_TESTS, DAYS, DOSES, FINDINGS, FOLLOW, MAX_MEDS, SELF, ageText, findTarget, findingLabel, genderLabel, targetBody, targetKey, targetSearch, timingLabel,
  useRxInvalidate, type Actor, type Drug, type LabTest, type RxMed, type RxTarget,
} from './model';
import { PatientsList, usePatients } from './Patients';

type Link = (path: string, extra?: Record<string, string>) => string;

export function WritePage({ actor, chambers, degrees, link }: { actor: Actor; chambers: string[]; degrees: string; link: Link }) {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const pq = usePatients(actor);
  const target = findTarget(sp, pq.data);
  const write = (t: RxTarget) => nav(link('/write', targetSearch(t)));

  if (!target) {
    return (
      <div className="col gap-12">
        <h2 className="t-title-l">{tr('Who is it for?', 'কার জন্য লিখবেন?')}</h2>
        <PatientsList actor={actor} query={pq} pick onWrite={write} onAdded={(p) => write({ rxPatientId: p.id, name: p.name })} />
      </div>
    );
  }
  // নাম-বয়স তালিকা থেকে (না পেলে সার্ভার নিজের কাছের তথ্য বসায়)
  if (pq.isPending) return <SkeletonCards n={3} h={140} />;
  return <RxEditor key={targetKey(target)} target={target} chambers={chambers} doctorName={actor.doctorName} degrees={degrees} link={link} onOtherPatient={() => nav(link('/write'))} />;
}

// ---------------- ফর্ম ----------------
interface MedRow { key: number; name: string; form: string; strength: string; dose: string; timing: string; days: string; note: string; picked: boolean }
let medKey = 1;
const newMed = (): MedRow => ({ key: medKey++, name: '', form: '', strength: '', dose: '1+0+1', timing: 'after', days: num(5), note: '', picked: false });
const DAY_MS = 86400000;

function RxEditor({ target, chambers, doctorName, degrees, link, onOtherPatient }: { target: RxTarget; chambers: string[]; doctorName: string; degrees: string; link: Link; onOtherPatient: () => void }) {
  const nav = useNavigate();
  const invalidate = useRxInvalidate();
  const [name, setName] = useState(target.name);
  const [age, setAge] = useState(target.age != null ? num(target.age) : '');
  const [gender, setGender] = useState(target.gender ?? '');
  const [complaints, setComplaints] = useState('');
  const [findings, setFindings] = useState<Record<string, string>>({});
  const [diagnosis, setDiagnosis] = useState('');
  const [meds, setMeds] = useState<MedRow[]>(() => [newMed()]);
  const [tests, setTests] = useState<string[]>([]);
  const [testInput, setTestInput] = useState('');
  const [testPlace, setTestPlace] = useState('');
  const [advice, setAdvice] = useState('');
  const [followDays, setFollowDays] = useState(0);
  const [followNote, setFollowNote] = useState('');
  const [chamber, setChamber] = useState(0);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const focusNext = useRef<number | null>(null);
  const listId = useId();
  const labtests = useCall<{ tests: LabTest[] }>('labtests_get', SELF, { staleTime: 30 * 60_000 });
  const testNames = useMemo(() => {
    const out = new Set<string>();
    for (const t of labtests.data?.tests ?? []) {
      if (t.hidden) continue;
      out.add(t.name);
    }
    return [...out];
  }, [labtests.data]);

  // নতুন ওষুধের সারি এলে তার নামের ঘরে
  useEffect(() => {
    if (focusNext.current == null) return;
    document.querySelector<HTMLInputElement>(`[data-med="${focusNext.current}"] [data-f="name"]`)?.focus();
    focusNext.current = null;
  }, [meds]);

  const filled = meds.filter((m) => m.name.trim());
  const canGive = !!name.trim() && (filled.length > 0 || tests.length > 0 || !!advice.trim());
  const setMed = (key: number, patch: Partial<MedRow>) => setMeds((l) => l.map((m) => (m.key === key ? { ...m, ...patch } : m)));
  const addMed = () => {
    if (meds.length >= MAX_MEDS) return;
    const m = newMed();
    focusNext.current = m.key;
    setMeds((l) => [...l, m]);
  };
  const removeMed = (key: number) => setMeds((l) => (l.length > 1 ? l.filter((m) => m.key !== key) : [newMed()]));
  const addTest = (v: string) => {
    const t = v.trim().slice(0, 80);
    if (t && !tests.includes(t)) setTests([...tests, t]);
    setTestInput('');
  };
  const toMed = (m: MedRow): RxMed => ({
    name: m.name.trim(), form: m.form.trim(), strength: m.strength.trim(), dose: toAsciiDigits(m.dose).replace(/\s+/g, ''), timing: m.timing,
    days: Math.min(365, Math.max(0, parseInt(toAsciiDigits(m.days), 10) || 0)), note: m.note.trim(),
  });
  const ask = () => {
    setErr(null);
    if (!canGive) {
      setErr(!name.trim() ? tr("Write the patient's name", 'রোগীর নাম লিখুন') : tr('Add a medicine, a test or advice', 'অন্তত একটা ওষুধ, পরীক্ষা বা পরামর্শ দিন'));
      return;
    }
    setConfirm(true);
  };

  async function issue() {
    setBusy(true);
    setErr(null);
    try {
      const a = toAsciiDigits(age.trim());
      const r = await call<{ id: string; code: string }>('rx_create', {
        ...targetBody(target),
        patient: { name: name.trim(), age: a ? Number(a) : null, gender },
        complaints: complaints.trim(),
        findings: Object.fromEntries(FINDINGS.map((k) => [k, (findings[k] ?? '').trim()])),
        diagnosis: diagnosis.trim(),
        medicines: filled.map(toMed),
        tests: [...tests, ...(testInput.trim() && !tests.includes(testInput.trim()) ? [testInput.trim()] : [])],
        testPlace: testPlace.trim(),
        advice: advice.trim(),
        followUpDays: followDays,
        followUpNote: followDays ? followNote.trim() : '',
        ...(chambers.length ? { chamberIndex: Math.min(chamber, chambers.length - 1) } : {}),
      });
      invalidate();
      toast(tr('Prescription given', 'প্রেসক্রিপশন দেওয়া হয়েছে'));
      nav(link('/view/' + r.id));
    } catch (e) {
      setErr(errorText(e));
      setConfirm(false);
    }
    setBusy(false);
  }

  const onFormKey = (e: KeyboardEvent<HTMLFormElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      ask();
    }
  };

  return (
    <form className="rxd-editor" onSubmit={(e) => { e.preventDefault(); ask(); }} onKeyDown={onFormKey} noValidate aria-label={tr('Write a prescription', 'প্রেসক্রিপশন লিখুন')}>
      <div className="rxd-grid">
        {/* রোগী */}
        <section className="card col gap-12">
          <div className="row">
            <h2 className="rxd-sec grow">{tr('Patient', 'রোগী')}</h2>
            <Button variant="text" small onClick={onOtherPatient}>{tr('Other patient', 'অন্য রোগী')}</Button>
          </div>
          <TextInput label={tr('Name', 'নাম')} value={name} onChange={(e) => setName(e.target.value.slice(0, 80))} autoComplete="off" />
          <div className="row top wrap gap-12">
            <div className="rxd-age">
              <TextInput label={tr('Age', 'বয়স')} inputMode="numeric" value={age} onChange={(e) => setAge(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 3))} />
            </div>
            <div className="field">
              <span className="label">{tr('Gender', 'লিঙ্গ')}</span>
              <ChoiceChips options={[{ value: 'male', label: genderLabel('male') }, { value: 'female', label: genderLabel('female') }]} value={gender} onChange={(v: string) => setGender(gender === v ? '' : v)} />
            </div>
          </div>
          {chambers.length > 1 ? (
            <Select label={tr('Chamber', 'চেম্বার')} value={String(chamber)} onChange={(e) => setChamber(Number(e.target.value))} options={chambers.map((c, i) => ({ value: String(i), label: c || '-' }))} />
          ) : chambers.length === 1 && chambers[0] ? (
            <span className="t-body-s muted row gap-6"><Icon name="local_hospital" className="rxd-ic16" />{chambers[0]}</span>
          ) : null}
        </section>

        {/* সমস্যা ও পরীক্ষায় পাওয়া */}
        <section className="card col gap-12">
          <h2 className="rxd-sec">{tr('Complaints & findings', 'সমস্যা ও পরীক্ষায় পাওয়া')}</h2>
          <TextArea label={tr('Complaints', 'সমস্যা')} rows={2} className="textarea rxd-ta" value={complaints} onChange={(e) => setComplaints(e.target.value.slice(0, 1000))} />
          <div className="rxd-findings">
            {FINDINGS.map((k) => (
              <TextInput key={k} label={findingLabel(k)} value={findings[k] ?? ''} onChange={(e) => setFindings({ ...findings, [k]: e.target.value.slice(0, 20) })} autoComplete="off" />
            ))}
          </div>
          <TextInput label={tr('Diagnosis', 'রোগনির্ণয়')} value={diagnosis} onChange={(e) => setDiagnosis(e.target.value.slice(0, 500))} autoComplete="off" />
        </section>

        {/* ওষুধ */}
        <section className="card col gap-12 rxd-span">
          <div className="row">
            <h2 className="rxd-sec grow"><span className="rxd-rx" aria-hidden="true">℞</span> {tr('Medicines', 'ওষুধ')}</h2>
            <span className="t-body-s muted rxd-keys">{tr('Enter: next box · Enter on note: new medicine', 'Enter: পরের ঘর · নোটে Enter: নতুন ওষুধ')}</span>
          </div>
          <div className="rxd-meds" role="group" aria-label={tr('Medicines', 'ওষুধ')}>
            <div className="rxd-med rxd-med-head" aria-hidden="true">
              <span />
              <span>{tr('Medicine name', 'ওষুধের নাম')}</span>
              <span>{tr('Form', 'ধরন')}</span>
              <span>{tr('Strength', 'মাত্রা')}</span>
              <span>{tr('How to take', 'খাওয়ার নিয়ম')}</span>
              <span>{tr('Food', 'খাবার')}</span>
              <span>{tr('Days', 'দিন')}</span>
              <span>{tr('Note', 'নোট')}</span>
              <span />
            </div>
            {meds.map((m, i) => (
              <MedRowView
                key={m.key}
                m={m}
                n={i + 1}
                last={i === meds.length - 1}
                onChange={(p) => setMed(m.key, p)}
                onRemove={() => removeMed(m.key)}
                onAddNext={addMed}
              />
            ))}
          </div>
          <div>
            <Button variant="tonal" icon="add" onClick={addMed} disabled={meds.length >= MAX_MEDS}>{tr('Add medicine', 'ওষুধ যোগ করুন')}</Button>
          </div>
        </section>

        {/* পরীক্ষা */}
        <section className="card col gap-12">
          <h2 className="rxd-sec">{tr('Tests', 'পরীক্ষা')}</h2>
          <ChoiceChips multi options={[...COMMON_TESTS, ...tests.filter((t) => !COMMON_TESTS.includes(t))].map((t) => ({ value: t, label: t }))} value={tests} onChange={(v: string[]) => setTests(v)} />
          <div className="row top gap-8">
            <div className="grow">
              <TextInput
                label={tr('Other test', 'অন্য পরীক্ষা')}
                list={listId + 'tests'}
                value={testInput}
                onChange={(e) => setTestInput(e.target.value.slice(0, 80))}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
                    e.preventDefault();
                    addTest(testInput);
                  }
                }}
                autoComplete="off"
              />
            </div>
            <Button variant="outline" className="rxd-field-btn" disabled={!testInput.trim()} onClick={() => addTest(testInput)}>{tr('Add', 'যোগ')}</Button>
          </div>
          <datalist id={listId + 'tests'}>{testNames.map((t) => <option key={t} value={t} />)}</datalist>
          {tests.length ? (
            <TextInput
              label={tr('Suggest where to test (optional)', 'কোথায় করাবেন (ঐচ্ছিক)')}
              hint={tr('The patient can test anywhere they like', 'রোগী চাইলে অন্য জায়গাতেও করাতে পারবেন')}
              placeholder={tr('e.g. Popular Diagnostic, Mymensingh', 'যেমন: পপুলার ডায়াগনস্টিক, ময়মনসিংহ')}
              value={testPlace}
              onChange={(e) => setTestPlace(e.target.value.slice(0, 200))}
              autoComplete="off"
            />
          ) : null}
        </section>

        {/* পরামর্শ ও আবার দেখানো */}
        <section className="card col gap-12">
          <h2 className="rxd-sec">{tr('Advice', 'পরামর্শ')}</h2>
          <TextArea label={tr('Advice', 'পরামর্শ')} rows={3} className="textarea rxd-ta" value={advice} onChange={(e) => setAdvice(e.target.value.slice(0, 1000))} />
          <div className="field">
            <span className="label">{tr('Come again', 'আবার দেখাবেন')}</span>
            <ChoiceChips
              options={[0, ...FOLLOW].map((d) => ({ value: d, label: d ? tr(`${d} days`, `${num(d)} দিন পর`) : tr('No', 'দরকার নেই') }))}
              value={followDays}
              onChange={(v: number) => setFollowDays(v)}
            />
          </div>
          {followDays > 0 ? (
            <TextInput
              label={tr('Note (e.g. bring the reports)', 'নোট (যেমন: রিপোর্ট নিয়ে আসবেন)')}
              hint={formatDate(todayUtcMidnight() + followDays * DAY_MS)}
              value={followNote}
              onChange={(e) => setFollowNote(e.target.value.slice(0, 80))}
              autoComplete="off"
            />
          ) : null}
        </section>
      </div>

      {/* নিচে আটকে থাকা বার */}
      <div className="rxd-bar">
        <div className="col gap-2 grow">
          <span className="t-title-s ellipsis">{[name.trim() || tr('Patient', 'রোগী'), ageText(age ? Number(toAsciiDigits(age)) : null), genderLabel(gender)].filter(Boolean).join(' · ')}</span>
          <span className="t-body-s muted">
            {[tr(`${filled.length} medicine(s)`, `${num(filled.length)}টা ওষুধ`), tr(`${tests.length} test(s)`, `${num(tests.length)}টা পরীক্ষা`)].join(' · ')}
            <span className="rxd-keys"> · Ctrl+Enter</span>
          </span>
          <ErrorText>{err}</ErrorText>
        </div>
        <Button icon="check_circle" busy={busy} onClick={ask}>{tr('Give prescription', 'প্রেসক্রিপশন দিন')}</Button>
      </div>

      {confirm ? (
        <Dialog
          onClose={() => !busy && setConfirm(false)}
          size="wide"
          title={tr('Give this prescription?', 'প্রেসক্রিপশনটা দেবেন?')}
          actions={
            <>
              <Button variant="text" onClick={() => setConfirm(false)} disabled={busy}>{tr('Check again', 'আবার দেখি')}</Button>
              <Button icon="check_circle" busy={busy} onClick={issue}>{tr('Give', 'দিন')}</Button>
            </>
          }
        >
          <p className="t-body-m muted">{tr("It can't be edited afterwards; you can only cancel it and write a new one.", 'দেওয়ার পর আর বদলানো যাবে না; শুধু বাতিল করে নতুন লেখা যাবে।')}</p>
          <DraftPaper
            doctorName={doctorName}
            degrees={degrees}
            chamber={chambers.length ? chambers[Math.min(chamber, chambers.length - 1)] : ''}
            patient={[name.trim(), ageText(age ? Number(toAsciiDigits(age)) : null), genderLabel(gender)].filter(Boolean).join(' · ')}
            complaints={complaints.trim()}
            findings={FINDINGS.filter((k) => findings[k]?.trim()).map((k) => findingLabel(k) + ': ' + findings[k].trim()).join(' · ')}
            diagnosis={diagnosis.trim()}
            meds={filled.map(toMed)}
            tests={tests}
            testPlace={testPlace.trim()}
            advice={advice.trim()}
            follow={followDays ? formatDate(todayUtcMidnight() + followDays * DAY_MS) + (followNote.trim() ? ' · ' + followNote.trim() : '') : ''}
          />
          <ErrorText>{err}</ErrorText>
        </Dialog>
      ) : null}
    </form>
  );
}

// ---------------- এক ওষুধ ----------------
function MedRowView({ m, n, last, onChange, onRemove, onAddNext }: { m: MedRow; n: number; last: boolean; onChange: (p: Partial<MedRow>) => void; onRemove: () => void; onAddNext: () => void }) {
  const doseList = useId();
  const daysList = useId();
  // Enter: পরের ঘরে; নোটে (শেষ ঘর) Enter: নতুন ওষুধ
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Enter' || e.defaultPrevented || e.ctrlKey || e.metaKey || e.shiftKey) return;
    const el = e.target as HTMLElement;
    if (el.tagName === 'TEXTAREA' || el.tagName === 'BUTTON') return;
    const fields = [...e.currentTarget.querySelectorAll<HTMLElement>('[data-f]')];
    const i = fields.indexOf(el);
    if (i < 0) return;
    e.preventDefault();
    if (i === fields.length - 1) {
      if (last) onAddNext();
      else (e.currentTarget.nextElementSibling?.querySelector<HTMLElement>('[data-f="name"]'))?.focus();
    } else fields[i + 1].focus();
  };
  return (
    <div className="rxd-med" data-med={m.key} onKeyDown={onKey}>
      <span className="rxd-med-n num" aria-hidden="true">{num(n)}.</span>
      <div className="rxd-c-name">
        <DrugInput
          value={m.name}
          picked={m.picked}
          label={tr('Medicine name', 'ওষুধের নাম') + ' ' + num(n)}
          onType={(v) => onChange({ name: v, picked: false })}
          onPick={(d) => {
            onChange({ name: d.name.slice(0, 100), form: d.form.slice(0, 40), strength: d.generic.slice(0, 40), picked: true });
            // নাম বাছার পর সোজা খাওয়ার নিয়মে
            setTimeout(() => document.querySelector<HTMLInputElement>(`[data-med="${m.key}"] [data-f="dose"]`)?.focus(), 0);
          }}
        />
      </div>
      <MedField className="rxd-c-form" label={tr('Form', 'ধরন')}>
        <input data-f="form" className="input rxd-in" value={m.form} placeholder="Tablet" onChange={(e) => onChange({ form: e.target.value.slice(0, 40) })} autoComplete="off" />
      </MedField>
      <MedField className="rxd-c-str" label={tr('Strength', 'মাত্রা')}>
        <input data-f="strength" className="input rxd-in" value={m.strength} placeholder="500 mg" onChange={(e) => onChange({ strength: e.target.value.slice(0, 40) })} autoComplete="off" />
      </MedField>
      <MedField className="rxd-c-dose" label={tr('How to take', 'খাওয়ার নিয়ম')}>
        <input data-f="dose" className="input rxd-in num" list={doseList} value={m.dose} onChange={(e) => onChange({ dose: e.target.value.slice(0, 30) })} autoComplete="off" />
        <datalist id={doseList}>{DOSES.map((d) => <option key={d} value={d} />)}</datalist>
      </MedField>
      <MedField className="rxd-c-time" label={tr('Food', 'খাবার')}>
        <select data-f="timing" className="select rxd-in" value={m.timing} onChange={(e) => onChange({ timing: e.target.value })}>
          <option value="after">{timingLabel('after')}</option>
          <option value="before">{timingLabel('before')}</option>
          <option value="">—</option>
        </select>
      </MedField>
      <MedField className="rxd-c-days" label={tr('Days (0 = continue)', 'দিন (০ = চলবে)')}>
        <input
          data-f="days"
          className="input rxd-in num"
          inputMode="numeric"
          list={daysList}
          value={m.days}
          title={tr('0 = continue', '০ = চলবে')}
          onChange={(e) => onChange({ days: e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 3) })}
          autoComplete="off"
        />
        <datalist id={daysList}>{DAYS.map((d) => <option key={d} value={d} />)}</datalist>
      </MedField>
      <MedField className="rxd-c-note" label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')}>
        <input data-f="note" className="input rxd-in" value={m.note} onChange={(e) => onChange({ note: e.target.value.slice(0, 120) })} autoComplete="off" />
      </MedField>
      <span className="rxd-c-x">
        <IconButton small icon="delete" label={tr('Remove', 'সরান')} onClick={onRemove} />
      </span>
      {m.name.trim() ? <span className="rxd-c-sum t-body-s muted">{medHowTo({ name: m.name, dose: m.dose, timing: m.timing, days: parseInt(toAsciiDigits(m.days), 10) || 0 })}</span> : null}
    </div>
  );
}

function MedField({ label, className, children }: { label: string; className: string; children: ReactNode }) {
  return (
    <label className={cx('rxd-mf', className)}>
      <span className="rxd-mlabel">{label}</span>
      {children}
    </label>
  );
}

/** ওষুধের নাম: লেখার সাথে সাথে তালিকা (drug_search); ↑↓ দিয়ে বাছা, Enter-এ নেওয়া, Esc-এ বন্ধ */
function DrugInput({ value, picked, label, onType, onPick }: { value: string; picked: boolean; label: string; onType: (v: string) => void; onPick: (d: Drug) => void }) {
  const id = useId();
  const [items, setItems] = useState<Drug[]>([]);
  const [open, setOpen] = useState(false);
  const [hi, setHi] = useState(0);
  const seq = useRef(0);
  useEffect(() => {
    const q = value.trim();
    if (picked || q.length < 2) {
      seq.current++;
      setItems([]);
      setOpen(false);
      return;
    }
    const n = ++seq.current;
    const t = setTimeout(async () => {
      try {
        const r = await call<{ items: Drug[] }>('drug_search', { q });
        if (n !== seq.current) return;
        const list = (r.items ?? []).slice(0, 8);
        setItems(list);
        setHi(0);
        setOpen(list.length > 0 && document.activeElement?.id === id);
      } catch {
        /* খোঁজা না গেলেও লেখা যায় */
      }
    }, 250);
    return () => clearTimeout(t);
  }, [value, picked, id]);
  const pick = (d: Drug) => {
    setOpen(false);
    onPick(d);
  };
  return (
    <div className="rxd-combo">
      <label htmlFor={id} className="rxd-mlabel">{tr('Medicine name', 'ওষুধের নাম')}</label>
      <input
        id={id}
        data-f="name"
        className="input rxd-in"
        role="combobox"
        aria-label={label}
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={id + 'l'}
        aria-activedescendant={open && items[hi] ? `${id}o${hi}` : undefined}
        value={value}
        placeholder={tr('Type to search', 'লিখে খুঁজুন')}
        autoComplete="off"
        spellCheck={false}
        onChange={(e) => onType(e.target.value.slice(0, 100))}
        onFocus={() => items.length && !picked && setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={(e) => {
          if (!open || !items.length) {
            if (e.key === 'ArrowDown' && items.length) {
              e.preventDefault();
              setOpen(true);
            }
            return;
          }
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setHi((hi + 1) % items.length);
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setHi((hi - 1 + items.length) % items.length);
          } else if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            pick(items[hi]);
          } else if (e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();
            setOpen(false);
          }
        }}
      />
      {open ? (
        <ul id={id + 'l'} role="listbox" className="rxd-pop" aria-label={tr('Medicines', 'ওষুধ')}>
          {items.map((d, i) => (
            <li
              key={d.i + d.name}
              id={`${id}o${i}`}
              role="option"
              aria-selected={i === hi}
              className={cx('rxd-opt', i === hi && 'on')}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(d);
              }}
              onMouseEnter={() => setHi(i)}
            >
              <span className="t-body-m semi">{d.name}</span>
              <span className="t-body-s muted">{[d.generic, d.form, d.company].filter(Boolean).join(' · ')}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

// ---------------- দেওয়ার আগে কাগজ ----------------
function Block({ title, text }: { title: string; text: string }) {
  if (!text) return null;
  return (
    <div className="col gap-2">
      <span className="t-label-l health-paper-h">{title}</span>
      <p className="t-body-m pre">{text}</p>
    </div>
  );
}

function DraftPaper(p: {
  doctorName: string; degrees: string; chamber: string; patient: string; complaints: string; findings: string; diagnosis: string; meds: RxMed[];
  tests: string[]; testPlace: string; advice: string; follow: string;
}) {
  return (
    <article className="health-paper rxd-draft" aria-label={tr('Prescription', 'প্রেসক্রিপশন')}>
      <div className="col gap-2">
        <h3 className="t-title-l">{p.doctorName}</h3>
        {p.degrees ? <span className="t-body-m">{p.degrees}</span> : null}
        {p.chamber ? <span className="t-body-s muted">{p.chamber}</span> : null}
      </div>
      <hr className="divider" />
      <div className="row wrap">
        <span className="t-title-s grow">{p.patient}</span>
        <span className="t-body-s muted">{formatDate(todayUtcMidnight())}</span>
      </div>
      <Block title={tr('Complaints', 'সমস্যা')} text={p.complaints} />
      <Block title={tr('Findings', 'পরীক্ষায় পাওয়া')} text={p.findings} />
      <Block title={tr('Diagnosis', 'রোগনির্ণয়')} text={p.diagnosis} />
      {p.meds.length ? (
        <div className="col">
          <span className="health-rx-sign" aria-hidden="true">℞</span>
          <ol className="col health-meds">
            {p.meds.map((m, i) => (
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
      <Block title={tr('Tests', 'পরীক্ষা')} text={p.tests.map((t) => '• ' + t).join('\n')} />
      {p.tests.length && p.testPlace ? <span className="t-body-s muted">{tr('Suggested place: ', 'করাতে পারেন: ') + p.testPlace + tr(' (or anywhere you like)', ' (চাইলে অন্য জায়গাতেও)')}</span> : null}
      <Block title={tr('Advice', 'পরামর্শ')} text={p.advice} />
      <Block title={tr('Come again', 'আবার দেখাবেন')} text={p.follow} />
    </article>
  );
}
