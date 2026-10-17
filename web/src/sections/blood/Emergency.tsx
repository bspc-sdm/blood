// জরুরি রক্তের আহ্বান (Lifolo টিম) — অ্যাপের Emergency.kt।
// হোমের উপরে: কোন গ্রুপের কত দরকার, কতজন আসছেন; নিজের গ্রুপ দরকার হলে "আসছি"।
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Action, ErrorText, InfoText, PhoneLine, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { districtLabel } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { isDonor } from '@/lib/profile';
import { BLOOD_GROUPS, UNKNOWN_GROUP } from '@/lib/rules';
import { Countdown } from './parts';
import { KEY } from './data';

interface Group { group: string; need: number; coming: number; full: boolean }
interface Appeal {
  id: string; hospitalName: string; district: string; contactPhone: string; note: string; groups: Group[];
  endsAt: number; myGroup: string; myStatus: string; canCome: boolean; eligible: boolean;
}
interface Ask { id: string; hospitalName: string; endedAt: number }

function parse(o: any): Appeal {
  const g = o.groups ?? {};
  return {
    id: String(o.id ?? ''),
    hospitalName: o.hospitalName ?? '',
    district: o.district ?? '',
    contactPhone: o.contactPhone ?? '',
    note: o.note ?? '',
    groups: BLOOD_GROUPS.filter((k) => g[k]).map((k) => ({ group: k, need: g[k].need ?? 0, coming: g[k].coming ?? 0, full: !!g[k].full })),
    endsAt: typeof o.endsAt === 'number' ? o.endsAt : 0,
    myGroup: o.myGroup ?? '',
    myStatus: o.myStatus ?? '',
    canCome: !!o.canCome,
    eligible: !!o.eligible,
  };
}

const THANKS_DONATED = () => tr('Thank you! Your last donation date is set to today.', 'ধন্যবাদ! আপনার শেষ রক্তদানের তারিখ আজ বসানো হয়েছে।');

export function EmergencyBanner({ district }: { district?: string | null }) {
  const s = useSession();
  const [thanks, setThanks] = useState<string | null>(null);
  const q = useQuery({
    queryKey: [KEY, 'emergencies', s.uid, s.guest ? district ?? '' : ''],
    queryFn: async () => {
      const o = await call<{ items?: any[]; ask?: any[] }>('emergencies_active', s.guest && district ? { district } : {});
      return { items: (o.items ?? []).map(parse), ask: (o.ask ?? []) as Ask[] };
    },
    enabled: s.ready,
    staleTime: 30_000,
  });
  const items = q.data?.items ?? [];
  const ask = q.data?.ask ?? [];
  if (!items.length && !ask.length && !thanks) return null;
  const reload = () => q.refetch();
  return (
    <div className="col blood-emg-wrap">
      <InfoText>{thanks}</InfoText>
      {ask.map((a) => (
        <AskCard key={a.id} a={a} onDone={(m) => { setThanks(m); reload(); }} />
      ))}
      {items.map((e) => (
        <EmergencyCard key={e.id} e={e} onDonated={setThanks} onChanged={reload} />
      ))}
    </div>
  );
}

/** আহ্বান শেষে: "রক্ত দিয়েছেন?" */
function AskCard({ a, onDone }: { a: Ask; onDone: (msg: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function answer(yes: boolean) {
    setBusy(true);
    setErr(null);
    try {
      await call('emergency_donated', { id: a.id, donated: yes });
      onDone(yes ? THANKS_DONATED() : tr('Thanks for letting us know.', 'জানানোর জন্য ধন্যবাদ।'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="card tonal col gap-8" style={{ padding: 14 }}>
      <h3 className="t-title-m bold">{tr('Did you donate blood?', 'রক্ত দিয়েছেন?')}</h3>
      <p className="t-body-m">{tr(`You said you were coming to the emergency appeal at ${a.hospitalName}.`, `${a.hospitalName}-এর জরুরি আহ্বানে আপনি "আসছি" বলেছিলেন।`)}</p>
      <ErrorText>{err}</ErrorText>
      <div className="row gap-8 wrap">
        <Action icon="check_circle" primary busy={busy} onClick={() => answer(true)}>{tr('Yes, I donated', 'হ্যাঁ, দিয়েছি')}</Action>
        <Action icon="close" disabled={busy} onClick={() => answer(false)}>{tr('No', 'না, দিইনি')}</Action>
      </div>
    </section>
  );
}

function EmergencyCard({ e, onDonated, onChanged }: { e: Appeal; onDonated: (m: string) => void; onChanged: () => void }) {
  const s = useSession();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function run(f: () => Promise<unknown>, after?: () => void) {
    setBusy(true);
    setErr(null);
    try {
      await f();
      after?.();
      onChanged();
    } catch (x) {
      setErr(errorText(x));
    } finally {
      setBusy(false);
    }
  }
  const my = e.groups.find((g) => g.group === e.myGroup);
  // লগইন ছাড়া, বা রক্তদাতার তথ্য দেওয়া নেই: "আসছি" চাপলে আগে সেগুলো চাওয়া হয়
  const needDonor = !e.myStatus && (s.guest || !isDonor(s.profile));
  const message = needDonor
    ? tr('Can you donate? Tap "I\'m coming" to add your donor details first.', 'রক্ত দিতে পারবেন? "আসছি" চাপুন, আগে রক্তদাতার তথ্য দিতে হবে।')
    : e.myStatus === 'coming'
      ? tr("You're coming — thank you! Please bring your ID.", 'আপনি আসছেন — ধন্যবাদ! সাথে পরিচয়পত্র আনুন।')
      : e.myStatus === 'donated'
        ? tr('You donated — thank you!', 'আপনি রক্ত দিয়েছেন — ধন্যবাদ!')
        : !e.myGroup || e.myGroup === UNKNOWN_GROUP
          ? tr('Add your blood group in your profile to respond. Please tell people you know.', 'সাড়া দিতে প্রোফাইলে রক্তের গ্রুপ দিন। পরিচিতদের জানাতে পারেন।')
          : !my
            ? tr("Your blood group isn't needed in this appeal. Please tell people you know.", 'এই আহ্বানে আপনার গ্রুপ দরকার নেই। পরিচিতদের জানাতে পারেন।')
            : my.full
              ? tr('Enough donors are already coming for your group — thank you!', 'আপনার গ্রুপের যথেষ্ট ডোনার আসছেন — ধন্যবাদ!')
              : !e.eligible
                ? tr("You can't donate right now (last donation, age or weight).", 'এখন আপনি রক্ত দিতে পারবেন না (শেষ রক্তদান, বয়স বা ওজন)।')
                : tr(`Your group (${e.myGroup}) is needed. Tap "I'm coming" only if you can go now.`, `আপনার গ্রুপ (${e.myGroup}) দরকার। এখনই যেতে পারলে তবেই "আসছি" চাপুন।`);
  return (
    <section className="card danger col gap-8 blood-emg">
      <div className="row gap-8 c-error">
        <Icon name="warning" size={20} />
        <span className="t-label-l bold">{tr('Emergency appeal · Lifolo team', 'জরুরি আহ্বান · Lifolo টিম')}</span>
      </div>
      <div className="col gap-2">
        <h3 className="t-title-m bold">{e.hospitalName}</h3>
        <span className="t-body-s">{districtLabel(e.district)}</span>
      </div>
      {e.note.trim() ? <p className="t-body-m pre">{e.note}</p> : null}
      <div className="row gap-8 blood-emg-groups">
        {e.groups.map((g) => (
          <div key={g.group} className={cx('blood-emg-g', g.full ? 'full' : g.group === e.myGroup && 'mine')}>
            <span className="t-title-s bold">{g.group}</span>
            <span className="t-label-s">{g.full ? tr('Enough', 'যথেষ্ট') : tr(`${g.coming}/${g.need} coming`, `${num(g.coming)}/${num(g.need)} আসছেন`)}</span>
          </div>
        ))}
      </div>
      <Countdown target={e.endsAt} />
      <p className="t-body-m semi">{message}</p>
      <ErrorText>{err}</ErrorText>
      <div className="row gap-8 wrap">
        {e.myStatus === 'coming' ? (
          <>
            <Action icon="check_circle" primary busy={busy} onClick={() => run(() => call('emergency_donated', { id: e.id, donated: true }), () => onDonated(THANKS_DONATED()))}>
              {tr('I donated', 'রক্ত দিয়েছি')}
            </Action>
            <Action icon="close" busy={busy} onClick={() => run(() => call('emergency_respond', { id: e.id, op: 'cancel' }))}>{tr("Can't come", 'আসতে পারছি না')}</Action>
          </>
        ) : needDonor ? (
          <Action icon="directions_run" primary busy={busy} onClick={() => s.requireLogin(tr('Add your donor details to respond', 'সাড়া দিতে রক্তদাতার তথ্য দিন'), onChanged, { donor: true })}>
            {tr("I'm coming", 'আসছি')}
          </Action>
        ) : e.canCome ? (
          <Action icon="directions_run" primary busy={busy} onClick={() => run(() => call('emergency_respond', { id: e.id, op: 'coming' }))}>{tr("I'm coming", 'আসছি')}</Action>
        ) : null}
      </div>
      {e.contactPhone.trim() ? (
        <div className="col gap-4">
          <span className="t-label-m">{tr('Call hospital', 'হাসপাতালে ফোন')}</span>
          <PhoneLine phone={e.contactPhone} />
        </div>
      ) : null}
    </section>
  );
}
