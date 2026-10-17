// প্রোফাইল এডিট (অ্যাপের ProfileEditScreen, FULL): নাম-মোবাইল আর রক্তদাতার তথ্য একসাথে
import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { Loading, toast } from '@/ui/kit';
import { PlacePicker } from '@/ui/AreaPicker';
import { FormPage } from '@/shell/templates';
import { ProfileForm } from '@/account/ProfileForm';
import { errorText } from '@/lib/api';
import { tr, useLang } from '@/lib/i18n';
import { hasLocation, isComplete, loadProfile, saveBasics, saveContact, type Profile } from '@/lib/profile';
import { useSession } from '@/lib/session';
import { TEST_MODE } from '@/lib/testmode';

export function EditProfilePage() {
  useLang();
  const s = useSession();
  const nav = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pickArea, setPickArea] = useState(false);
  const [areaBusy, setAreaBusy] = useState(false);
  const [areaError, setAreaError] = useState<string | null>(null);

  if (!s.ready) return <Loading />;
  if (s.guest) return <Navigate to="/profile" replace />;
  const full = isComplete(s.profile);

  async function save(next: Profile) {
    if (!s.uid) return;
    setBusy(true);
    setError(null);
    try {
      // সার্ভার থেকে নতুন প্রোফাইল (রক্ত দেওয়ার পর সার্ভার তারিখ বসিয়ে থাকতে পারে)
      const fresh = (TEST_MODE ? null : await loadProfile(s.uid).catch(() => null)) ?? s.profile;
      const firstDate = fresh?.lastDonationMillis == null;
      if (full) await saveBasics(s.uid, next, firstDate);
      else await saveContact(s.uid, next.name, next.phone);
      const p = await s.reloadProfile();
      toast(tr('Saved', 'সেভ হয়েছে'));
      if (!hasLocation(p ?? next)) setPickArea(true);
      else nav('/profile');
    } catch (e) {
      setError(tr("Couldn't save", 'সেভ হয়নি') + ': ' + errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormPage title={full ? tr('Edit profile', 'প্রোফাইল এডিট') : tr('Your profile', 'আপনার প্রোফাইল')} backTo="/profile">
      {s.profileLoaded ? (
        <ProfileForm existing={s.profile} mode={full ? 'full' : 'basic'} busy={busy} error={error} onSave={(p) => save(p)} />
      ) : (
        <Loading />
      )}
      {pickArea ? (
        <PlacePicker
          busy={areaBusy}
          error={areaError}
          onClose={() => {
            setPickArea(false);
            nav('/profile');
          }}
          onPicked={async (pl) => {
            setAreaBusy(true);
            setAreaError(null);
            try {
              await s.setPlace(pl);
              setPickArea(false);
              nav('/profile');
            } catch (e) {
              setAreaError(errorText(e));
            } finally {
              setAreaBusy(false);
            }
          }}
        />
      ) : null}
    </FormPage>
  );
}
