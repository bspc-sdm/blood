// রক্তের অংশের ছোট উপাদান — অ্যাপের Common.kt (RequestCard, DonorStatusTag, Countdown, ProcessingCard, RequestInfo)
// আর MyRequestUi.kt (BagRow, AgreedLine, DonorRow, UndoNotice, CloseRequestDialog)-এর ওয়েব রূপ।
import { useState, type ReactNode } from 'react';
import {
  Action, BloodBag, BloodGroupBadge, Button, Check, ConfirmDialog, Dialog, IconButton, IconLine, PhoneLine, Tag, UrgentTag, cx, toast, useNow,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { num, tr } from '@/lib/i18n';
import { formatDuration, formatKm } from '@/lib/format';
import { districtLabel } from '@/lib/geo';
import {
  bagsLeft, counts, deadline, isActive, needsFare, neededText, shareLink, shareText, willing,
  type BloodRequest, type DonorResponse,
} from './model';

/** কার্ডের বাম পাশের রক্তের গ্রুপের গোল যত চওড়া; নিচের আইকনগুলো এর মাঝ বরাবর */
export const BADGE_SLOT = 44;

/** কার্ড চাপলে খোলে (কিবোর্ডেও) */
function clickProps(onClick?: () => void) {
  if (!onClick) return {};
  return {
    role: 'button' as const,
    tabIndex: 0,
    onClick,
    onKeyDown: (e: React.KeyboardEvent) => {
      if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
        e.preventDefault();
        onClick();
      }
    },
  };
}

/** রিকোয়েস্টে ডোনারের অবস্থা: এখনো কেউ নেই / কতজন রাজি / পাওয়া গেছে */
export function DonorStatusTag({ r }: { r: BloodRequest }) {
  if (r.fulfilled) return <Tag tone="good">{tr('Donors found', 'ডোনার পাওয়া গেছে')}</Tag>;
  if (r.acceptedCount > 0) return <Tag tone="info">{tr(`${num(r.acceptedCount)} agreed`, `${num(r.acceptedCount)} জন রাজি`)}</Tag>;
  return <Tag tone="error">{tr('No donor yet', 'এখনো কোনো ডোনার নেই')}</Tag>;
}

export const ClosedTag = () => <Tag>{tr('Closed', 'বন্ধ')}</Tag>;

export function RequestCard({ r, km, onClick, selected }: { r: BloodRequest; km?: number | null; onClick: () => void; selected?: boolean }) {
  const active = isActive(r);
  const left = bagsLeft(r);
  const partial = active && left >= 1 && left < r.bags;
  const shown = partial ? left : r.bags;
  return (
    <article className={cx('card click blood-rc', selected && 'sel')} aria-pressed={selected} {...clickProps(onClick)}>
      <BloodGroupBadge group={r.patientGroup} />
      <div className="col gap-4 grow">
        <div className="row gap-8">
          <span className="t-title-m ellipsis">{r.hospital}</span>
          {r.urgent && active ? <UrgentTag /> : null}
        </div>
        <IconLine icon="location_on">{[districtLabel(r.placeName), km != null ? formatKm(km) : null].filter(Boolean).join(' · ')}</IconLine>
        <IconLine icon="schedule">{neededText(r)}</IconLine>
        {r.travelFare ? <IconLine icon="directions_run">{tr('Travel cost paid', 'যাতায়াত খরচ দেওয়া হবে')}</IconLine> : null}
        {active ? <DonorStatusTag r={r} /> : null}
      </div>
      <div className="blood-bagcol">
        <BloodBag
          n={shown}
          filled={active}
          label={partial ? tr(`${left} of ${r.bags} bag(s) still needed`, `${num(r.bags)} ব্যাগের ${num(left)}টা এখনো দরকার`) : tr(`${r.bags} bag(s) needed`, `${num(r.bags)} ব্যাগ দরকার`)}
        />
        {partial ? <span className="t-label-s c-error">{tr('left', 'বাকি')}</span> : null}
        {!active ? <ClosedTag /> : null}
      </div>
    </article>
  );
}

/** রক্ত লাগার সময় পর্যন্ত প্রতি সেকেন্ডে কমতে থাকা কাউন্টডাউন */
export function Countdown({ target, slot }: { target: number; slot?: number }) {
  const now = useNow();
  const left = target - now;
  return (
    <div className="row blood-count" style={slot ? { gap: 12 } : undefined}>
      <span className="blood-slot" style={slot ? { width: slot } : undefined}><Icon name="schedule" size={20} /></span>
      <span className="t-title-m bold c-primary num">{left > 0 ? formatDuration(left) : tr("Time's up", 'সময় হয়ে গেছে')}</span>
      {left > 0 ? <span className="t-body-m muted">{tr('left', 'বাকি')}</span> : null}
    </div>
  );
}

/** বামে আইকন (গ্রুপের গোলের নিচে মাঝ বরাবর), ডানে লেখা */
export function SlotRow({ icon, slot = BADGE_SLOT, muted, children }: { icon?: string; slot?: number; muted?: boolean; children: ReactNode }) {
  return (
    <div className="row" style={{ gap: 12 }}>
      <span className={cx('blood-slot', muted && 'muted')} style={{ width: slot }}>{icon ? <Icon name={icon} size={20} /> : null}</span>
      <div className="grow">{children}</div>
    </div>
  );
}

/** ডোনার যে রিকোয়েস্টে রাজি হয়েছেন: "প্রসেসিং" কার্ড */
export function ProcessingCard({
  r, onClick, onDonated, onNotDonated, busy, selected,
}: { r: BloodRequest; onClick?: () => void; onDonated?: () => void; onNotDonated?: () => void; busy?: boolean; selected?: boolean }) {
  const active = isActive(r);
  return (
    <article className={cx('card blood-proc', onClick && 'click', selected && 'sel')} {...clickProps(onClick)}>
      <div className="row gap-6 c-primary">
        <Icon name="sync" size={18} />
        <span className="t-label-l bold">{tr('Processing', 'প্রসেসিং')}</span>
      </div>
      <div className="row gap-12">
        <BloodGroupBadge group={r.patientGroup} size={40} />
        <div className="col gap-2 grow">
          <span className="semi ellipsis">{r.hospital}</span>
          <span className="t-body-s">{neededText(r)}</span>
        </div>
      </div>
      {active ? (
        <Countdown target={deadline(r)} />
      ) : (
        <>
          <p className="semi">{tr('This request has ended. Did you donate?', 'রিকোয়েস্টটা শেষ হয়েছে। আপনি কি রক্ত দিয়েছেন?')}</p>
          <div className="row gap-8" onClick={(e) => e.stopPropagation()}>
            {onDonated ? <Action icon="check_circle" primary busy={busy} onClick={onDonated}>{tr('I donated', 'রক্ত দিয়েছি')}</Action> : null}
            {onNotDonated ? <Action icon="close" disabled={busy} onClick={onNotDonated}>{tr("I didn't", 'দিইনি')}</Action> : null}
          </div>
        </>
      )}
    </article>
  );
}

/** আইকন + ছোট শিরোনাম + মান (অ্যাপের InfoLine) */
export function InfoLine({ icon, label, children }: { icon: string; label: string; children: ReactNode }) {
  return (
    <div className="row top gap-12">
      <Icon name={icon} size={20} className="blood-info-ic" />
      <div className="col gap-2 grow">
        <span className="t-label-m muted">{label}</span>
        <span className="t-body-l pre">{children}</span>
      </div>
    </div>
  );
}

/** রিকোয়েস্টের পুরো তথ্য (অ্যাপের RequestInfo) */
export function RequestInfo({ r, km }: { r: BloodRequest; km?: number | null }) {
  const active = isActive(r);
  return (
    <section className="card blood-info">
      <div className="row gap-16">
        <BloodGroupBadge group={r.patientGroup} size={64} />
        <div className="col gap-4">
          <h3 className="t-title-l">{tr(`${num(r.bags)} bag${r.bags > 1 ? 's' : ''} needed`, `${num(r.bags)} ব্যাগ রক্ত দরকার`)}</h3>
          {r.urgent && active ? <UrgentTag /> : null}
          {!active ? <ClosedTag /> : null}
        </div>
      </div>
      <InfoLine icon="local_hospital" label={tr('Hospital', 'হাসপাতাল')}>{r.hospital}</InfoLine>
      <InfoLine icon="location_on" label={tr('Area', 'এলাকা')}>
        {[districtLabel(r.placeName), km != null ? formatKm(km) : null].filter(Boolean).join(' · ')}
      </InfoLine>
      <InfoLine icon="calendar_month" label={tr('Needed by', 'কবে লাগবে')}>{neededText(r)}</InfoLine>
      <InfoLine icon="directions_run" label={tr('Travel cost', 'যাতায়াত খরচ')}>
        {r.travelFare ? tr('Will be paid', 'দেওয়া হবে') : tr('Not provided', 'দেওয়া হবে না')}
      </InfoLine>
      {r.note.trim() ? <InfoLine icon="info" label={tr('Note', 'অতিরিক্ত তথ্য')}>{r.note}</InfoLine> : null}
      {r.requesterName ? <InfoLine icon="person_outline" label={tr('Requested by', 'রিকোয়েস্ট দিয়েছেন')}>{r.requesterName}</InfoLine> : null}
    </section>
  );
}

export function Disclaimer() {
  return <p className="t-body-s muted">{tr('The blood bank makes the final decision after cross-matching.', 'রক্ত দেওয়া যাবে কি না, চূড়ান্তভাবে ব্লাড ব্যাংক ক্রসম্যাচ করে ঠিক করবে।')}</p>;
}

export const Note = ({ children }: { children: ReactNode }) => <p className="t-body-m muted">{children}</p>;

/** রঙিন ছোট বার্তার কার্ড (আইকন + মোটা লেখা) */
export function StatusCard({ icon, tone, children }: { icon: string; tone?: 'good' | 'high'; children: ReactNode }) {
  return (
    <div className={cx('card blood-status', tone === 'good' ? 'good' : 'high')}>
      <Icon name={icon} size={24} className="c-primary none" />
      <span className="semi">{children}</span>
    </div>
  );
}

// ---------- রাজি ডোনার, ব্যাগ ----------

/** "৩ জন রাজি · ১ জন নিশ্চিত" */
export function AgreedLine({ list, slot }: { list: DonorResponse[]; slot?: number }) {
  const w = willing(list);
  return (
    <SlotRow icon="group_round" slot={slot ?? 20}>
      <span className="t-title-s">
        {tr(`${num(w.length)} agreed · ${num(w.filter(counts).length)} confirmed`, `${num(w.length)} জন রাজি · ${num(w.filter(counts).length)} জন নিশ্চিত`)}
      </span>
    </SlotRow>
  );
}

/** ১, ২, ৩… করে প্রতিটা ব্যাগ — যতগুলো পূরণ হয়েছে সেগুলো ভরাট */
export function BagRow({ r, list }: { r: BloodRequest; list: DonorResponse[] }) {
  const done = Math.min(Math.max(willing(list).filter(counts).length, r.bagsReceived), r.bags);
  return (
    <div className="row gap-8 blood-bags">
      {Array.from({ length: r.bags }, (_, i) => (
        <BloodBag
          key={i}
          n={i + 1}
          filled={i + 1 <= done}
          label={i + 1 <= done ? tr(`Bag ${i + 1} arranged`, `${num(i + 1)} নম্বর ব্যাগ পূরণ`) : tr(`Bag ${i + 1} needed`, `${num(i + 1)} নম্বর ব্যাগ বাকি`)}
        />
      ))}
    </div>
  );
}

export function RequestProgress({ r, list }: { r: BloodRequest; list: DonorResponse[] }) {
  return (
    <div className="col gap-8">
      <AgreedLine list={list} />
      <BagRow r={r} list={list} />
    </div>
  );
}

/** রাজি হওয়া এক ডোনার: নাম, দূরত্ব, ফোন, নিশ্চিত / বাদ */
export function DonorRow({
  d, active, busy, onConfirm, onRemove, canConfirm = true,
}: { d: DonorResponse; active: boolean; busy: boolean; onConfirm: () => void; onRemove: () => void; canConfirm?: boolean }) {
  const [askRemove, setAskRemove] = useState(false);
  const fare = needsFare(d);
  const meta = [d.distanceKm != null ? num(d.distanceKm) + tr(' km away', ' কিমি দূরে') : null, fare ? tr('needs travel cost', 'যাতায়াত খরচ লাগবে') : null].filter(Boolean).join(' · ');
  return (
    <div className="col gap-6 blood-donor">
      <div className="row" style={{ gap: 10 }}>
        <BloodGroupBadge group={d.donorGroup} size={36} />
        <div className="col gap-2 grow">
          <div className="row gap-8">
            <span className="semi ellipsis">{d.donorName}</span>
            {d.status === 'donated' ? <Tag tone="good">{tr('Donated', 'রক্ত দিয়েছেন')}</Tag> : d.confirmed ? <Tag tone="good">{tr('Confirmed', 'নিশ্চিত')}</Tag> : null}
          </div>
          {meta ? <span className={cx('t-body-s', fare ? 'c-primary' : 'muted')}>{meta}</span> : null}
        </div>
      </div>
      <div className="blood-indent"><PhoneLine phone={d.donorPhone} /></div>
      {active && d.status === 'accepted' ? (
        <div className="row gap-8 blood-indent">
          {!d.confirmed && canConfirm ? <Action icon="check_circle" busy={busy} onClick={onConfirm}>{tr('Confirm', 'নিশ্চিত করুন')}</Action> : null}
          <Action icon="close" disabled={busy} onClick={() => setAskRemove(true)}>{tr('Remove', 'বাদ দিন')}</Action>
        </div>
      ) : null}
      {askRemove ? (
        <ConfirmDialog
          title={tr(`Remove ${d.donorName}?`, `${d.donorName}-কে বাদ দেবেন?`)}
          text={tr(
            "They'll be told they don't need to come. If it's a mistake, you can bring them back within 30 minutes.",
            'তাঁকে জানানো হবে যে আর আসতে হবে না। ভুল হলে ৩০ মিনিটের মধ্যে ফিরিয়ে আনতে পারবেন।',
          )}
          confirm={tr('Remove', 'বাদ দিন')}
          danger
          onClose={() => setAskRemove(false)}
          onConfirm={() => {
            setAskRemove(false);
            onRemove();
          }}
        />
      ) : null}
    </div>
  );
}

/** ছোট সারি: কী হলো + ফিরিয়ে আনার বাটন + বন্ধ */
export function UndoNotice({ text, undo, busy, onUndo, onDismiss }: { text: string; undo: string; busy: boolean; onUndo: () => void; onDismiss: () => void }) {
  return (
    <div className="row blood-undo">
      <span className="t-body-m grow clamp2">{text}</span>
      <Action icon="refresh" busy={busy} onClick={onUndo}>{undo}</Action>
      <IconButton icon="close" small label={tr('Close', 'বন্ধ')} onClick={onDismiss} />
    </div>
  );
}

export const RemovedNotice = ({ name, busy, onUndo, onDismiss }: { name: string; busy: boolean; onUndo: () => void; onDismiss: () => void }) => (
  <UndoNotice text={tr(`${name} was removed`, `${name}-কে বাদ দেওয়া হয়েছে`)} undo={tr('Undo', 'ফিরিয়ে আনুন')} busy={busy} onUndo={onUndo} onDismiss={onDismiss} />
);

/** "রক্ত পেয়েছি": কত ব্যাগ পেয়েছেন, কে কে রক্ত দিয়েছেন */
export function CloseRequestDialog({
  bags, already, list, onDismiss, onClose,
}: { bags: number; already: number; list: DonorResponse[]; onDismiss: () => void; onClose: (donated: string[], got: number) => void }) {
  const w = willing(list);
  const donatedBefore = w.filter((d) => d.status === 'donated').length;
  const open = w.filter((d) => d.status === 'accepted');
  const maxBags = Math.max(bags, 1);
  const clampAlready = Math.min(Math.max(already, 1), maxBags);
  const [received, setReceivedState] = useState(clampAlready);
  const [donated, setDonated] = useState<string[]>([]);
  const room = Math.max(received - donatedBefore, 0);
  const setReceived = (n: number) => {
    setReceivedState(n);
    setDonated((d) => d.slice(0, Math.max(n - donatedBefore, 0)));
  };
  const notNeeded = received === 0;
  const closes = notNeeded || received >= bags;
  return (
    <Dialog
      onClose={onDismiss}
      title={notNeeded ? tr('Blood no longer needed?', 'রক্ত আর লাগবে না?') : closes ? tr('Close this request?', 'রিকোয়েস্ট বন্ধ করবেন?') : tr('Blood received so far', 'এখন পর্যন্ত কত রক্ত পেলেন')}
      actions={
        <>
          <Button variant="text" onClick={onDismiss}>{tr('Cancel', 'বাতিল')}</Button>
          <Button onClick={() => onClose(notNeeded ? [] : donated, received)}>{closes ? tr('Close', 'বন্ধ করুন') : tr('Save', 'সেভ করুন')}</Button>
        </>
      }
    >
      <div className="col gap-8">
        <span className="semi">{tr('How many bags did you get?', 'কত ব্যাগ পেয়েছেন?')}</span>
        <div className="row gap-8 blood-bags">
          {Array.from({ length: maxBags }, (_, i) => (
            <button key={i} type="button" className="blood-bagbtn" aria-pressed={i + 1 <= received} onClick={() => setReceived(i + 1)}>
              <BloodBag n={i + 1} filled={i + 1 <= received} label={tr(`${i + 1} bag(s)`, `${num(i + 1)} ব্যাগ`)} />
            </button>
          ))}
        </div>
        <div className="chips">
          <button type="button" className="chip" aria-pressed={notNeeded} onClick={() => setReceived(notNeeded ? clampAlready : 0)}>
            {notNeeded ? <Icon name="check_circle" /> : null}
            {tr('Not needed anymore', 'আর লাগবে না')}
          </button>
        </div>
        <p className="t-body-s muted">
          {notNeeded
            ? tr("The request will close. Donors who agreed will be told they don't need to come.", 'রিকোয়েস্ট বন্ধ হবে। রাজি ডোনারদের জানানো হবে যে আসতে হবে না।')
            : closes
              ? tr("All bags received — the request will close and donors won't see it anymore.", 'সব ব্যাগ পেয়েছেন — রিকোয়েস্ট বন্ধ হবে, ডোনাররা আর দেখবেন না।')
              : tr(
                `${num(received)} of ${num(bags)} received. The request stays open for the remaining ${num(bags - received)}.`,
                `${num(bags)} ব্যাগের ${num(received)}টা পেয়েছেন। বাকি ${num(bags - received)}টার জন্য রিকোয়েস্ট চালু থাকবে।`,
              )}
        </p>
        {closes ? <p className="t-body-s muted">{tr("If it's a mistake, you can open it again within 1 hour.", 'ভুল হলে ১ ঘণ্টার মধ্যে আবার খুলতে পারবেন।')}</p> : null}
        {!notNeeded && open.length ? (
          <>
            <span className="semi">{tr(`Who donated? (up to ${num(room)})`, `কে কে রক্ত দিয়েছেন? (${num(room)} জন পর্যন্ত)`)}</span>
            {open.map((d) => {
              const on = donated.includes(d.donorUid);
              return (
                <Check
                  key={d.donorUid}
                  checked={on}
                  disabled={!on && donated.length >= room}
                  onChange={(v) => setDonated((cur) => (v ? [...cur, d.donorUid] : cur.filter((x) => x !== d.donorUid)))}
                >
                  {`${d.donorName} (${d.donorGroup})` + (d.confirmed ? tr(' · confirmed', ' · নিশ্চিত') : '')}
                </Check>
              );
            })}
          </>
        ) : null}
      </div>
    </Dialog>
  );
}

/** শেয়ার: লিংক আর লেখা কপি, ফোনে থাকলে শেয়ারের তালিকা */
export function ShareDialog({ r, onClose }: { r: BloodRequest; onClose: () => void }) {
  const text = shareText(r);
  const link = shareLink(r.id);
  const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function';
  const copy = (v: string) => {
    navigator.clipboard?.writeText(v).then(
      () => toast(tr('Copied', 'কপি হয়েছে')),
      () => toast(tr("Couldn't copy", 'কপি করা যায়নি')),
    );
  };
  return (
    <Dialog
      onClose={onClose}
      title={tr('Share request', 'রিকোয়েস্ট শেয়ার করুন')}
      actions={
        <>
          <Button variant="text" onClick={() => copy(text)}>{tr('Copy text', 'লেখা কপি')}</Button>
          {canShare ? (
            <Button icon="share" onClick={() => navigator.share({ text }).catch(() => {})}>{tr('Share', 'শেয়ার')}</Button>
          ) : null}
        </>
      }
    >
      <p className="t-body-m pre blood-sharetext">{text}</p>
      <div className="row blood-link">
        <span className="grow ellipsis num" style={{ userSelect: 'all' }}>{link}</span>
        <Button variant="tonal" small onClick={() => copy(link)}>{tr('Copy link', 'লিংক কপি')}</Button>
      </div>
    </Dialog>
  );
}
