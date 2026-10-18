// কেয়ারগিভার ও পরিবার (অ্যাপের CareAccess.kt → CareScreen)
// - আমার কেয়ারগিভার (সর্বোচ্চ ২ জন, ইমেইল দিয়ে আমন্ত্রণ, তাঁরা রাজি হলে চালু)
// - যাঁদের দেখাশোনা করি: রাজি/না, "ওঁর হয়ে খুলুন" (কেয়ার মোড — উপরে ফিতা দেখায়), সরে যান
// - পরিবারের সদস্য (নিজের অ্যাকাউন্ট নেই): সর্বোচ্চ ৬ জন
import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Action, Avatar, Button, ChoiceChips, ConfirmDialog, Dialog, EmptyState, ErrorBox, ErrorText, SkeletonCards, Tag, TextInput, toast } from '@/ui/kit';
import { SubPageBar } from '@/shell/templates';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { num, toAsciiDigits, tr, useLang } from '@/lib/i18n';
import { normalizePhone } from '@/lib/format';
import { setCareMode } from '@/lib/care';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { useBack } from './nav';

interface CareLink { patientUid: string; caretakerUid: string; patientName: string; caretakerName: string; caretakerEmail: string; status: string; createdAt: number }
export interface FamilyMember { id: string; name: string; relation: string; age: number | null; gender: string; phone: string }
interface CareData { caretakers: CareLink[]; caringFor: CareLink[]; family: FamilyMember[] }

function Person({ icon, name, sub, tag, children }: { icon: string; name: string; sub?: string; tag?: ReactNode; children?: ReactNode }) {
  return (
    <div className="acct-person">
      <hr className="divider" />
      <div className="row gap-12">
        <Avatar icon={icon} size={40} />
        <div className="col gap-2 grow">
          <span className="t-body-l semi ellipsis">{name}</span>
          {sub ? <span className="t-body-s muted acct-wrap">{sub}</span> : null}
        </div>
        {tag}
      </div>
      {children ? <div className="row wrap gap-8">{children}</div> : null}
    </div>
  );
}

export function CarePage() {
  useLang();
  const s = useSession();
  const nav = useNavigate();
  const title = tr('Caregivers & family', 'কেয়ারগিভার ও পরিবার');
  usePageTitle(title);
  const back = useBack('/profile');
  const q = useCall<CareData>('care_list', {}, { enabled: !s.guest, staleTime: 0 });
  const [inviting, setInviting] = useState(false);
  const [member, setMember] = useState<FamilyMember | 'new' | null>(null);
  const [confirm, setConfirm] = useState<{ text: string; run: () => Promise<unknown> } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // নোটিফিকেশন থেকে (?for=রোগী&rx=প্রেসক্রিপশন): এখনো কেয়ারগিভার হলে রোগীর মোডে সেই প্রেসক্রিপশন, নইলে কারণ
  const [params, setParams] = useSearchParams();
  const forUid = params.get('for');
  const forRx = params.get('rx');
  useEffect(() => {
    if (!forUid || !forRx || !q.data) return;
    const link = q.data.caringFor.find((c) => c.patientUid === forUid && c.status === 'active');
    if (link) {
      setCareMode({ uid: link.patientUid, name: link.patientName });
      nav(`/prescriptions/${encodeURIComponent(forRx)}`, { replace: true });
    } else {
      setError(tr("You can't open this prescription now — you're no longer their caregiver", 'এই প্রেসক্রিপশন এখন খোলা যাচ্ছে না — আপনি আর তাঁর কেয়ারগিভার নন'));
      setParams({}, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [forUid, forRx, q.data]);

  async function act(run: () => Promise<unknown>, ok?: string) {
    setBusy(true);
    setError(null);
    try {
      await run();
      if (ok) toast(ok);
      await q.refetch();
      return true;
    } catch (e) {
      setError(errorText(e));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const d = q.data;
  let body: ReactNode;
  if (s.ready && s.guest) {
    body = (
      <EmptyState icon="health_and_safety_filled" title={tr('Log in to do this', 'এর জন্য লগইন করুন')}>
        <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
      </EmptyState>
    );
  } else if (!d) {
    body = q.error ? <ErrorBox error={q.error.message} onRetry={() => q.refetch()} /> : <SkeletonCards n={2} h={180} />;
  } else {
    const nameOf = (c: CareLink) => c.caretakerName || c.caretakerEmail;
    const caregivers = (
      <section className="acct-scard pad">
        <h2 className="t-title-m">{tr('My caregivers', 'আমার কেয়ারগিভার')}</h2>
        <p className="t-body-s muted">
          {tr(
            "Up to 2 people you trust — your emergency contacts. From their own phone they can see your prescriptions and reports, send them to a pharmacy or lab, pay for a report review and get a serial for you. They also get your notifications. They can't change your profile or delete your account. Remove them any time.",
            'সর্বোচ্চ ২ জন বিশ্বস্ত মানুষ — আপনার জরুরি যোগাযোগ। তাঁরা নিজের ফোন থেকেই আপনার প্রেসক্রিপশন আর রিপোর্ট দেখতে, ফার্মেসি বা প্যাথলজিতে পাঠাতে, রিপোর্ট দেখানোর টাকা দিতে আর আপনার সিরিয়াল নিতে পারবেন; আপনার নোটিফিকেশনও পাবেন। প্রোফাইল বদলাতে বা অ্যাকাউন্ট মুছতে পারবেন না। যেকোনো সময় সরিয়ে দিতে পারবেন।',
          )}
        </p>
        {d.caretakers.map((c) => (
          <Person
            key={c.caretakerUid}
            icon="person_outline"
            name={nameOf(c)}
            sub={c.caretakerEmail}
            tag={<Tag tone={c.status === 'active' ? 'good' : undefined}>{c.status === 'active' ? tr('Active', 'চালু') : tr('Waiting for them', 'রাজি হওয়ার অপেক্ষায়')}</Tag>}
          >
            <Action
              icon="close"
              onClick={() =>
                setConfirm({
                  text: tr(`Remove ${nameOf(c)}? They won't be able to see or do anything for you.`, `${nameOf(c)}-কে সরাবেন? তিনি আর আপনার কিছু দেখতে বা করতে পারবেন না।`),
                  run: () => call('care_remove', { caretakerUid: c.caretakerUid }),
                })
              }
            >
              {tr('Remove', 'সরিয়ে দিন')}
            </Action>
          </Person>
        ))}
        {d.caretakers.length < 2 ? (
          <Button variant="outline" icon="add" block onClick={() => setInviting(true)}>{tr('Add a caregiver', 'কেয়ারগিভার যোগ করুন')}</Button>
        ) : null}
      </section>
    );
    const caringFor = d.caringFor.length ? (
      <section className="acct-scard pad">
        <h2 className="t-title-m">{tr('People I care for', 'যাঁদের দেখাশোনা করি')}</h2>
        {d.caringFor.map((c) => (
          <Person
            key={c.patientUid}
            icon="health_and_safety_filled"
            name={c.patientName || '-'}
            sub={c.status === 'active' ? tr('You can act for them', 'আপনি ওঁর হয়ে কাজ করতে পারেন') : tr('Wants you as their caregiver', 'আপনাকে কেয়ারগিভার করতে চান')}
          >
            {c.status === 'active' ? (
              <>
                <Action
                  primary
                  icon="chevron_right"
                  onClick={() => {
                    setCareMode({ uid: c.patientUid, name: c.patientName });
                    nav('/prescriptions');
                  }}
                >
                  {tr('Open for them', 'ওঁর হয়ে খুলুন')}
                </Action>
                <Action
                  icon="close"
                  onClick={() =>
                    setConfirm({
                      text: tr(`Stop being ${c.patientName}'s caregiver?`, `${c.patientName}-এর কেয়ারগিভার থেকে সরে যাবেন?`),
                      run: () => call('care_remove', { patientUid: c.patientUid }),
                    })
                  }
                >
                  {tr('Stop being caregiver', 'সরে যান')}
                </Action>
              </>
            ) : (
              <>
                <Action
                  primary
                  icon="check_circle"
                  busy={busy}
                  onClick={() => act(() => call('care_respond', { patientUid: c.patientUid, accept: true }), tr(`You're now ${c.patientName}'s caregiver`, `আপনি এখন ${c.patientName}-এর কেয়ারগিভার`))}
                >
                  {tr('Accept', 'রাজি')}
                </Action>
                <Action icon="close" busy={busy} onClick={() => act(() => call('care_respond', { patientUid: c.patientUid, accept: false }))}>
                  {tr('Decline', 'না')}
                </Action>
              </>
            )}
          </Person>
        ))}
      </section>
    ) : null;
    const family = (
      <section className="acct-scard pad">
        <h2 className="t-title-m">{tr('Family members', 'পরিবারের সদস্য')}</h2>
        <p className="t-body-s muted">
          {tr(
            "For family who don't have their own phone or account — elderly parents, children. Get a serial in their name, and add their prescriptions with the code and their mobile number.",
            'যাঁদের নিজের ফোন বা অ্যাকাউন্ট নেই — বয়স্ক বাবা-মা, শিশু। তাঁদের নামে সিরিয়াল নিন; প্রেসক্রিপশনের কোড আর তাঁদের মোবাইল নম্বর দিয়ে প্রেসক্রিপশন যোগ করুন।',
          )}
        </p>
        {d.family.map((m) => (
          <Person
            key={m.id}
            icon="person_outline"
            name={m.name}
            sub={[m.relation || null, m.age != null ? tr(`${m.age} yrs`, `${num(m.age)} বছর`) : null, m.phone ? num(m.phone) : null].filter(Boolean).join(' · ')}
            tag={<Action icon="edit" onClick={() => setMember(m)}>{tr('Edit', 'বদলান')}</Action>}
          />
        ))}
        {d.family.length < 6 ? (
          <Button variant="outline" icon="add" block onClick={() => setMember('new')}>{tr('Add a family member', 'পরিবারের সদস্য যোগ করুন')}</Button>
        ) : null}
      </section>
    );
    body = (
      <div className="acct-scols">
        <div className="col gap-12">
          {caregivers}
          {caringFor}
        </div>
        <div className="col gap-12">{family}</div>
      </div>
    );
  }

  return (
    <>
      <SubPageBar title={title} onBack={back} />
      <div className="form-page">
        <div className="form-inner acct-settings">
          <ErrorText>{error}</ErrorText>
          {body}
        </div>
      </div>
      {inviting ? (
        <InviteDialog
          onClose={() => setInviting(false)}
          onSent={() => {
            setInviting(false);
            toast(tr("Sent. They'll appear once they accept.", 'পাঠানো হয়েছে। তিনি রাজি হলে চালু হবে।'));
            q.refetch();
          }}
        />
      ) : null}
      {member && d ? (
        <MemberDialog
          existing={member === 'new' ? null : member}
          onClose={() => setMember(null)}
          onDelete={
            member === 'new'
              ? undefined
              : () => act(() => call('family_set', { family: d.family.filter((x) => x.id !== member.id) })).then((ok) => ok && setMember(null))
          }
          onSave={(m) => {
            const next = member === 'new' ? [...d.family, m] : d.family.map((x) => (x.id === m.id ? m : x));
            return act(() => call('family_set', { family: next }), tr('Saved', 'সেভ হয়েছে')).then((ok) => {
              if (ok) setMember(null);
            });
          }}
          busy={busy}
        />
      ) : null}
      {confirm ? (
        <ConfirmDialog
          title={tr('Are you sure?', 'নিশ্চিত?')}
          text={confirm.text}
          confirm={tr('Yes', 'হ্যাঁ')}
          danger
          busy={busy}
          onClose={() => !busy && setConfirm(null)}
          onConfirm={() => act(confirm.run).then(() => setConfirm(null))}
        />
      ) : null}
    </>
  );
}

function InviteDialog({ onClose, onSent }: { onClose: () => void; onSent: () => void }) {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function send() {
    setBusy(true);
    setError(null);
    try {
      await call('care_invite', { email: email.trim() });
      onSent();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="small"
      title={tr('Add a caregiver', 'কেয়ারগিভার যোগ করুন')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} disabled={!email.includes('@')} onClick={send}>{tr('Send', 'পাঠান')}</Button>
        </>
      }
    >
      <p className="t-body-m">{tr("Their Lifolo account's email. They need to have logged in to Lifolo once.", 'তাঁর Lifolo অ্যাকাউন্টের ইমেইল। তাঁকে অন্তত একবার Lifolo-তে লগইন করে থাকতে হবে।')}</p>
      <TextInput
        label={tr('Email', 'ইমেইল')}
        type="email"
        inputMode="email"
        autoFocus
        maxLength={200}
        value={email}
        onChange={(e) => setEmail(e.target.value.trim())}
        hint={tr("They'll get a notification and must accept.", 'তিনি নোটিফিকেশন পাবেন, রাজি হলে চালু হবে।')}
      />
      <ErrorText>{error}</ErrorText>
    </Dialog>
  );
}

function MemberDialog({
  existing, onClose, onDelete, onSave, busy,
}: { existing: FamilyMember | null; onClose: () => void; onDelete?: () => void; onSave: (m: FamilyMember) => void; busy: boolean }) {
  const [name, setName] = useState(existing?.name ?? '');
  const [relation, setRelation] = useState(existing?.relation ?? '');
  const [age, setAge] = useState(existing?.age != null ? String(existing.age) : '');
  const [gender, setGender] = useState(existing?.gender ?? '');
  const [phone, setPhone] = useState(existing?.phone ?? '');
  const [error, setError] = useState<string | null>(null);

  function save() {
    const a = parseInt(toAsciiDigits(age), 10);
    const p = phone.trim();
    if (p && !normalizePhone(p)) return setError(tr('Enter a valid mobile number (01…, 11 digits)', 'সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)'));
    setError(null);
    onSave({
      id: existing?.id ?? '',
      name: name.trim(),
      relation: relation.trim(),
      age: Number.isFinite(a) && a >= 0 && a <= 120 ? a : null,
      gender,
      phone: p ? normalizePhone(p)! : '',
    });
  }

  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={existing ? existing.name : tr('Family member', 'পরিবারের সদস্য')}
      actions={
        <>
          {onDelete ? <Button variant="danger-text" disabled={busy} onClick={onDelete} className="acct-left-act">{tr('Remove this member', 'এই সদস্যকে সরান')}</Button> : null}
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} disabled={!name.trim()} onClick={save}>{tr('Save', 'সেভ')}</Button>
        </>
      }
    >
      <TextInput label={tr('Name', 'নাম')} maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
      <TextInput label={tr('Relation (e.g. Mother)', 'সম্পর্ক (যেমন: মা)')} maxLength={30} value={relation} onChange={(e) => setRelation(e.target.value)} />
      <TextInput label={tr('Age', 'বয়স')} inputMode="numeric" value={age} onChange={(e) => setAge(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 3))} />
      <ChoiceChips
        options={[{ value: 'male', label: tr('Male', 'পুরুষ') }, { value: 'female', label: tr('Female', 'নারী') }]}
        value={gender}
        onChange={(g: string) => setGender(gender === g ? '' : g)}
      />
      <TextInput
        label={tr('Mobile number (optional)', 'মোবাইল নম্বর (ঐচ্ছিক)')}
        inputMode="tel"
        maxLength={20}
        value={phone}
        onChange={(e) => setPhone(e.target.value)}
        hint={tr('The number given at the chamber — used to add their prescriptions.', 'চেম্বারে যে নম্বর দেওয়া হয় — এটা দিয়ে তাঁর প্রেসক্রিপশন যোগ হবে।')}
      />
      <ErrorText>{error}</ErrorText>
    </Dialog>
  );
}
