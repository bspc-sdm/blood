// প্রোফাইলের ফর্ম (অ্যাপের ProfileScreen): BASIC = নাম-মোবাইল, DONOR = রক্তদাতার তথ্য, FULL = সব
import { useState } from 'react';
import { Button, Check, ChoiceChips, ErrorText, TextInput } from '@/ui/kit';
import { formatDate, fromDateInput, dateInputValue, normalizePhone, todayUtcMidnight } from '@/lib/format';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { BLOOD_GROUPS, UNKNOWN_GROUP, ageYears } from '@/lib/rules';
import type { Profile } from '@/lib/profile';

export type ProfileMode = 'basic' | 'donor' | 'full';

/** ইংরেজি নামে প্রতিটা শব্দের প্রথম অক্ষর বড় */
export function nameCase(s: string) {
  return s
    .trim()
    .replace(/\s+/g, ' ')
    .split(' ')
    .map((w) => (w && /[a-z]/.test(w[0]) ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ')
    .replace(/(?<=[A-Za-z][-'’])([a-z])(?=[a-z])/g, (m) => m.toUpperCase());
}

export function BloodGroupChips({ value, onChange, allowUnknown }: { value: string; onChange: (g: string) => void; allowUnknown?: boolean }) {
  const opts = BLOOD_GROUPS.map((g) => ({ value: g, label: g }));
  if (allowUnknown) opts.push({ value: UNKNOWN_GROUP, label: tr("I don't know", 'জানি না') });
  return (
    <div className="col gap-6">
      <ChoiceChips options={opts} value={value} onChange={onChange} />
      {value === UNKNOWN_GROUP ? (
        <p className="t-body-s muted">
          {tr(
            "You won't get donation requests until you add your group. Get it tested at a hospital or blood bank, then update your profile.",
            'গ্রুপ না দেওয়া পর্যন্ত রক্তদানের নোটিফিকেশন আসবে না। হাসপাতাল বা ব্লাড ব্যাংকে পরীক্ষা করে জেনে প্রোফাইলে দিন।',
          )}
        </p>
      ) : null}
    </div>
  );
}

export function ProfileForm({
  existing, mode, busy, error, onSave, submitLabel,
}: { existing: Profile | null; mode: ProfileMode; busy?: boolean; error?: string | null; onSave: (p: Profile, includeDonation: boolean) => void; submitLabel?: string }) {
  const [name, setName] = useState(existing?.name ?? '');
  const [phone, setPhone] = useState(existing?.phone ?? '');
  const [group, setGroup] = useState(mode === 'donor' && existing?.bloodGroup === UNKNOWN_GROUP ? '' : existing?.bloodGroup ?? '');
  const [birth, setBirth] = useState<number | null>(existing?.birthDateMillis ?? null);
  const [weight, setWeight] = useState(existing?.weightKg != null ? String(existing.weightKg) : '');
  const saved = existing?.lastDonationMillis ?? null;
  const [never, setNever] = useState(!!existing && !!existing.bloodGroup && existing.lastDonationMillis == null);
  const [last, setLast] = useState<number | null>(saved);
  const [formError, setFormError] = useState<string | null>(null);
  const showContact = mode !== 'donor';
  const showDonor = mode !== 'basic';
  const today = dateInputValue(todayUtcMidnight());

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const cleanPhone = normalizePhone(phone);
    const age = ageYears(birth) ?? 0;
    const w = parseInt(toAsciiDigits(weight), 10);
    const donorTouched = mode === 'donor' || (mode === 'full' && (!!group || birth != null || !!weight.trim() || last != null || saved != null));
    let err: string | null = null;
    if (showContact && !name.trim()) err = tr('Enter your name', 'নাম লিখুন');
    else if (showContact && !cleanPhone) err = tr('Enter a valid mobile number (01…, 11 digits)', 'সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)');
    else if (donorTouched) {
      if (!group) err = tr('Choose your blood group', 'রক্তের গ্রুপ বেছে নিন');
      else if (birth == null) err = tr('Add your birth date', 'জন্ম তারিখ দিন');
      else if (age < 10 || age > 100) err = tr('Check your birth date', 'জন্ম তারিখ ঠিক নেই');
      else if (!(w >= 20 && w <= 250)) err = tr('Enter a valid weight (kg)', 'সঠিক ওজন দিন (কেজি)');
      else if (saved == null && !never && last == null) err = tr('Add your last donation date, or tick "never donated"', 'শেষ রক্তদানের তারিখ দিন, অথবা "কখনো রক্ত দিইনি" টিক দিন');
      else if ((last ?? 0) > todayUtcMidnight()) err = tr("Date can't be in the future", 'ভবিষ্যতের তারিখ দেওয়া যাবে না');
    }
    setFormError(err);
    if (err) return;
    const base: Profile = existing ?? ({} as Profile);
    const out: Profile = {
      ...base,
      ...(showContact && cleanPhone ? { name: nameCase(name), phone: cleanPhone } : {}),
      ...(donorTouched ? { bloodGroup: group, birthDateMillis: birth, weightKg: w, lastDonationMillis: saved ?? (never ? null : last) } : {}),
      notify: existing?.notify ?? true,
    };
    onSave(out, donorTouched && saved == null);
  }

  return (
    <form className="col gap-16" onSubmit={submit} noValidate>
      {mode === 'donor' ? (
        <p className="t-body-m muted">
          {tr(
            'We use these to know when you can donate and to send you only requests your blood group can help.',
            'আপনি কখন রক্ত দিতে পারবেন আর কোন রিকোয়েস্ট আপনার গ্রুপের সাথে মেলে, তা বুঝতে এগুলো লাগে।',
          )}
        </p>
      ) : null}
      {showContact ? (
        <>
          <TextInput label={tr('Name', 'নাম')} value={name} maxLength={80} autoComplete="name" onChange={(e) => setName(e.target.value)} />
          <TextInput
            label={tr('Mobile number', 'মোবাইল নম্বর')}
            value={phone}
            inputMode="tel"
            autoComplete="tel"
            placeholder="01XXXXXXXXX"
            hint={tr('Shown only after you agree to donate', 'শুধু "রক্ত দেব" চাপার পর অন্যজন দেখবেন')}
            onChange={(e) => setPhone(e.target.value)}
          />
        </>
      ) : null}
      {showDonor ? (
        <>
          {mode === 'full' ? <h3 className="t-title-m bold">{tr('Donor details (only if you want to donate blood)', 'রক্তদাতার তথ্য (শুধু রক্ত দিতে চাইলে)')}</h3> : null}
          <div className="col gap-6">
            <span className="t-title-s">{tr('Blood group', 'রক্তের গ্রুপ')}</span>
            <BloodGroupChips value={group} onChange={setGroup} allowUnknown={mode === 'full'} />
          </div>
          <div className="row top gap-12" style={{ flexWrap: 'wrap' }}>
            <div className="grow" style={{ minWidth: 180 }}>
              <TextInput type="date" label={tr('Birth date', 'জন্ম তারিখ')} max={today} value={dateInputValue(birth)} onChange={(e) => setBirth(fromDateInput(e.target.value))} />
            </div>
            <div className="grow" style={{ minWidth: 140 }}>
              <TextInput label={tr('Weight (kg)', 'ওজন (কেজি)')} inputMode="numeric" value={weight} onChange={(e) => setWeight(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 3))} />
            </div>
          </div>
          <div className="col gap-6">
            <span className="t-title-s">{tr('Last donation', 'শেষ রক্তদান')}</span>
            {saved != null ? (
              <>
                <span className="t-body-l">{formatDate(saved)}</span>
                <span className="t-body-s muted">{tr('To change it, use "Change last donation date" in Profile.', 'বদলাতে প্রোফাইলের "শেষ রক্তদানের তারিখ বদলান" চাপুন।')}</span>
              </>
            ) : (
              <>
                <Check checked={never} onChange={(v) => { setNever(v); if (v) setLast(null); }}>{tr('I have never donated', 'কখনো রক্ত দিইনি')}</Check>
                {!never ? <TextInput type="date" aria-label={tr('Last donation', 'শেষ রক্তদান')} max={today} value={dateInputValue(last)} onChange={(e) => setLast(fromDateInput(e.target.value))} /> : null}
              </>
            )}
          </div>
        </>
      ) : null}
      <ErrorText>{formError ?? error}</ErrorText>
      <div className="form-actions">
        <Button type="submit" busy={busy}>{submitLabel ?? tr('Save', 'সেভ করুন')}</Button>
      </div>
    </form>
  );
}
