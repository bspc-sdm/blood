// সহকারী (শুধু ডাক্তার): ইমেইল যোগ/সরানো — অ্যাপের RxTeamDialog, প্যানেলের teamView (প্রতিটা বদল সাথে সাথে সেভ)।
import { useState } from 'react';
import { Button, ErrorBox, ErrorText, IconButton, SkeletonCards, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { MAX_ASSISTANTS } from './model';

export function TeamPage() {
  const q = useCall<{ emails: string[] }>('rx_team');
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const emails = q.data?.emails ?? [];
  const full = emails.length >= MAX_ASSISTANTS;

  async function save(next: string[], what: string) {
    setBusy(what);
    setErr(null);
    try {
      await call<{ emails: string[] }>('rx_team_set', { emails: next });
      await q.refetch();
      toast(tr('Saved', 'সেভ হয়েছে'));
      return true;
    } catch (e) {
      setErr(errorText(e));
      return false;
    } finally {
      setBusy(null);
    }
  }
  const add = async () => {
    const v = input.trim().toLowerCase();
    if (!v.includes('@') || busy) return;
    if (emails.includes(v)) {
      setInput('');
      return;
    }
    if (await save([...emails, v], '+')) setInput('');
  };

  if (q.isPending) return <SkeletonCards n={1} h={220} />;
  if (q.isError) return <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />;

  return (
    <div className="rxd-narrow col gap-16">
      <section className="card col gap-12">
        <div className="row">
          <span className="rxd-ic"><Icon name="group_round" /></span>
          <div className="col gap-2 grow">
            <h2 className="t-title-m">{tr('Assistants', 'সহকারী')}</h2>
            <span className="t-body-s muted num">{tr(`${emails.length} of ${MAX_ASSISTANTS}`, `${num(emails.length)}/${num(MAX_ASSISTANTS)} জন`)}</span>
          </div>
        </div>
        <p className="t-body-m">
          {tr('Assistants log in with this email and enter patients. Only you write prescriptions.', 'সহকারী এই ইমেইলে লগইন করে রোগী এন্ট্রি করবেন। প্রেসক্রিপশন লিখবেন শুধু আপনি।')}
          {' '}
          {tr(`Up to ${MAX_ASSISTANTS}.`, `সর্বোচ্চ ${num(MAX_ASSISTANTS)} জন।`)}
        </p>
        {emails.length ? (
          <ul className="col gap-2 rxd-emails">
            {emails.map((e) => (
              <li key={e} className="row rxd-email">
                <Icon name="mail" className="rxd-ic16" />
                <span className="grow t-body-l rxd-break">{e}</span>
                <IconButton small icon="close" label={tr('Remove', 'সরান') + ': ' + e} disabled={!!busy} onClick={() => save(emails.filter((x) => x !== e), e)} />
              </li>
            ))}
          </ul>
        ) : (
          <p className="t-body-m muted">{tr('No assistants', 'কোনো সহকারী নেই')}</p>
        )}
        {!full ? (
          <form className="row gap-8" onSubmit={(e) => { e.preventDefault(); add(); }}>
            <input
              className="input grow"
              type="email"
              inputMode="email"
              autoComplete="off"
              value={input}
              onChange={(e) => setInput(e.target.value.trim().slice(0, 200))}
              placeholder={tr("Assistant's email", 'সহকারীর ইমেইল')}
              aria-label={tr("Assistant's email", 'সহকারীর ইমেইল')}
            />
            <Button type="submit" className="rxd-add-btn" busy={busy === '+'} disabled={!input.includes('@') || !!busy}>{tr('Add', 'যোগ')}</Button>
          </form>
        ) : null}
        <ErrorText>{err}</ErrorText>
      </section>
    </div>
  );
}
