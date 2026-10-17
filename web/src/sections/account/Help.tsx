// সাহায্য ও আপিল (অ্যাপের Support.kt): নতুন অনুরোধ, নিজের অনুরোধের তালিকা, কথোপকথন আর আরও লেখা।
// /help — তালিকা; /help/new?category=pharmacy — নতুন (বিষয় আগে থেকে বাছা); /help/:id — কথোপকথন
import { useState } from 'react';
import { matchPath, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Button, ChoiceChips, EmptyState, ErrorBox, ErrorText, ListRow, SkeletonCards, TextArea, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { DetailPane, ListDetail, SubPageBar } from '@/shell/templates';
import { useMobile, usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { tr, trPlain, useLang } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { SUPPORT_EMAIL, TICKET_CATEGORIES, ticketAnswered, ticketLabel, ticketStatus, type Ticket, type TicketMessage } from './util';
import { useBack } from './nav';

function Bubble({ m }: { m: TicketMessage }) {
  const admin = m.from === 'admin';
  return (
    <div className={cx('acct-bubble', admin && 'admin')}>
      <p className="t-body-m pre">{m.text}</p>
      <span className="t-label-s muted">{(admin ? tr('Lifolo team', 'Lifolo টিম') : tr('You', 'আপনি')) + ', ' + timeAgo(m.at)}</span>
    </div>
  );
}

function NewTicket({ start, onSent }: { start: string | null; onSent: (id: string | null) => void }) {
  const [category, setCategory] = useState<string | null>(start && [...TICKET_CATEGORIES, 'homevisit', 'medorder'].includes(start) ? start : null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cats = category && !TICKET_CATEGORIES.includes(category) ? [...TICKET_CATEGORIES, category] : TICKET_CATEGORIES;

  async function send() {
    if (!category) return;
    setBusy(true);
    setError(null);
    try {
      const r = await call<{ id?: string }>('ticket_create', { category, text: text.trim() });
      onSent(r.id ?? null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <span className="t-title-s">{tr('What is it about?', 'কী নিয়ে?')}</span>
      <ChoiceChips options={cats.map((c) => ({ value: c, label: ticketLabel(c) }))} value={category} onChange={setCategory} />
      <TextArea label={tr('Describe the problem', 'সমস্যাটা খুলে লিখুন')} rows={5} maxLength={1500} value={text} onChange={(e) => setText(e.target.value)} />
      <ErrorText>{error}</ErrorText>
      <div className="form-actions">
        <Button icon="send" busy={busy} disabled={!category || text.trim().length < 10} onClick={send}>{tr('Send', 'পাঠান')}</Button>
      </div>
    </>
  );
}

function Conversation({ t, onSent }: { t: Ticket; onSent: () => void }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function send() {
    setBusy(true);
    setError(null);
    try {
      await call('ticket_reply', { id: t.id, text: text.trim() });
      setText('');
      toast(tr('Sent.', 'পাঠানো হয়েছে।'));
      onSent();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <span className="t-label-l c-primary">{ticketStatus(t)}</span>
      <div className="acct-thread">
        {t.messages.map((m, i) => <Bubble key={i} m={m} />)}
      </div>
      <TextArea
        label={t.status === 'closed' ? tr('Write again (reopens it)', 'আবার লিখুন (আবার খুলবে)') : tr('Write more', 'আরও লিখুন')}
        rows={3}
        maxLength={1500}
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
      <ErrorText>{error}</ErrorText>
      <div className="form-actions">
        <Button icon="send" busy={busy} disabled={!text.trim()} onClick={send}>{tr('Send', 'পাঠান')}</Button>
      </div>
    </>
  );
}

export function HelpPage() {
  useLang();
  const s = useSession();
  const nav = useNavigate();
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const mobile = useMobile();
  const title = tr('Help & appeals', 'সাহায্য ও আপিল');
  usePageTitle(title);
  const back = useBack(s.guest ? '/' : '/profile');
  const startCat = params.get('category');
  const isNew = !!matchPath('/help/new', pathname) || (!!startCat && pathname.replace(/\/$/, '') === '/help');
  const selId = !isNew ? matchPath('/help/:id', pathname)?.params.id ?? null : null;
  const q = useCall<{ items?: Ticket[] }>('tickets_mine', {}, { enabled: !s.guest, staleTime: 0 });

  if (s.ready && s.guest) {
    return (
      <>
        {mobile ? <SubPageBar title={title} onBack={back} /> : null}
        <div className="acct-page">
          <div className="acct-narrow col gap-12">
            <EmptyState icon="help" title={tr('Log in to do this', 'এর জন্য লগইন করুন')}>
              <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
            </EmptyState>
            <nav className="acct-menu">
              <ListRow icon="mail" title={tr('Contact support', 'সাপোর্টে যোগাযোগ')} sub={SUPPORT_EMAIL} href={`mailto:${SUPPORT_EMAIL}`} trailing={<span />} />
            </nav>
          </div>
        </div>
      </>
    );
  }

  const items = q.data?.items ?? [];
  const sel = selId ? items.find((t) => t.id === selId) ?? null : null;

  const list = (
    <div className="list-inner">
      <Button icon="edit_note" block onClick={() => nav(`/help/new${startCat ? `?category=${encodeURIComponent(startCat)}` : ''}`)}>{tr('Write to us', 'আমাদের লিখুন')}</Button>
      <h2 className="acct-sec">{tr('My messages', 'আমার অনুরোধ')}</h2>
      {q.isLoading ? (
        <SkeletonCards n={3} h={92} />
      ) : q.error && !items.length ? (
        <ErrorBox error={q.error.message} onRetry={() => q.refetch()} />
      ) : !items.length ? (
        <p className="t-body-m muted">{tr('Nothing yet.', 'এখনো কিছু নেই।')}</p>
      ) : (
        items.map((t) => {
          const last = t.messages[t.messages.length - 1];
          const hot = ticketAnswered(t) && t.status !== 'closed';
          return (
            <button key={t.id} type="button" className={cx('card click acct-ticket', selId === t.id && 'sel')} aria-current={selId === t.id ? 'true' : undefined} onClick={() => nav(`/help/${t.id}`)}>
              <span className="row">
                <span className="t-title-s grow ellipsis">{ticketLabel(t.category)}</span>
                <span className={cx('t-label-m', hot ? 'c-primary' : 'muted')}>{ticketStatus(t)}</span>
              </span>
              {last ? <span className="t-body-m clamp2">{last.text}</span> : null}
              <span className="t-body-s muted">{timeAgo(t.updatedAt)}</span>
            </button>
          );
        })
      )}
    </div>
  );

  let detail = null;
  if (isNew) {
    detail = (
      <DetailPane title={tr('Write to us', 'আমাদের লিখুন')} backTo="/help">
        <NewTicket
          key={startCat ?? ''}
          start={startCat}
          onSent={(id) => {
            toast(trPlain('Sent. The Lifolo team will reply here.', 'পাঠানো হয়েছে। Lifolo টিম এখানেই উত্তর দেবে।'));
            q.refetch();
            nav(id ? `/help/${id}` : '/help', { replace: true });
          }}
        />
      </DetailPane>
    );
  } else if (selId) {
    detail = (
      <DetailPane title={sel ? ticketLabel(sel.category) : title} backTo="/help">
        {q.isLoading ? <SkeletonCards n={2} h={64} /> : sel ? <Conversation key={sel.id} t={sel} onSent={() => q.refetch()} /> : <EmptyState icon="help" title={tr('Not found', 'পাওয়া যায়নি')} />}
      </DetailPane>
    );
  }

  return (
    <>
      {mobile && !detail ? <SubPageBar title={title} onBack={back} /> : null}
      <ListDetail list={list} detail={detail} placeholder={<Icon name="forum_outline" size={64} className="acct-ph" />} />
    </>
  );
}
