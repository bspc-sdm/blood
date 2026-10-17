// নোটিফিকেশন (অ্যাপের Inbox.kt): খুললেই সব পড়া ধরা হয়; চাপলে অ্যাপের মতো সংশ্লিষ্ট পাতা খোলে।
// বড় পর্দায় পাশে বিস্তারিত, মোবাইলে সরাসরি সেই পাতা।
import { useEffect, useRef } from 'react';
import { matchPath, useLocation, useNavigate } from 'react-router-dom';
import { Button, EmptyState, ErrorBox, IconButton, SkeletonCards, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { DetailPane, ListDetail, SubPageBar } from '@/shell/templates';
import { useMobile, usePageTitle, useTwoPane } from '@/shell/chrome';
import { call } from '@/lib/api';
import { tr, useLang } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { inboxIcon, inboxTarget, useAccountState } from './util';
import { useBack } from './nav';

interface InboxItem { id: string; title: string; body: string; data: Record<string, string>; read: boolean; createdAt: number }

function NoteIcon({ item }: { item: InboxItem }) {
  return (
    <span className={cx('acct-nicon', !item.read && 'new')}>
      <Icon name={inboxIcon(item.data?.type, item.data?.kind, item.title)} size={22} />
    </span>
  );
}

export function InboxPage() {
  useLang();
  const s = useSession();
  const nav = useNavigate();
  const { pathname } = useLocation();
  const two = useTwoPane();
  const mobile = useMobile();
  const title = tr('Notifications', 'নোটিফিকেশন');
  usePageTitle(title);
  const back = useBack('/');
  const { me, work } = useAccountState();
  const selId = matchPath('/inbox/:id', pathname)?.params.id ?? null;
  const q = useCall<{ items?: InboxItem[] }>('inbox', {}, { enabled: !s.guest, staleTime: 0 });
  const marked = useRef(0);

  // খুললেই সব পড়া (কোনগুলো নতুন ছিল তা এবার আলাদা রঙে দেখায়), আর ঘণ্টার সংখ্যা নতুন করে
  useEffect(() => {
    const items = q.data?.items;
    if (!items || marked.current === q.dataUpdatedAt) return;
    marked.current = q.dataUpdatedAt;
    if (items.some((x) => !x.read)) call('inbox_read').then(() => s.reloadMe(), () => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q.data, q.dataUpdatedAt]);

  if (s.ready && s.guest) {
    return (
      <>
        {mobile ? <SubPageBar title={title} onBack={back} /> : null}
        <EmptyState icon="notifications" title={tr('Log in to do this', 'এর জন্য লগইন করুন')}>
          <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
        </EmptyState>
      </>
    );
  }

  const items = q.data?.items ?? [];
  const sel = selId ? items.find((x) => x.id === selId) ?? null : null;
  const open = (it: InboxItem) => (two ? nav(`/inbox/${encodeURIComponent(it.id)}`) : nav(inboxTarget(it.data ?? {}, me, work)));
  const refresh = <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => q.refetch()} disabled={q.isFetching} />;

  const list = (
    <div className="list-inner">
      {!mobile ? <div className="row acct-listhead"><span className="grow" />{refresh}</div> : null}
      {q.isLoading ? (
        <SkeletonCards n={5} h={72} />
      ) : q.error && !items.length ? (
        <ErrorBox error={q.error.message} onRetry={() => q.refetch()} />
      ) : !items.length ? (
        <EmptyState icon="notifications" title={tr('No notifications yet', 'এখনো কোনো নোটিফিকেশন নেই')} />
      ) : (
        <ul className="acct-notes">
          {items.map((it) => (
            <li key={it.id}>
              <button type="button" className={cx('acct-note', selId === it.id && 'sel')} aria-current={selId === it.id ? 'true' : undefined} onClick={() => open(it)}>
                <NoteIcon item={it} />
                <span className="col gap-2 grow">
                  <span className={cx('t-title-s', it.read && 'acct-read')}>{it.title}</span>
                  {it.body ? <span className="t-body-m muted clamp2">{it.body}</span> : null}
                  <span className="t-label-s muted">{timeAgo(it.createdAt)}</span>
                </span>
                {!it.read ? <span className="acct-dot" aria-hidden="true" /> : null}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  let detail = null;
  if (selId) {
    detail = (
      <DetailPane title={sel?.title ?? title} backTo="/inbox">
        {q.isLoading ? (
          <SkeletonCards n={1} />
        ) : !sel ? (
          <EmptyState icon="notifications" title={tr('Not found', 'পাওয়া যায়নি')} />
        ) : (
          <>
            <div className="row gap-12">
              <NoteIcon item={{ ...sel, read: true }} />
              <span className="t-body-s muted">{formatDateTime(sel.createdAt)}</span>
            </div>
            {sel.body ? <p className="t-body-l pre">{sel.body}</p> : null}
            <div>
              <Button icon="chevron_right" onClick={() => nav(inboxTarget(sel.data ?? {}, me, work))}>{tr('Open', 'খুলুন')}</Button>
            </div>
          </>
        )}
      </DetailPane>
    );
  }

  return (
    <>
      {mobile && !selId ? <SubPageBar title={title} onBack={back} actions={refresh} /> : null}
      <ListDetail list={list} detail={detail} placeholder={<Icon name="notifications" size={64} className="acct-ph" />} />
    </>
  );
}
