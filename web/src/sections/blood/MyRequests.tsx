// রক্তপ্রার্থীর নিজের রিকোয়েস্ট — অ্যাপের MyRequestUi.kt (হোমের "আমার রিকোয়েস্ট") আর
// RequestScreens.kt (MyRequestsScreen, MyRequestDetailScreen)।
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Action, BloodGroupBadge, Button, ConfirmDialog, EmptyState, ErrorText, Fab, IconButton, InfoText, Loading, SkeletonCards, UrgentTag,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { ListDetail, DetailPane, SubPageBar } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { MAX_OPEN_REQUESTS } from '@/lib/rules';
import { api, myRequests, responses, useInvalidate, useMine, useMyRequest, KEY } from './data';
import {
  canReopen, counts, deadline, isActive, bagsLeft, neededText, willing, type BloodRequest, type DonorResponse,
} from './model';
import {
  AgreedLine, BADGE_SLOT, BagRow, CloseRequestDialog, Countdown, DonorRow, Note, RemovedNotice, RequestCard, RequestInfo,
  RequestProgress, ShareDialog, SlotRow, UndoNotice,
} from './parts';
import { LoginNeeded } from './LoginNeeded';

/** কাজ চালানো: চলার সময় busy, ভুল হলে বার্তা, শেষে নতুন করে আনা */
function useRunner() {
  const invalidate = useInvalidate();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function run(key: string, f: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await f();
      await invalidate();
      return true;
    } catch (e) {
      setError(errorText(e));
      return false;
    } finally {
      setBusy(null);
    }
  }
  return { busy, error, setError, run };
}

// ------------------------------------------------------------
// হোমে "আমার রিকোয়েস্ট": নিজের খোলা রিকোয়েস্টের অবস্থা
// ------------------------------------------------------------

export function MyRequestSection() {
  const { uid } = useSession();
  const nav = useNavigate();
  const { busy, error, run } = useRunner();
  const [info, setInfo] = useState<string | null>(null);
  const [closing, setClosing] = useState<{ r: BloodRequest; list: DonorResponse[] } | null>(null);
  const [removed, setRemoved] = useState<{ rid: string; d: DonorResponse } | null>(null);
  const [hidden, setHidden] = useState<string[]>([]);
  const [sharing, setSharing] = useState<BloodRequest | null>(null);
  const q = useQuery({
    queryKey: [KEY, 'homeMine', uid],
    queryFn: async () => {
      const mine = await myRequests(uid!);
      const now = Date.now();
      const recentClosed = mine.filter((r) => canReopen(r, now));
      const active = mine.filter((r) => isActive(r, now)).sort((a, b) => deadline(a) - deadline(b));
      const items = await Promise.all(active.map(async (r) => ({ r, list: await responses(r.id).catch(() => [] as DonorResponse[]) })));
      return { items, recentClosed };
    },
    enabled: !!uid,
    staleTime: 10_000,
  });
  if (!uid || !q.data) return null;
  const reopenable = q.data.recentClosed.filter((r) => !hidden.includes(r.id));
  if (!q.data.items.length && !reopenable.length) return null;

  return (
    <section className="blood-mine col" aria-label={tr('My Request', 'আমার রিকোয়েস্ট')}>
      <div className="row gap-8">
        <Icon name="bloodtype" size={20} className="c-primary" />
        <h2 className="t-title-m bold">{tr('My Request', 'আমার রিকোয়েস্ট')}</h2>
      </div>
      <ErrorText>{error}</ErrorText>
      <InfoText>{info}</InfoText>
      {reopenable.map((c) => (
        <UndoNotice
          key={c.id}
          text={tr(`Request at ${c.hospital} closed`, `${c.hospital}-এর রিকোয়েস্ট বন্ধ হয়েছে`)}
          undo={tr('Open again', 'আবার খুলুন')}
          busy={busy === c.id}
          onUndo={() => run(c.id, () => api.reopen(c.id))}
          onDismiss={() => setHidden((h) => [...h, c.id])}
        />
      ))}
      {removed ? (
        <RemovedNotice
          name={removed.d.donorName}
          busy={busy === removed.rid}
          onUndo={async () => {
            if (await run(removed.rid, () => api.updateResponse(removed.rid, 'restore', removed.d.donorUid))) setRemoved(null);
          }}
          onDismiss={() => setRemoved(null)}
        />
      ) : null}
      {q.data.items.map(({ r, list }) => (
        <MyRequestCard
          key={r.id}
          r={r}
          list={list}
          busyId={busy}
          onShare={() => setSharing(r)}
          onNotify={() => run(r.id, () => api.notify(r.id))}
          onNotifyAgain={() =>
            run(r.id, async () => {
              const n = await api.notify(r.id, true);
              setInfo(n > 0
                ? tr(`${num(n)} more donors were notified`, `আরও ${num(n)} জন ডোনারকে জানানো হয়েছে`)
                : tr('No new donors found nearby right now. Please share the request.', 'এই মুহূর্তে কাছে নতুন ডোনার পাওয়া যায়নি। রিকোয়েস্টটা শেয়ার করুন।'));
            })}
          onConfirm={(d) => run(r.id, () => api.confirmDonor(r.id, d.donorUid))}
          onRemove={async (d) => {
            if (await run(r.id, () => api.updateResponse(r.id, 'remove', d.donorUid))) setRemoved({ rid: r.id, d });
          }}
          onOpen={() => nav(`/my-requests/${r.id}`)}
          onClose={() => setClosing({ r, list })}
        />
      ))}
      {closing ? (
        <CloseRequestDialog
          bags={closing.r.bags}
          already={closing.r.bagsReceived}
          list={closing.list}
          onDismiss={() => setClosing(null)}
          onClose={(chosen, got) => {
            const id = closing.r.id;
            setClosing(null);
            run(id, () => api.closeRequest(id, chosen, got));
          }}
        />
      ) : null}
      {sharing ? <ShareDialog r={sharing} onClose={() => setSharing(null)} /> : null}
    </section>
  );
}

/** "আমার রিকোয়েস্ট"-এর একটা কার্ড: অবস্থা, রাজি ডোনার (৩ জন পর্যন্ত), নিচে ছোট বাটন */
function MyRequestCard({
  r, list, busyId, onShare, onNotify, onNotifyAgain, onConfirm, onRemove, onOpen, onClose,
}: {
  r: BloodRequest; list: DonorResponse[]; busyId: string | null; onShare: () => void; onNotify: () => void; onNotifyAgain: () => void;
  onConfirm: (d: DonorResponse) => void; onRemove: (d: DonorResponse) => void; onOpen: () => void; onClose: () => void;
}) {
  const busy = busyId === r.id;
  const w = willing(list);
  const sorted = [...w].sort((a, b) => Number(counts(b)) - Number(counts(a)));
  return (
    <article className="card blood-mycard col">
      <div className="row gap-12">
        <BloodGroupBadge group={r.patientGroup} size={BADGE_SLOT} />
        <div className="col gap-2 grow">
          <div className="row gap-6">
            <span className="semi ellipsis">{r.hospital}</span>
            {r.urgent ? <UrgentTag /> : null}
          </div>
          <span className="t-body-s muted">{neededText(r)}</span>
        </div>
        <IconButton icon="share" label={tr('Share', 'শেয়ার')} onClick={onShare} className="c-primary" />
      </div>
      <div className="col gap-6">
        <Countdown target={deadline(r)} slot={BADGE_SLOT} />
        {r.notified ? (
          <>
            <SlotRow icon="notifications_active" muted>
              <span className="t-body-m muted">{tr(`${num(r.notifiedCount)} donors notified`, `${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে`)}</span>
            </SlotRow>
            <NotifyAgainRow r={r} busy={busy} disabled={busyId != null && !busy} onNotifyAgain={onNotifyAgain} />
          </>
        ) : (
          <SlotRow>
            <Action icon="notifications" primary busy={busy} disabled={busyId != null && !busy} onClick={onNotify}>
              {tr('Notify nearby donors', 'আশেপাশের ডোনারদের জানান')}
            </Action>
          </SlotRow>
        )}
        <AgreedLine list={list} slot={BADGE_SLOT} />
      </div>
      <div style={{ paddingLeft: BADGE_SLOT + 12 }}><BagRow r={r} list={list} /></div>
      {w.length ? (
        <>
          <hr className="divider" />
          {sorted.slice(0, 3).map((d) => (
            <DonorRow key={d.donorUid} d={d} active busy={busy} onConfirm={() => onConfirm(d)} onRemove={() => onRemove(d)} canConfirm={r.confirmedCount < r.bags} />
          ))}
          {w.length > 3 ? (
            <Button variant="text" small onClick={onOpen} style={{ alignSelf: 'flex-start' }}>{tr(`See all ${num(w.length)}`, `সবাই দেখুন (${num(w.length)})`)}</Button>
          ) : null}
        </>
      ) : null}
      <hr className="divider" />
      <div className="row gap-8 blood-actions">
        <Action icon="list_alt" onClick={onOpen}>{tr('Details', 'বিস্তারিত')}</Action>
        <Action icon="check_circle" primary={w.length > 0} onClick={onClose}>{tr('Got blood', 'রক্ত পেয়েছি')}</Action>
      </div>
    </article>
  );
}

/** "আবার জানান": মোট ৩ বার, দুইবারের মাঝে ৬ ঘণ্টা */
function NotifyAgainRow({ r, busy, disabled, onNotifyAgain }: { r: BloodRequest; busy: boolean; disabled: boolean; onNotifyAgain: () => void }) {
  if (!isActive(r) || bagsLeft(r) <= 0 || r.notifyRounds < 1 || r.notifyRounds > 2) return null;
  const waitMs = r.lastNotifiedMillis + 6 * 3600 * 1000 - Date.now();
  return (
    <SlotRow>
      {waitMs > 0 ? (
        <span className="t-body-s muted">
          {tr(
            `${num(bagsLeft(r))} bag(s) still needed · you can notify more donors in ${num(Math.ceil(waitMs / 3600000))}h`,
            `আরও ${num(bagsLeft(r))} ব্যাগ লাগবে · ${num(Math.ceil(waitMs / 3600000))} ঘণ্টা পর আরও ডোনারকে জানাতে পারবেন`,
          )}
        </span>
      ) : (
        <Action icon="notifications" primary busy={busy} disabled={disabled} onClick={onNotifyAgain}>{tr('Notify', 'জানান')}</Action>
      )}
    </SlotRow>
  );
}

// ------------------------------------------------------------
// /my-requests আর /my-requests/:id
// ------------------------------------------------------------

/** অ্যাপের ভেতরে আগের পাতা থাকলে সেখানে, নইলে হোমে */
function useBack(fallback: string) {
  const nav = useNavigate();
  return () => ((window.history.state?.idx ?? 0) > 0 ? nav(-1) : nav(fallback));
}

export function MyRequestsPage({ selectedId }: { selectedId: string | null }) {
  const s = useSession();
  const nav = useNavigate();
  const two = useTwoPane();
  const back = useBack('/profile');
  usePageTitle(tr('My Requests', 'আমার রিকোয়েস্ট'));
  const mine = useMine();
  if (!s.ready) return <Loading />;
  if (s.guest) {
    return (
      <>
        <SubPageBar title={tr('My Requests', 'আমার রিকোয়েস্ট')} onBack={back} />
        <LoginNeeded reason={tr('Log in to see your requests', 'আপনার রিকোয়েস্ট দেখতে লগইন করুন')} />
      </>
    );
  }
  const list = [...(mine.data ?? [])].sort((a, b) => Number(isActive(b)) - Number(isActive(a)) || b.serverCreatedMillis - a.serverCreatedMillis);
  const activeCount = list.filter((r) => isActive(r)).length;
  const canAdd = !mine.isLoading && activeCount < MAX_OPEN_REQUESTS;
  const newRequest = () => nav('/request/new');
  const listPane = (
    <div className="list-inner">
      {canAdd ? (
        <div className="row blood-wide-only" style={{ justifyContent: 'flex-end' }}>
          <Button icon="add" square onClick={newRequest}>{tr('Request', 'রিকোয়েস্ট')}</Button>
        </div>
      ) : null}
      {activeCount >= MAX_OPEN_REQUESTS ? (
        <p className="t-body-s muted">{tr(`You can keep up to ${num(MAX_OPEN_REQUESTS)} open requests.`, `একসাথে সর্বোচ্চ ${num(MAX_OPEN_REQUESTS)}টা খোলা রিকোয়েস্ট রাখা যায়।`)}</p>
      ) : null}
      {mine.isLoading ? (
        <SkeletonCards n={3} />
      ) : mine.error ? (
        <ErrorText>{tr("Couldn't load", 'লোড হয়নি')}</ErrorText>
      ) : !list.length ? (
        <EmptyState icon="list_alt" title={tr('No requests yet', 'এখনো কোনো রিকোয়েস্ট দেননি')} />
      ) : (
        list.map((r) => <RequestCard key={r.id} r={r} selected={r.id === selectedId} onClick={() => nav(`/my-requests/${r.id}`)} />)
      )}
      {canAdd ? <div className="blood-fab"><Fab icon="add" onClick={newRequest}>{tr('Request', 'রিকোয়েস্ট')}</Fab></div> : null}
    </div>
  );
  return (
    <>
      {!selectedId || two ? <SubPageBar title={tr('My Requests', 'আমার রিকোয়েস্ট')} onBack={back} /> : null}
      <ListDetail
        list={listPane}
        detail={selectedId ? <MyRequestDetail key={selectedId} id={selectedId} /> : null}
        placeholder={<EmptyState icon="list_alt" title={tr('Choose a request', 'একটা রিকোয়েস্ট বেছে নিন')} />}
      />
    </>
  );
}

export function MyRequestDetail({ id }: { id: string }) {
  const nav = useNavigate();
  const loc = useLocation();
  const q = useMyRequest(id);
  const { busy, error, setError, run } = useRunner();
  const [info, setInfo] = useState<string | null>(() => (loc.state as { info?: string } | null)?.info ?? null);
  const [closing, setClosing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [delBusy, setDelBusy] = useState(false);
  const [removed, setRemoved] = useState<DonorResponse | null>(null);
  const [sharing, setSharing] = useState(false);
  const r = q.data?.r ?? null;
  const list = q.data?.responses ?? [];
  const active = !!r && isActive(r);
  const w = willing(list);
  const isBusy = busy != null;

  const actions = (
    <>
      {r && active ? <IconButton icon="share" label={tr('Share', 'শেয়ার')} onClick={() => setSharing(true)} /> : null}
      {r && active ? <IconButton icon="edit" label={tr('Edit', 'এডিট')} disabled={isBusy} onClick={() => nav(`/my-requests/${id}/edit`)} /> : null}
      <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => q.refetch()} />
      {r ? <IconButton icon="delete" label={tr('Delete', 'মুছে ফেলুন')} disabled={isBusy} onClick={() => setDeleting(true)} /> : null}
    </>
  );

  let body;
  if (q.isLoading) body = <Loading />;
  else if (!r) body = <ErrorText>{q.error ? tr("Couldn't load", 'লোড হয়নি') : tr('Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি')}</ErrorText>;
  else {
    const sorted = [...w].sort((a, b) => Number(counts(b)) - Number(counts(a)));
    body = (
      <>
        <RequestInfo r={r} />
        {active && r.notified ? (
          <div className="icon-line">
            <Icon name="notifications_active" />
            <span>
              {r.notifiedCount > 0
                ? tr(`${num(r.notifiedCount)} nearby donors notified`, `আশেপাশের ${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে`)
                : tr('No donors with notifications on nearby yet', 'এখনো আশেপাশে নোটিফিকেশন চালু থাকা ডোনার পাওয়া যায়নি')}
            </span>
          </div>
        ) : null}
        {active && !r.notified ? (
          <Action
            icon="notifications"
            primary
            busy={busy === 'notify'}
            disabled={isBusy}
            style={{ alignSelf: 'flex-start' }}
            onClick={() => run('notify', async () => {
              const n = await api.notify(r.id);
              setInfo(tr(`${num(n)} donors notified`, `${num(n)} জন ডোনারকে জানানো হয়েছে`));
            })}
          >
            {tr('Notify nearby donors', 'আশেপাশের ডোনারদের জানান')}
          </Action>
        ) : null}
        <InfoText>{info}</InfoText>
        {active ? <RequestProgress r={r} list={list} /> : null}
        <h3 className="t-title-s c-primary blood-sectitle">{tr(`Donors (${num(w.length)})`, `রাজি হয়েছেন (${num(w.length)})`)}</h3>
        {!w.length ? (
          <Note>{tr('No one has responded yet.', 'এখনো কেউ সাড়া দেননি।')}</Note>
        ) : active ? (
          <Note>{tr("Confirm the donors you'll take. Others are told when enough are confirmed.", 'যাঁদের নেবেন তাঁদের নিশ্চিত করুন। যথেষ্ট নিশ্চিত হলে বাকিদের জানানো হয়।')}</Note>
        ) : null}
        {removed ? (
          <RemovedNotice
            name={removed.donorName}
            busy={busy === 'restore'}
            onUndo={async () => {
              if (await run('restore', () => api.updateResponse(r.id, 'restore', removed.donorUid))) setRemoved(null);
            }}
            onDismiss={() => setRemoved(null)}
          />
        ) : null}
        {sorted.map((d) => (
          <div key={d.donorUid} className="card" style={{ padding: 12 }}>
            <DonorRow
              d={d}
              active={active}
              busy={isBusy}
              canConfirm={r.confirmedCount < r.bags}
              onConfirm={() => run('confirm', () => api.confirmDonor(r.id, d.donorUid))}
              onRemove={async () => {
                if (await run('remove', () => api.updateResponse(r.id, 'remove', d.donorUid))) setRemoved(d);
              }}
            />
          </div>
        ))}
        <ErrorText>{error}</ErrorText>
        {canReopen(r) ? (
          <Action icon="refresh" busy={busy === 'reopen'} style={{ alignSelf: 'flex-start' }} onClick={() => run('reopen', () => api.reopen(r.id))}>
            {tr('Closed by mistake? Open again', 'ভুল করে বন্ধ? আবার খুলুন')}
          </Action>
        ) : null}
        {active ? (
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <Action icon="check_circle" primary disabled={isBusy} onClick={() => setClosing(true)}>{tr('Got blood, close', 'রক্ত পেয়েছি, বন্ধ করুন')}</Action>
          </div>
        ) : null}
      </>
    );
  }

  const willingCount = w.length;
  return (
    <DetailPane title={tr('My Request', 'আমার রিকোয়েস্ট')} backTo="/my-requests" actions={actions}>
      {body}
      {deleting ? (
        <ConfirmDialog
          title={tr('Delete this request?', 'রিকোয়েস্টটা মুছে ফেলবেন?')}
          text={
            active && willingCount > 0
              ? tr(
                `It will be removed for everyone. The ${num(willingCount)} donor(s) who agreed will be told they don't need to come.`,
                `সবার কাছ থেকে সরে যাবে। রাজি হওয়া ${num(willingCount)} জন ডোনারকে জানানো হবে যে আর আসার দরকার নেই।`,
              )
              : active
                ? tr('It will be removed for everyone and donors will stop seeing it.', 'সবার কাছ থেকে সরে যাবে, ডোনাররা আর দেখবেন না।')
                : tr("It will be removed from your list. This can't be undone.", 'আপনার তালিকা থেকে মুছে যাবে। আর ফেরানো যাবে না।')
          }
          confirm={tr('Delete', 'মুছে ফেলুন')}
          danger
          busy={delBusy}
          onClose={() => setDeleting(false)}
          onConfirm={async () => {
            setDelBusy(true);
            setError(null);
            if (await run('delete', () => api.deleteRequest(id))) {
              setDeleting(false);
              nav('/my-requests', { replace: true });
            } else setDeleting(false);
            setDelBusy(false);
          }}
        />
      ) : null}
      {closing && r ? (
        <CloseRequestDialog
          bags={r.bags}
          already={r.bagsReceived}
          list={list}
          onDismiss={() => setClosing(false)}
          onClose={(chosen, got) => {
            setClosing(false);
            run('close', () => api.closeRequest(id, chosen, got));
          }}
        />
      ) : null}
      {sharing && r ? <ShareDialog r={r} onClose={() => setSharing(false)} /> : null}
    </DetailPane>
  );
}
