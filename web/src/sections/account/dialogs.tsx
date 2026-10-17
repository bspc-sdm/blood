// ডায়ালগ: শেষ রক্তদানের তারিখ (অ্যাপের Corrections.kt), ফোন যাচাই (Updates.kt), প্রোফাইলের ছবি (Profile.kt),
// ব্যাজের কাগজের ছবি (Verify.kt-এর DocsNeededNotice)
import { useRef, useState } from 'react';
import { Button, Check, Dialog, ErrorText, TextInput, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { dateInputValue, formatDate, fromDateInput, todayUtcMidnight } from '@/lib/format';
import { DAY_MS, ELIGIBILITY_DAYS } from '@/lib/rules';
import { savePhoto, type Profile } from '@/lib/profile';
import { useSession } from '@/lib/session';
import { toAvatar, toDocument } from './util';

/** অ্যাপের Rules.donationRisk */
export function donationRisk(before: number | null, after: number | null): 'backward' | 'short_gap' | null {
  if (before == null) return null;
  if (after == null || after < before) return 'backward';
  if (after > before && after - before < ELIGIBILITY_DAYS * DAY_MS) return 'short_gap';
  return null;
}

/** শেষ রক্তদানের তারিখ বদলানো: পেছালে বা ১২০ দিনের কম ফাঁকে লাল সতর্কতা আর "জেনেই সিদ্ধান্ত" টিক */
export function DonationDateDialog({ profile, onClose, onSaved }: { profile: Profile; onClose: () => void; onSaved: (v: number | null) => void }) {
  const before = profile.lastDonationMillis ?? null;
  const [never, setNever] = useState(before == null);
  const [date, setDate] = useState<number | null>(before);
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const after = never ? null : date;
  const risk = donationRisk(before, after);
  const changed = after !== before;
  const can = !busy && changed && (never || date != null) && (risk == null || agreed);

  async function save() {
    if ((after ?? 0) > todayUtcMidnight()) return setError(tr("Date can't be in the future", 'ভবিষ্যতের তারিখ দেওয়া যাবে না'));
    setBusy(true);
    setError(null);
    try {
      await call('donation_date_set', { millis: after, confirmed: risk != null && agreed });
      onSaved(after);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const gap = Math.round(((after ?? 0) - (before ?? 0)) / DAY_MS);
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="small"
      title={tr('Last donation date', 'শেষ রক্তদানের তারিখ')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} disabled={!can} onClick={save}>{tr('Confirm', 'নিশ্চিত করুন')}</Button>
        </>
      }
    >
      <p className="t-body-m muted">{tr('Now: ', 'এখন: ') + (before != null ? formatDate(before) : tr('Never donated', 'কখনো দেননি'))}</p>
      <Check checked={never} onChange={(v) => { setNever(v); setAgreed(false); }}>{tr('I have never donated', 'কখনো রক্ত দিইনি')}</Check>
      {!never ? (
        <TextInput
          type="date"
          aria-label={tr('Pick the date', 'তারিখ বেছে নিন')}
          max={dateInputValue(todayUtcMidnight())}
          value={dateInputValue(date)}
          onChange={(e) => { setDate(fromDateInput(e.target.value)); setAgreed(false); }}
        />
      ) : null}
      {changed && risk ? (
        <div className="acct-risk" role="alert">
          <div className="row gap-6">
            <Icon name="warning" size={18} style={{ color: 'var(--error)' }} />
            <span className="t-title-s">{tr('Health risk', 'স্বাস্থ্যঝুঁকি')}</span>
          </div>
          <p className="t-body-s">
            {risk === 'backward'
              ? tr(
                  "You're moving the date back. You'll show as able to donate earlier. Donating less than 120 days after your last donation is risky for you and the patient.",
                  'আপনি তারিখ পেছাচ্ছেন — আগের চেয়ে আগেই "রক্ত দিতে পারবেন" দেখাবে। আগের রক্তদানের ১২০ দিনের আগে আবার রক্ত দেওয়া আপনার ও রোগীর জন্য ঝুঁকিপূর্ণ।',
                )
              : tr(
                  `Only ${gap} days between the two donations. Less than 120 days between donations is a health risk.`,
                  `দুই রক্তদানের মাঝে মাত্র ${num(gap)} দিন। ১২০ দিনের কম ফাঁকে রক্ত দেওয়া স্বাস্থ্যঝুঁকি।`,
                )}
          </p>
          <Check checked={agreed} onChange={setAgreed}><span className="semi">{tr('I understand and have decided', 'আমি জেনেই সিদ্ধান্ত নিয়েছি')}</span></Check>
        </div>
      ) : null}
      <ErrorText>{error}</ErrorText>
    </Dialog>
  );
}

/** ফোন নম্বর যাচাই: SMS-এ ৬ সংখ্যার কোড */
export function PhoneVerifyDialog({ onClose, onVerified }: { onClose: () => void; onVerified: () => void }) {
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const r = await call<{ to?: string }>('phone_send_code');
      setSentTo(r.to ?? '');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function verify() {
    setBusy(true);
    setError(null);
    try {
      await call('phone_verify_code', { code: toAsciiDigits(code) });
      onVerified();
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
      title={tr('Verify your phone', 'ফোন নম্বর যাচাই')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          {sentTo == null ? (
            <Button busy={busy} onClick={send}>{tr('Send code', 'কোড পাঠান')}</Button>
          ) : (
            <Button busy={busy} disabled={toAsciiDigits(code).length !== 6} onClick={verify}>{tr('Verify', 'যাচাই করুন')}</Button>
          )}
        </>
      }
    >
      <p className="t-body-m">
        {tr(
          'A nurse will come to a home, so we check that the phone number in your profile is really yours.',
          'নার্স বাসায় যাবেন, তাই প্রোফাইলের নম্বরটা সত্যিই আপনার কি না যাচাই করা হয়।',
        )}
      </p>
      {sentTo != null ? (
        <>
          <p className="t-body-s">{tr('Code sent to ', 'কোড পাঠানো হয়েছে: ') + num(sentTo)}</p>
          <TextInput
            label={tr('6-digit code', '৬ সংখ্যার কোড')}
            inputMode="numeric"
            autoComplete="one-time-code"
            autoFocus
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 6))}
          />
          <div><Button variant="text" small disabled={busy} onClick={send}>{tr('Send again', 'আবার পাঠান')}</Button></div>
        </>
      ) : null}
      <ErrorText>{error}</ErrorText>
    </Dialog>
  );
}

/** প্রোফাইলের ছবি: বাছাই → ছোট করে সেভ; থাকলে "বদলান / মুছুন" */
export function usePhotoPicker() {
  const s = useSession();
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onFile(file: File | undefined) {
    if (!file || !s.uid || !s.profile) return;
    setBusy(true);
    setError(null);
    try {
      const { photo, thumb } = await toAvatar(file);
      await savePhoto(s.uid, photo, thumb);
      s.setProfile({ ...s.profile, photo });
    } catch {
      setError(tr("Couldn't upload the photo", 'ছবি আপলোড হয়নি'));
    } finally {
      setBusy(false);
    }
  }
  async function remove() {
    if (!s.uid || !s.profile) return;
    setError(null);
    try {
      await savePhoto(s.uid, null, null);
      s.setProfile({ ...s.profile, photo: null });
    } catch {
      setError(tr("Couldn't remove the photo", 'ছবি মোছা যায়নি'));
    }
  }
  const field = (
    <input
      ref={input}
      type="file"
      accept="image/*"
      hidden
      onChange={(e) => {
        onFile(e.target.files?.[0]);
        e.target.value = '';
      }}
    />
  );
  return { field, pick: () => input.current?.click(), remove, busy, error };
}

export function PhotoMenuDialog({ onClose, onChange, onRemove }: { onClose: () => void; onChange: () => void; onRemove: () => void }) {
  return (
    <Dialog
      onClose={onClose}
      size="small"
      title={tr('Profile photo', 'প্রোফাইলের ছবি')}
      actions={
        <>
          <Button variant="danger-text" icon="delete" onClick={() => { onClose(); onRemove(); }}>{tr('Remove', 'মুছুন')}</Button>
          <Button icon="photo_camera" onClick={() => { onClose(); onChange(); }}>{tr('Change', 'বদলান')}</Button>
        </>
      }
    />
  );
}

// ---------- ব্যাজের কাগজের ছবি ----------
interface DocSpec { type: string; label: string; hint: string; required: boolean; has?: boolean }

export function DocsNeededCard({ uid, docs, requested, note, onDone }: { uid: string; docs: DocSpec[]; requested: boolean; note: string; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [photos, setPhotos] = useState<Record<string, string>>({});
  const [reading, setReading] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const picking = useRef<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  async function onFile(f: File | undefined) {
    const type = picking.current;
    picking.current = null;
    if (!f || !type) return;
    setReading(type);
    try {
      const b64 = await toDocument(f);
      setPhotos((p) => ({ ...p, [type]: b64 }));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setReading(null);
    }
  }
  async function send() {
    setBusy(true);
    setError(null);
    try {
      await call('verify_docs_upload', { kind: 'badge', id: uid, docs: photos });
      toast(tr('Sent.', 'পাঠানো হয়েছে।'));
      setOpen(false);
      setPhotos({});
      onDone();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="acct-docs">
      <span className="t-title-s">
        {requested ? tr('The Lifolo team asked for document photos', 'Lifolo টিম কাগজের ছবি চেয়েছে') : tr('Document photos are needed for checking', 'যাচাইয়ের জন্য কাগজের ছবি লাগবে')}
      </span>
      {note ? <p className="t-body-m pre">{note}</p> : null}
      {!open ? (
        <div><Button variant="outline" small onClick={() => setOpen(true)}>{tr('Add photos', 'ছবি দিন')}</Button></div>
      ) : (
        <>
          <input ref={input} type="file" accept="image/*" hidden onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = ''; }} />
          <span className="t-body-s muted">{tr('Clear photo of the whole paper.', 'পুরো কাগজের পরিষ্কার ছবি দিন।')}</span>
          {docs.map((d) => (
            <div key={d.type} className="row gap-12">
              {photos[d.type] ? <img className="acct-doc-thumb" src={`data:image/jpeg;base64,${photos[d.type]}`} alt="" /> : null}
              <div className="col gap-2 grow">
                <span className="t-body-m semi">{d.label}{d.required ? ' *' : ''}</span>
                {d.hint ? <span className="t-body-s muted">{d.hint}</span> : null}
              </div>
              <Button
                variant="text"
                small
                busy={reading === d.type}
                disabled={reading != null}
                onClick={() => { picking.current = d.type; input.current?.click(); }}
              >
                {photos[d.type] ? tr('Change', 'বদলান') : tr('Add photo', 'ছবি দিন')}
              </Button>
            </div>
          ))}
          <ErrorText>{error}</ErrorText>
          <div className="row gap-8">
            <Button small busy={busy} disabled={!Object.keys(photos).length} onClick={send}>{tr('Send', 'পাঠান')}</Button>
            <Button variant="text" small onClick={() => setOpen(false)}>{tr('Cancel', 'বাতিল')}</Button>
          </div>
        </>
      )}
    </div>
  );
}
