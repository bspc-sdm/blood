// লগইন-গেট (অ্যাপের LoginGate + LoginScreen): ইমেইল → কোড → (দরকার হলে) নাম-মোবাইল → (দরকার হলে) রক্তদাতার তথ্য,
// তারপর যে কাজের জন্য লগইন চাওয়া হয়েছিল সেটাই চলে।
import { useEffect, useState } from 'react';
import { Button, Dialog, ErrorText, InfoText, TextInput, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, toAsciiDigits, tr, trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { isComplete, isDonor, saveContact, saveDonor, type Profile } from '@/lib/profile';
import { ProfileForm } from './ProfileForm';

type Step = 'email' | 'code' | 'contact' | 'donor';

export function LoginFlow() {
  const s = useSession();
  const p = s.pending!;
  const [step, setStep] = useState<Step>(s.guest ? 'email' : 'contact');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [wait, setWait] = useState(0);

  useEffect(() => {
    if (wait <= 0) return;
    const t = setTimeout(() => setWait(wait - 1), 1000);
    return () => clearTimeout(t);
  }, [wait]);

  // লগইন হয়ে গেলে/প্রোফাইল এলে পরের ধাপ ঠিক করা
  useEffect(() => {
    if (s.guest || !s.profileLoaded) return;
    if (step === 'email' || step === 'code') return;
    if (!isComplete(s.profile)) setStep('contact');
    else if (p.donor && !isDonor(s.profile)) setStep('donor');
    else s.finishPending(true);
  }, [s.guest, s.profileLoaded, s.profile, step, p.donor]); // eslint-disable-line react-hooks/exhaustive-deps

  const cleanEmail = email.trim().toLowerCase();
  const validEmail = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(cleanEmail);

  async function sendCode() {
    if (!validEmail) return setError(tr('Enter a valid email', 'সঠিক ইমেইল দিন'));
    setBusy(true);
    setError(null);
    try {
      await s.sendCode(cleanEmail);
      setStep('code');
      setInfo(trPlain('We sent a 6-digit code. Check Spam too.', '৬ সংখ্যার কোড পাঠানো হয়েছে। Spam ফোল্ডারও দেখুন।'));
      setWait(60);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function verify() {
    const c = toAsciiDigits(code).replace(/\D/g, '');
    if (c.length !== 6) return setError(tr('Enter the 6-digit code', '৬ সংখ্যার কোড দিন'));
    setBusy(true);
    setError(null);
    try {
      const r = await s.signIn(cleanEmail, c);
      if (r.restored) toast(tr('Account deletion cancelled. Everything is back.', 'অ্যাকাউন্ট মোছা বাতিল হয়েছে। সব আগের মতো আছে।'));
      setStep('contact');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveProfile(next: Profile, includeDonation: boolean, kind: 'contact' | 'donor') {
    if (!s.uid) return;
    setBusy(true);
    setError(null);
    try {
      if (kind === 'contact') await saveContact(s.uid, next.name, next.phone);
      else await saveDonor(s.uid, next, includeDonation);
      await s.reloadProfile();
    } catch (e) {
      setError(errorText(e) || tr("Couldn't save. Check your internet.", 'সেভ হয়নি, ইন্টারনেট দেখুন।'));
    } finally {
      setBusy(false);
    }
  }

  const title =
    step === 'email' || step === 'code' ? tr('Log in', 'লগইন') : step === 'contact' ? tr('Your profile', 'আপনার প্রোফাইল') : tr('Donor details', 'রক্তদাতার তথ্য');

  return (
    <Dialog onClose={() => s.finishPending(false)} title={title}>
      {p.reason ? <p className="t-body-m" style={{ background: 'var(--secondary-container)', color: 'var(--on-secondary-container)', padding: '10px 14px', borderRadius: 12 }}>{p.reason}</p> : null}
      {step === 'email' ? (
        <form className="col gap-12" onSubmit={(e) => { e.preventDefault(); sendCode(); }}>
          <p className="t-body-m muted">{tr('We will email you a code. No password needed.', 'ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।')}</p>
          <TextInput label={tr('Email', 'ইমেইল')} type="email" inputMode="email" autoComplete="email" autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
          <ErrorText>{error}</ErrorText>
          <div className="form-actions">
            <Button type="submit" busy={busy}>{tr('Send code', 'কোড পাঠান')}</Button>
          </div>
        </form>
      ) : null}
      {step === 'code' ? (
        <form className="col gap-12" onSubmit={(e) => { e.preventDefault(); verify(); }}>
          <InfoText>{info}</InfoText>
          <p className="t-body-m">{cleanEmail} <button type="button" className="btn text small" onClick={() => { setStep('email'); setCode(''); setError(null); }}>{tr('Change', 'বদলান')}</button></p>
          <TextInput
            label={tr('6-digit code', '৬ সংখ্যার কোড')}
            inputMode="numeric"
            autoComplete="one-time-code"
            autoFocus
            maxLength={6}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 6))}
          />
          <ErrorText>{error}</ErrorText>
          <div className="form-actions">
            <Button variant="text" disabled={wait > 0 || busy} onClick={sendCode}>
              {wait > 0 ? tr(`Resend in ${wait}s`, `${num(wait)} সেকেন্ড পর আবার পাঠান`) : tr('Resend code', 'আবার কোড পাঠান')}
            </Button>
            <Button type="submit" busy={busy}>{tr('Log in', 'লগইন')}</Button>
          </div>
        </form>
      ) : null}
      {step === 'contact' && s.profileLoaded ? (
        <ProfileForm existing={s.profile} mode="basic" busy={busy} error={error} onSave={(np, inc) => saveProfile(np, inc, 'contact')} submitLabel={tr('Continue', 'এগিয়ে যান')} />
      ) : null}
      {step === 'donor' && s.profileLoaded ? (
        <ProfileForm existing={s.profile} mode="donor" busy={busy} error={error} onSave={(np, inc) => saveProfile(np, inc, 'donor')} submitLabel={tr('Continue', 'এগিয়ে যান')} />
      ) : null}
      {(step === 'contact' || step === 'donor') && !s.profileLoaded ? <div className="loading"><span className="spinner" /></div> : null}
    </Dialog>
  );
}
