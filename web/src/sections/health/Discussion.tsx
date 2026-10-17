// স্বাস্থ্য আলোচনা — অ্যাপের HealthDiscussionTab, QuestionDetailScreen, AskQuestionScreen
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Check, ConfirmDialog, EmptyState, ErrorBox, ErrorText, Fab, IconButton, IconLine, InfoText, Loading, SkeletonCards, Switch, cx } from '@/ui/kit';
import { DetailPane, FormPage, ListDetail } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr, useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import {
  AuthorLine, EditTextDialog, EditedNote, EmergencyNotice, HealthTabs, ListHeading, ReportDialog, answersText, approvedBadge, goBack, useFlash, useMe,
} from './common';

export interface Question { id: string; text: string; authorName: string | null; mine: boolean; createdAt: number; answerCount: number; editedAt?: number; anonymous?: boolean }
export interface Answer { id: string; text: string; authorName: string; badge: string | null; mine: boolean; createdAt: number; editedAt?: number }

const PAGE = 30;

/** তালিকা পাতায় পাতায় (before = শেষটার সময়) — প্রশ্ন আর টিপস দুটোতেই */
export function usePaged<T extends { id: string; createdAt: number }>(action: 'questions' | 'tips') {
  const lang = useLang();
  const { uid } = useSession();
  const q = useInfiniteQuery({
    queryKey: [action, 'pages', lang, uid],
    queryFn: ({ pageParam }) => call<{ items?: T[] }>(action, pageParam ? { before: pageParam } : {}).then((r) => r.items ?? []),
    initialPageParam: 0,
    getNextPageParam: (last: T[]) => (last.length >= PAGE ? last[last.length - 1].createdAt : undefined),
  });
  const items = useMemo(() => {
    const seen = new Set<string>();
    const out: T[] = [];
    for (const p of q.data?.pages ?? []) for (const it of p) if (!seen.has(it.id)) (seen.add(it.id), out.push(it));
    return out;
  }, [q.data]);
  return { ...q, items };
}

export function DiscussionPage({ id }: { id: string | null }) {
  usePageTitle(tr('Health', 'স্বাস্থ্য'));
  const nav = useNavigate();
  const s = useSession();
  const two = useTwoPane();
  const q = usePaged<Question>('questions');
  const ask = () => s.requireLogin(tr('Log in to ask a question', 'প্রশ্ন করতে লগইন করুন'), () => nav('/health/ask'));

  const list = (
    <div className="list-inner">
      <HealthTabs value="discussion" />
      <ListHeading
        title={tr('Health Discussion', 'স্বাস্থ্য আলোচনা')}
        onRefresh={() => q.refetch()}
        refreshing={q.isFetching && !q.isFetchingNextPage}
        action={two ? <Button small icon="edit_note" onClick={ask}>{tr('Ask a question', 'প্রশ্ন করুন')}</Button> : null}
      />
      <EmergencyNotice />
      {q.isPending ? (
        <SkeletonCards n={4} h={120} />
      ) : q.isError && !q.items.length ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !q.items.length ? (
        <EmptyState icon="forum_outline" title={tr('No questions yet', 'এখনো কোনো প্রশ্ন নেই')} text={tr('Ask a health question and verified doctors will answer.', 'স্বাস্থ্য নিয়ে প্রশ্ন করুন, যাচাই করা ডাক্তাররা উত্তর দেবেন।')} />
      ) : (
        <>
          {q.items.map((it) => (
            <QuestionCard key={it.id} q={it} selected={it.id === id} onClick={() => nav(`/health/question/${it.id}`)} />
          ))}
          {q.hasNextPage ? (
            <Button variant="text" block busy={q.isFetchingNextPage} onClick={() => q.fetchNextPage()}>{tr('Load more', 'আরও দেখুন')}</Button>
          ) : null}
        </>
      )}
      {!two ? <Fab icon="edit_note" onClick={ask}>{tr('Ask a question', 'প্রশ্ন করুন')}</Fab> : null}
    </div>
  );
  return (
    <ListDetail
      list={list}
      detail={id ? <QuestionDetail key={id} id={id} /> : null}
      placeholder={<EmptyState icon="forum_outline" title={tr('Pick a question', 'একটা প্রশ্ন বেছে নিন')} />}
    />
  );
}

function QuestionCard({ q, selected, onClick }: { q: Question; selected: boolean; onClick: () => void }) {
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
      <AuthorLine name={q.authorName} createdAt={q.createdAt} />
      <p className="t-body-l pre health-clamp4">{q.text}</p>
      <IconLine icon="forum_outline" color={q.answerCount > 0 ? 'var(--tertiary)' : undefined}>{answersText(q.answerCount)}</IconLine>
    </article>
  );
}

// ---------- একটা প্রশ্ন ----------
function QuestionDetail({ id }: { id: string }) {
  const nav = useNavigate();
  const qc = useQueryClient();
  const s = useSession();
  const me = useMe();
  const r = useCall<{ question: Question; answers: Answer[] }>('question', { id });
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useFlash();
  const [editingQ, setEditingQ] = useState(false);
  const [editingA, setEditingA] = useState<Answer | null>(null);
  const [deletingA, setDeletingA] = useState<Answer | null>(null);
  const [deletingQ, setDeletingQ] = useState(false);
  const [report, setReport] = useState<{ kind: 'question' | 'answer'; answerId?: string } | null>(null);
  const [delBusy, setDelBusy] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);

  const changed = () => {
    qc.invalidateQueries({ queryKey: ['question'] });
    qc.invalidateQueries({ queryKey: ['questions'] });
  };
  const back = () => nav('/health/discussion');
  const q = r.data?.question;
  const answers = r.data?.answers ?? [];
  const canAnswer = !!approvedBadge(me);
  const askReport = (t: { kind: 'question' | 'answer'; answerId?: string }) => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => setReport(t));

  const actions = q ? (
    <>
      {!q.mine ? <IconButton icon="flag" label={tr('Report', 'রিপোর্ট')} onClick={() => askReport({ kind: 'question' })} /> : null}
      {q.mine ? <IconButton icon="edit" label={tr('Edit', 'বদলান')} onClick={() => setEditingQ(true)} /> : null}
      {q.mine || me?.isAdmin ? <IconButton icon="delete" label={tr('Delete', 'মুছুন')} onClick={() => setDeletingQ(true)} /> : null}
    </>
  ) : null;

  async function sendAnswer() {
    if (!reply.trim()) return;
    setBusy(true);
    setErr(null);
    try {
      await call('answer', { id, text: reply.trim() });
      setReply('');
      changed();
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(false);
  }

  return (
    <DetailPane title={tr('Question', 'প্রশ্ন')} onBack={back} actions={actions}>
      {r.isPending ? (
        <Loading />
      ) : !q ? (
        <ErrorBox error={errorText(r.error)} onRetry={() => r.refetch()} />
      ) : (
        <>
          <div className="col">
            <AuthorLine name={q.authorName} createdAt={q.createdAt} />
            <p className="t-title-m pre" style={{ fontWeight: 500 }}>{q.text}</p>
            {q.editedAt ? <EditedNote /> : null}
            {q.mine ? (
              <Switch
                label={tr('Hide my name', 'আমার নাম দেখাবে না')}
                checked={!!q.anonymous}
                onChange={async (on) => {
                  try {
                    await call('edit_question', { id, text: q.text, anonymous: on });
                    changed();
                  } catch (e) {
                    setErr(errorText(e));
                  }
                }}
              />
            ) : null}
          </div>
          <div className="section-label health-section">
            {answers.length ? `${tr('Answers', 'উত্তর')} (${num(answers.length)})` : tr('No answers yet', 'এখনো উত্তর নেই')}
          </div>
          {answers.map((a) => (
            <div key={a.id} className="card col">
              <AuthorLine name={a.authorName} badge={a.badge} createdAt={a.createdAt} />
              <p className="t-body-l pre">{a.text}</p>
              {a.editedAt ? <EditedNote /> : null}
              <div className="row health-text-actions">
                {!a.mine ? <Button variant="text" small onClick={() => askReport({ kind: 'answer', answerId: a.id })}>{tr('Report', 'রিপোর্ট')}</Button> : null}
                {a.mine ? <Button variant="text" small onClick={() => setEditingA(a)}>{tr('Edit', 'বদলান')}</Button> : null}
                {a.mine || me?.isAdmin ? <Button variant="danger-text" small onClick={() => setDeletingA(a)}>{tr('Delete', 'মুছুন')}</Button> : null}
              </div>
            </div>
          ))}
          {canAnswer ? (
            <div className="col">
              <label className="field">
                <span className="label">{tr('Write an answer', 'উত্তর লিখুন')}</span>
                <textarea className="textarea" rows={4} maxLength={2000} value={reply} onChange={(e) => setReply(e.target.value)} />
              </label>
              <Button icon="send" block busy={busy} disabled={!reply.trim()} onClick={sendAnswer}>{tr('Answer', 'উত্তর দিন')}</Button>
            </div>
          ) : (
            <div className="row wrap">
              <p className="t-body-s muted grow">{tr('Only verified health professionals can answer.', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা উত্তর দিতে পারেন।')}</p>
              {s.guest ? <Button variant="text" small onClick={s.login}>{tr('Log in', 'লগইন')}</Button> : null}
            </div>
          )}
          <ErrorText>{err}</ErrorText>
          <InfoText>{info}</InfoText>
        </>
      )}

      {report ? (
        <ReportDialog
          onClose={() => setReport(null)}
          onConfirm={async () => {
            await call('report', { kind: report.kind, id, ...(report.answerId ? { answerId: report.answerId } : {}) });
            setReport(null);
            setInfo(tr('Reported. An admin will review it.', 'রিপোর্ট পাঠানো হয়েছে। অ্যাডমিন দেখবেন।'));
          }}
        />
      ) : null}
      {editingQ && q ? (
        <EditTextDialog
          title={tr('Edit question', 'প্রশ্ন বদলান')}
          initial={q.text}
          maxLen={1000}
          onClose={() => setEditingQ(false)}
          onSave={async (text) => {
            await call('edit_question', { id, text });
            setEditingQ(false);
            changed();
          }}
        />
      ) : null}
      {editingA ? (
        <EditTextDialog
          title={tr('Edit answer', 'উত্তর বদলান')}
          initial={editingA.text}
          maxLen={2000}
          onClose={() => setEditingA(null)}
          onSave={async (text) => {
            await call('edit_answer', { id, answerId: editingA.id, text });
            setEditingA(null);
            changed();
          }}
        />
      ) : null}
      {deletingA ? (
        <ConfirmDialog
          title={tr('Delete this answer?', 'উত্তরটা মুছবেন?')}
          text={!deletingA.mine ? tr("You're deleting someone else's answer as an admin. This is recorded.", 'অ্যাডমিন হিসেবে অন্যের উত্তর মুছছেন। এটা রেকর্ডে থাকবে।') : undefined}
          confirm={tr('Delete', 'মুছুন')}
          danger
          busy={delBusy}
          error={delErr}
          onClose={() => {
            setDeletingA(null);
            setDelErr(null);
          }}
          onConfirm={async () => {
            setDelBusy(true);
            try {
              await call('delete_answer', { id, answerId: deletingA.id });
              setDeletingA(null);
              changed();
            } catch (e) {
              setDelErr(errorText(e));
            }
            setDelBusy(false);
          }}
        />
      ) : null}
      {deletingQ ? (
        <ConfirmDialog
          title={tr('Delete this question?', 'প্রশ্নটা মুছবেন?')}
          confirm={tr('Delete', 'মুছুন')}
          danger
          busy={delBusy}
          error={delErr}
          onClose={() => {
            setDeletingQ(false);
            setDelErr(null);
          }}
          onConfirm={async () => {
            setDelBusy(true);
            try {
              await call('delete_question', { id });
              setDeletingQ(false);
              qc.invalidateQueries({ queryKey: ['questions'] });
              back();
            } catch (e) {
              setDelErr(errorText(e));
            }
            setDelBusy(false);
          }}
        />
      ) : null}
    </DetailPane>
  );
}

// ---------- প্রশ্ন করুন ----------
export function AskPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const s = useSession();
  const [text, setText] = useState('');
  const [anonymous, setAnonymous] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function post() {
    if (!text.trim()) {
      setErr(tr('Write your question', 'প্রশ্ন লিখুন'));
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const r = await call<{ id: string }>('ask', { text: text.trim(), anonymous });
      qc.invalidateQueries({ queryKey: ['questions'] });
      nav(r.id ? `/health/question/${r.id}` : '/health/discussion', { replace: true });
    } catch (e) {
      setErr(errorText(e));
      setBusy(false);
    }
  }

  return (
    <FormPage title={tr('Ask a question', 'প্রশ্ন করুন')} onBack={() => goBack(nav, '/health/discussion')}>
      <div className="field">
        <label htmlFor="health-q">{tr('Your question', 'আপনার প্রশ্ন')}</label>
        <textarea id="health-q" className="textarea" rows={6} maxLength={1000} value={text} onChange={(e) => setText(e.target.value)} autoFocus />
        <div className="row hint health-hint">
          <span className="grow">{tr('Answers are general advice, not a diagnosis.', 'উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।')}</span>
          <span className="num none">{num(text.length)}/{num(1000)}</span>
        </div>
      </div>
      <Check checked={anonymous} onChange={setAnonymous}>{tr('Hide my name', 'আমার নাম দেখাবে না')}</Check>
      <ErrorText>{err}</ErrorText>
      <Button icon="send" block busy={busy} onClick={() => s.requireLogin(tr('Log in to ask a question', 'প্রশ্ন করতে লগইন করুন'), post)}>
        {tr('Post', 'পোস্ট করুন')}
      </Button>
    </FormPage>
  );
}
