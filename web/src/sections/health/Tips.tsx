// হেলথ টিপস — অ্যাপের HealthTipsTab, TipDetailScreen, PostTipScreen (শুধু অনুমোদিত ব্যাজধারী)
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button, ConfirmDialog, EmptyState, ErrorBox, ErrorText, Fab, IconButton, InfoText, Loading, SkeletonCards, TextInput, cx } from '@/ui/kit';
import { DetailPane, FormPage, ListDetail } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { AuthorLine, EditTextDialog, EditedNote, HealthTabs, ListHeading, ReportDialog, approvedBadge, goBack, useFlash, useMe } from './common';
import { usePaged } from './Discussion';

export interface Tip { id: string; title: string; body: string; authorName: string; badge: string | null; mine: boolean; createdAt: number; editedAt?: number }

export function TipsPage({ id }: { id: string | null }) {
  usePageTitle(tr('Health', 'স্বাস্থ্য'));
  const nav = useNavigate();
  const me = useMe();
  const two = useTwoPane();
  const q = usePaged<Tip>('tips');
  const canPost = !!approvedBadge(me);
  const tip = id ? q.items.find((t) => t.id === id) ?? null : null;

  // সরাসরি লিংকে খোলা টিপস প্রথম পাতায় না থাকলে পরের পাতাগুলো আনা (সর্বোচ্চ ১০ পাতা)
  const pages = q.data?.pages.length ?? 0;
  useEffect(() => {
    if (id && !tip && q.hasNextPage && !q.isFetchingNextPage && pages < 10) q.fetchNextPage();
  }, [id, tip, q, pages]);

  const list = (
    <div className="list-inner">
      <HealthTabs value="tips" />
      <ListHeading
        title={tr('Health Tips', 'হেলথ টিপস')}
        onRefresh={() => q.refetch()}
        refreshing={q.isFetching && !q.isFetchingNextPage}
        action={two && canPost ? <Button small icon="edit_note" onClick={() => nav('/health/post-tip')}>{tr('Post a tip', 'টিপস দিন')}</Button> : null}
      />
      {q.isPending ? (
        <SkeletonCards n={4} h={130} />
      ) : q.isError && !q.items.length ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !q.items.length ? (
        <EmptyState icon="lightbulb_outline" title={tr('No tips yet', 'এখনো কোনো টিপস নেই')} text={tr('Doctors, nurses and health experts will share tips here.', 'ডাক্তার, নার্স আর স্বাস্থ্য বিশেষজ্ঞরা এখানে টিপস দেবেন।')} />
      ) : (
        <>
          {q.items.map((t) => (
            <TipCard key={t.id} t={t} selected={t.id === id} onClick={() => nav(`/health/tip/${t.id}`)} />
          ))}
          {q.hasNextPage ? (
            <Button variant="text" block busy={q.isFetchingNextPage} onClick={() => q.fetchNextPage()}>{tr('Load more', 'আরও দেখুন')}</Button>
          ) : null}
        </>
      )}
      {!two && canPost ? <Fab icon="edit_note" onClick={() => nav('/health/post-tip')}>{tr('Tip', 'টিপস')}</Fab> : null}
    </div>
  );

  let detail = null;
  if (id) {
    if (tip) detail = <TipDetail key={tip.id} tip={tip} />;
    else {
      const searching = q.isPending || q.isFetchingNextPage || (q.hasNextPage && pages < 10);
      detail = (
        <DetailPane title={tr('Health Tips', 'হেলথ টিপস')} onBack={() => nav('/health/tips')}>
          {searching ? <Loading /> : q.isError ? <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} /> : <EmptyState icon="lightbulb_outline" title={tr('Not found', 'পাওয়া যায়নি')} />}
        </DetailPane>
      );
    }
  }
  return <ListDetail list={list} detail={detail} placeholder={<EmptyState icon="lightbulb_outline" title={tr('Pick a tip', 'একটা টিপস বেছে নিন')} />} />;
}

function TipCard({ t, selected, onClick }: { t: Tip; selected: boolean; onClick: () => void }) {
  return (
    <article
      className={cx('card click col', selected && 'sel')}
      role="button"
      tabIndex={0}
      aria-current={selected || undefined}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
    >
      <AuthorLine name={t.authorName} badge={t.badge} createdAt={t.createdAt} />
      <h3 className="t-title-m">{t.title}</h3>
      <p className="t-body-m muted pre health-clamp3">{t.body}</p>
    </article>
  );
}

function TipDetail({ tip }: { tip: Tip }) {
  const nav = useNavigate();
  const qc = useQueryClient();
  const s = useSession();
  const me = useMe();
  const [editing, setEditing] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useFlash();
  const back = () => nav('/health/tips');

  const actions = (
    <>
      {!tip.mine ? <IconButton icon="flag" label={tr('Report', 'রিপোর্ট')} onClick={() => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => setReporting(true))} /> : null}
      {tip.mine ? <IconButton icon="edit" label={tr('Edit', 'বদলান')} onClick={() => setEditing(true)} /> : null}
      {tip.mine || me?.isAdmin ? <IconButton icon="delete" label={tr('Delete', 'মুছুন')} onClick={() => setDeleting(true)} /> : null}
    </>
  );

  return (
    <DetailPane title={tr('Health Tips', 'হেলথ টিপস')} onBack={back} actions={actions}>
      <h2 className="t-headline">{tip.title}</h2>
      <AuthorLine name={tip.authorName} badge={tip.badge} createdAt={tip.createdAt} />
      <p className="t-body-l pre">{tip.body}</p>
      {tip.editedAt ? <EditedNote /> : null}
      <InfoText>{info}</InfoText>

      {reporting ? (
        <ReportDialog
          onClose={() => setReporting(false)}
          onConfirm={async () => {
            await call('report', { kind: 'tip', id: tip.id });
            setReporting(false);
            setInfo(tr('Reported. An admin will review it.', 'রিপোর্ট পাঠানো হয়েছে। অ্যাডমিন দেখবেন।'));
          }}
        />
      ) : null}
      {editing ? (
        <EditTextDialog
          title={tr('Edit tip', 'টিপস বদলান')}
          initial={tip.body}
          initialTitle={tip.title}
          maxLen={3000}
          onClose={() => setEditing(false)}
          onSave={async (body, title) => {
            await call('edit_tip', { id: tip.id, title, body });
            setEditing(false);
            await qc.invalidateQueries({ queryKey: ['tips'] });
          }}
        />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title={tr('Delete this tip?', 'টিপসটা মুছবেন?')}
          confirm={tr('Delete', 'মুছুন')}
          danger
          busy={busy}
          error={err}
          onClose={() => {
            setDeleting(false);
            setErr(null);
          }}
          onConfirm={async () => {
            setBusy(true);
            try {
              await call('delete_tip', { id: tip.id });
              setDeleting(false);
              qc.invalidateQueries({ queryKey: ['tips'] });
              back();
            } catch (e) {
              setErr(errorText(e));
            }
            setBusy(false);
          }}
        />
      ) : null}
    </DetailPane>
  );
}

// ---------- টিপস দিন ----------
export function PostTipPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const s = useSession();
  const me = useMe();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const back = () => goBack(nav, '/health/tips');

  async function post() {
    if (!title.trim() || !body.trim()) {
      setErr(tr('Add a title and the tip', 'শিরোনাম আর টিপস দুটোই লিখুন'));
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const r = await call<{ id?: string }>('post_tip', { title: title.trim(), body: body.trim() });
      await qc.invalidateQueries({ queryKey: ['tips'] });
      nav(r.id ? `/health/tip/${r.id}` : '/health/tips', { replace: true });
    } catch (e) {
      setErr(errorText(e));
      setBusy(false);
    }
  }

  let content;
  if (s.guest) {
    content = (
      <EmptyState icon="lightbulb_outline" title={tr('Only verified health professionals can post tips', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা টিপস দিতে পারেন')}>
        <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
      </EmptyState>
    );
  } else if (!me) {
    content = <Loading />;
  } else if (!approvedBadge(me)) {
    content = (
      <EmptyState icon="workspace_premium" title={tr('Only verified health professionals can post tips', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা টিপস দিতে পারেন')}>
        <Link className="btn tonal" to="/health/badge">{tr('Professional badge', 'পেশাদার ব্যাজ')}</Link>
      </EmptyState>
    );
  } else {
    content = (
      <>
        <TextInput label={tr('Title', 'শিরোনাম')} value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} autoFocus />
        <div className="field">
          <label htmlFor="health-tip">{tr('Tip', 'টিপস')}</label>
          <textarea id="health-tip" className="textarea" rows={10} maxLength={3000} value={body} onChange={(e) => setBody(e.target.value)} />
          <span className="hint" style={{ textAlign: 'right' }}>{num(body.length)}/{num(3000)}</span>
        </div>
        <ErrorText>{err}</ErrorText>
        <Button icon="send" block busy={busy} onClick={post}>{tr('Post', 'পোস্ট করুন')}</Button>
      </>
    );
  }
  return <FormPage title={tr('Post a tip', 'টিপস দিন')} onBack={back}>{content}</FormPage>;
}
