// ডোনারের দিক থেকে রিকোয়েস্ট — অ্যাপের RequestDetailScreen (RequestScreens.kt)।
// অতিথি: আশেপাশের তালিকা থেকে পাওয়া তথ্য (নাম/ফোন/নোট ছাড়া); "রক্ত দেব" চাপলে লগইন আর রক্তদাতার তথ্য।
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Check, ConfirmDialog, Dialog, ErrorText, IconButton, InfoText, Loading, PhoneLine } from '@/ui/kit';
import { DetailPane } from '@/shell/templates';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { addCommitment, isDonor } from '@/lib/profile';
import { canDonate, notEligibleReason } from '@/lib/rules';
import { distanceKm } from '@/lib/geo';
import { TEST_MODE } from '@/lib/testmode';
import { KEY, api, dropCommitments, getRequest, myResponse, reportRequest, requesterContact, useInvalidate, type Nearby } from './data';
import { isActive, type BloodRequest } from './model';
import { Disclaimer, DonorStatusTag, Note, ProcessingCard, RequestInfo, ShareDialog, StatusCard } from './parts';
import { LoginNeeded } from './LoginNeeded';

/** অতিথির তালিকায় আগে আনা রিকোয়েস্ট (react-query ক্যাশ থেকে) */
function useGuestCached(id: string): Nearby | null {
  const qc = useQueryClient();
  const all = qc.getQueriesData<Nearby[]>({ queryKey: [KEY, 'nearby'] });
  for (const [, list] of all) {
    const hit = list?.find((x) => x.r.id === id);
    if (hit) return hit;
  }
  return null;
}

export function RequestDetail({ id }: { id: string }) {
  const s = useSession();
  const title = tr('Blood Request', 'রক্তের রিকোয়েস্ট');
  if (!s.ready) return <DetailPane title={title} backTo="/"><Loading /></DetailPane>;
  if (s.guest) return <GuestDetail id={id} title={title} />;
  return <MemberDetail id={id} title={title} />;
}

function GuestDetail({ id, title }: { id: string; title: string }) {
  const s = useSession();
  const hit = useGuestCached(id);
  const [sharing, setSharing] = useState(false);
  const respond = () => s.requireLogin(tr('Log in to respond to this request', 'সাড়া দিতে লগইন করুন'), () => {}, { donor: true });
  if (!hit) {
    return (
      <DetailPane title={title} backTo="/">
        <LoginNeeded icon="bloodtype" reason={tr('Log in to see and respond to this request', 'রিকোয়েস্টটা দেখতে ও সাড়া দিতে লগইন করুন')} />
      </DetailPane>
    );
  }
  const { r, km } = hit;
  const active = isActive(r);
  return (
    <DetailPane
      title={title}
      backTo="/"
      actions={
        <>
          {active ? <IconButton icon="share" label={tr('Share', 'শেয়ার')} onClick={() => setSharing(true)} /> : null}
          <IconButton icon="flag" label={tr('Report', 'রিপোর্ট')} onClick={() => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => {})} />
        </>
      }
    >
      <RequestInfo r={r} km={km} />
      {active ? <ShareCard r={r} onShare={() => setSharing(true)} /> : null}
      {active ? (
        <>
          <Button block icon="volunteer_activism" onClick={respond}>{tr("I'll donate", 'রক্ত দেব')}</Button>
          <Note>{tr('Your name and number will be shared with the requester.', 'রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন।')}</Note>
        </>
      ) : null}
      <Disclaimer />
      {sharing ? <ShareDialog r={r} onClose={() => setSharing(false)} /> : null}
    </DetailPane>
  );
}

/** সবার জন্য: কেউ রাজি হয়েছেন কি না, আর পরিচিতদের পাঠানোর বাটন */
function ShareCard({ r, onShare }: { r: BloodRequest; onShare: () => void }) {
  const noDonor = r.acceptedCount === 0 && !r.fulfilled;
  return (
    <section className={noDonor ? 'card tonal col' : 'card col'}>
      <DonorStatusTag r={r} />
      <p className="t-body-m">
        {noDonor
          ? tr('No one has agreed yet. Know someone with a matching group, even far away? Send it to them.', 'এখনো কেউ রাজি হননি। মিলে যায় এমন গ্রুপের কাউকে চেনেন, দূরে হলেও? তাঁকে পাঠান।')
          : tr('Know someone who could help? Share it.', 'সাহায্য করতে পারেন এমন কাউকে চেনেন? শেয়ার করুন।')}
      </p>
      <Button variant="tonal" block icon="share" onClick={onShare}>{tr('Send to people you know', 'পরিচিতদের পাঠান')}</Button>
    </section>
  );
}

function MemberDetail({ id, title }: { id: string; title: string }) {
  const s = useSession();
  const nav = useNavigate();
  const invalidate = useInvalidate();
  const uid = s.uid!;
  const profile = s.profile;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [checklist, setChecklist] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [sharing, setSharing] = useState(false);

  const q = useQuery({
    queryKey: [KEY, 'req', id, uid],
    queryFn: async () => {
      const r = await getRequest(id);
      const resp = r && r.requesterUid !== uid ? await myResponse(id, uid) : null;
      // নম্বর শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার পড়তে পারেন, আর রিকোয়েস্ট খোলা থাকলে
      const canSee = !!r && r.status === 'open' && (resp?.status === 'accepted' || resp?.status === 'donated');
      const contact = canSee ? await requesterContact(id).catch(() => null) : null;
      return { r, resp, contact };
    },
    staleTime: 5_000,
  });
  const r = q.data?.r ?? null;
  const resp = q.data?.resp ?? null;

  // আগে রাজি হওয়া রিকোয়েস্টও প্রসেসিং তালিকায় তোলা
  const accepted = !!r && resp?.status === 'accepted' && isActive(r);
  useEffect(() => {
    if (!accepted || !profile || profile.commitments.includes(id) || TEST_MODE) return;
    addCommitment(uid, id).then(() => s.reloadProfile()).catch(() => {});
  }, [accepted]); // eslint-disable-line react-hooks/exhaustive-deps

  async function act(f: () => Promise<unknown>, msg?: string) {
    setBusy(true);
    setError(null);
    try {
      await f();
      if (msg) setInfo(msg);
      await invalidate();
    } catch (e) {
      setError(errorText(e) || tr('Something went wrong', 'কিছু একটা সমস্যা হয়েছে'));
    } finally {
      setBusy(false);
    }
  }
  const donated = () => act(async () => {
    await api.updateResponse(id, 'donated');
    // তারিখ সার্ভার বসায় (বাংলাদেশের দিন ধরে)
    await s.reloadProfile();
  });

  const mine = !!r && r.requesterUid === uid;
  const active = !!r && isActive(r);
  const actions = r ? (
    <>
      {active ? <IconButton icon="share" label={tr('Share', 'শেয়ার')} onClick={() => setSharing(true)} /> : null}
      {!mine ? <IconButton icon="flag" label={tr('Report', 'রিপোর্ট')} onClick={() => setReporting(true)} /> : null}
    </>
  ) : null;

  let body;
  if (q.isLoading) body = <Loading />;
  else if (!r) body = <ErrorText>{q.error ? tr("Couldn't load", 'লোড হয়নি') : tr('Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি')}</ErrorText>;
  else {
    const km = profile?.lat != null && profile?.lng != null && r.lat ? distanceKm(profile.lat, profile.lng, r.lat, r.lng) : null;
    const group = profile?.bloodGroup ?? '';
    const compatible = canDonate(group, r.patientGroup);
    const notEligible = profile ? notEligibleReason(profile) : null;
    const eligible = notEligible == null;
    const donorOk = isDonor(profile);

    let main = null;
    if (mine) {
      main = <Button block onClick={() => nav(`/my-requests/${r.id}`)}>{tr('View responses', 'সাড়া দেওয়া ডোনারদের দেখুন')}</Button>;
    } else if (!resp && !active) {
      main = null;
    } else if (!resp && !donorOk) {
      // ডোনারের তথ্য নেই: আগে সেটা চেয়ে নেওয়া
      main = (
        <>
          <Button block icon="volunteer_activism" onClick={() => s.requireLogin(tr('Add your donor details to respond', 'সাড়া দিতে রক্তদাতার তথ্য দিন'), () => {}, { donor: true })}>
            {tr('I want to donate', 'রক্ত দিতে চাই')}
          </Button>
          <Note>{tr('You can still share this request.', 'চাইলে রিকোয়েস্টটা পরিচিতদের পাঠাতে পারেন।')}</Note>
        </>
      );
    } else if (!resp && !compatible) {
      main = (
        <Note>
          {tr(
            `Your group (${group}) can't donate to ${r.patientGroup}. You can share it with others.`,
            `আপনার গ্রুপ (${group}) থেকে ${r.patientGroup} রোগীকে রক্ত দেওয়া যায় না। পরিচিতদের জানাতে পারেন।`,
          )}
        </Note>
      );
    } else if (!resp && !eligible) {
      main = <Note>{notEligible}</Note>;
    } else if (!resp) {
      main = (
        <>
          <Button block icon="volunteer_activism" busy={busy} onClick={() => setChecklist(true)}>{tr("I'll donate", 'রক্ত দেব')}</Button>
          <Note>{tr('Your name and number will be shared with the requester.', 'রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন।')}</Note>
        </>
      );
    } else if (resp.status === 'accepted' && !active) {
      // রিকোয়েস্ট বন্ধ/মেয়াদ শেষ: আর যাওয়ার দরকার নেই; রক্ত দিয়ে থাকলে জানানো
      main = (
        <>
          <StatusCard icon="info">
            {tr("This request is closed — you don't need to go. If you already donated, tell us below.", 'রিকোয়েস্টটা বন্ধ হয়ে গেছে, আর যেতে হবে না। আগেই রক্ত দিয়ে থাকলে নিচে জানান।')}
          </StatusCard>
          <Button variant="outline" block icon="check_circle" busy={busy} onClick={donated}>{tr('I donated for this', 'এতে রক্ত দিয়েছি')}</Button>
        </>
      );
    } else if (resp.status === 'accepted') {
      const [icon, text] = resp.confirmed
        ? ['check_circle', tr('The requester confirmed you. Please go to the hospital on time.', 'রক্তপ্রার্থী আপনাকে নিশ্চিত করেছেন। সময়মতো হাসপাতালে যান।')]
        : r.fulfilled
          ? ['info', tr("Enough donors are confirmed for now. Thank you! You'll be told if you're needed.", 'আপাতত যথেষ্ট ডোনার নিশ্চিত হয়েছেন। ধন্যবাদ! দরকার হলে জানানো হবে।')]
          : ['schedule', tr('Waiting for the requester to confirm you.', 'রক্তপ্রার্থী আপনাকে নিশ্চিত করার অপেক্ষায়।')];
      const c = q.data?.contact;
      main = (
        <>
          <StatusCard icon={icon} tone={resp.confirmed ? 'good' : 'high'}>{text}</StatusCard>
          <section className="card blood-contact col">
            <h3 className="bold">{tr('Contact the requester', 'রক্তপ্রার্থীর সাথে যোগাযোগ করুন')}</h3>
            {c ? (
              <>
                <span>{c.name}</span>
                <PhoneLine phone={c.phone} big />
              </>
            ) : (
              <span>{tr("Couldn't load the number. Open this page again.", 'নম্বর লোড হয়নি, পাতাটা আবার খুলুন।')}</span>
            )}
          </section>
          <Button block icon="check_circle" busy={busy} onClick={donated}>{tr('I donated', 'রক্ত দিয়েছি')}</Button>
          <Button
            variant="text"
            block
            disabled={busy}
            onClick={() => act(async () => {
              await api.updateResponse(id, 'cancel');
              await dropCommitments(uid, [id]).catch(() => {});
              await s.reloadProfile();
            }, tr('Cancelled. The requester can no longer see your number.', 'বাতিল করা হয়েছে। রক্তপ্রার্থী এখন আর আপনার নম্বর দেখবেন না।'))}
          >
            {tr("I can't make it, cancel", 'আর পারছি না, বাতিল করুন')}
          </Button>
        </>
      );
    } else if (resp.status === 'donated') {
      main = <StatusCard icon="favorite" tone="good">{tr('Thank you! You helped save a life.', 'ধন্যবাদ! আপনি একজনের জীবন বাঁচাতে সাহায্য করেছেন।')}</StatusCard>;
    } else if (resp.status === 'declined') {
      main = <Note>{tr("The requester no longer needs you for this request — you don't need to go. Thank you!", 'রক্তপ্রার্থী এই রিকোয়েস্টে আপনাকে আর রাখেননি, আপনাকে যেতে হবে না। ধন্যবাদ!')}</Note>;
    } else {
      main = (
        <>
          <Note>{tr('You cancelled for this request.', 'আপনি এই রিকোয়েস্টে রক্ত দেওয়া বাতিল করেছেন।')}</Note>
          {active && compatible && eligible ? (
            <Button variant="outline" block disabled={busy} onClick={() => setChecklist(true)}>{tr('I can donate after all', 'আবার রক্ত দিতে চাই')}</Button>
          ) : null}
        </>
      );
    }

    body = (
      <>
        {resp?.status === 'accepted' && active ? <ProcessingCard r={r} /> : null}
        <RequestInfo r={r} km={km} />
        {active && !mine ? <ShareCard r={r} onShare={() => setSharing(true)} /> : null}
        {main}
        <ErrorText>{error}</ErrorText>
        <InfoText>{info}</InfoText>
        <Disclaimer />
      </>
    );
  }

  return (
    <DetailPane title={title} backTo="/" actions={actions}>
      {body}
      {checklist ? (
        <DonationChecklistDialog
          onDismiss={() => setChecklist(false)}
          onConfirm={() => {
            setChecklist(false);
            act(async () => {
              await api.respond(id);
              await s.reloadProfile();
            });
          }}
        />
      ) : null}
      {reporting ? (
        <ConfirmDialog
          title={tr('Report this request?', 'রিপোর্ট করবেন?')}
          text={tr('Report it if it looks fake or abusive.', 'ভুয়া বা অপব্যবহার মনে হলে রিপোর্ট করুন।')}
          confirm={tr('Report', 'রিপোর্ট করুন')}
          onClose={() => setReporting(false)}
          onConfirm={() => {
            setReporting(false);
            reportRequest(id, uid).then(
              () => setInfo(tr('Reported. Thank you.', 'রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।')),
              () => setError(tr("Couldn't send the report", 'রিপোর্ট পাঠানো যায়নি')),
            );
          }}
        />
      ) : null}
      {sharing && r ? <ShareDialog r={r} onClose={() => setSharing(false)} /> : null}
    </DetailPane>
  );
}

/** "রক্ত দেব" চাপার আগে স্বাস্থ্য যাচাই (উত্তর কোথাও সেভ হয় না) */
function DonationChecklistDialog({ onDismiss, onConfirm }: { onDismiss: () => void; onConfirm: () => void }) {
  const items = [
    tr('I feel well today and slept and ate well', 'আজ সুস্থ বোধ করছি, ভালো ঘুম ও খাওয়া হয়েছে'),
    tr('No fever, cold or antibiotics in the last 7 days', 'গত ৭ দিনে জ্বর, সর্দি-কাশি বা অ্যান্টিবায়োটিক নেই'),
    tr('No surgery, tattoo, piercing or transfusion in 6 months', 'গত ৬ মাসে অপারেশন, ট্যাটু, কান-নাক ফোঁড়ানো বা রক্ত নেওয়া হয়নি'),
    tr('No hepatitis B/C, HIV, malaria or heart disease', 'হেপাটাইটিস বি/সি, এইচআইভি, ম্যালেরিয়া বা হৃদরোগ নেই'),
    tr('Not pregnant or breastfeeding (women)', 'গর্ভবতী নই বা বুকের দুধ খাওয়াচ্ছি না (নারীদের জন্য)'),
  ];
  const [checked, setChecked] = useState<number[]>([]);
  return (
    <Dialog
      onClose={onDismiss}
      title={tr('Before you donate', 'রক্ত দেওয়ার আগে')}
      actions={
        <>
          <Button variant="text" onClick={onDismiss}>{tr('Cancel', 'বাতিল')}</Button>
          <Button disabled={checked.length !== items.length} onClick={onConfirm}>{tr("I'll donate", 'রক্ত দেব')}</Button>
        </>
      }
    >
      <div className="col gap-4">
        {items.map((t, i) => (
          <Check key={i} checked={checked.includes(i)} onChange={(v) => setChecked((c) => (v ? [...c, i] : c.filter((x) => x !== i)))}>
            {t}
          </Check>
        ))}
      </div>
    </Dialog>
  );
}

