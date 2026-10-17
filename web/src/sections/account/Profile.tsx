// প্রোফাইল (অ্যাপের Profile.kt → ProfileTab): ছবি, রক্তদানের যোগ্যতা, তথ্য, আর সব কাজের সারি।
// ডেস্কটপে দুই কলাম (বাঁয়ে প্রোফাইলের সারাংশ, ডানে মেনু); মোবাইলে অ্যাপের মতো এক কলাম।
import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { BloodGroupBadge, Button, ConfirmDialog, IconLine, ListRow, Loading, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { num, tr, useLang } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { districtLabel } from '@/lib/geo';
import { ageYears, notEligibleReason } from '@/lib/rules';
import { isDonor, type Profile } from '@/lib/profile';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { store } from '@/lib/store';
import { DocsNeededCard, DonationDateDialog, PhoneVerifyDialog, PhotoMenuDialog, usePhotoPicker } from './dialogs';
import { SUPPORT_EMAIL, badgeLabel, hasBusiness, useAccountState } from './util';

export function ProfilePage() {
  useLang();
  const s = useSession();
  usePageTitle(tr('Profile', 'প্রোফাইল'));
  if (!s.ready) return <Loading />;
  if (s.guest) return <GuestProfile />;
  if (!s.profile) return <Loading />;
  return <MemberProfile profile={s.profile} />;
}

// ---------- ছোট অংশ ----------
function MobileHeading() {
  return <h1 className="acct-mhead t-headline">{tr('Profile', 'প্রোফাইল')}</h1>;
}

export function BadgeChip({ type }: { type: string }) {
  return (
    <span className="tag good acct-badge">
      <Icon name="verified" size={14} />
      {badgeLabel(type)}
    </span>
  );
}

function InfoLine({ icon, label, value, trailing }: { icon: string; label: string; value: ReactNode; trailing?: ReactNode }) {
  return (
    <div className="acct-info">
      <Icon name={icon} size={20} />
      <div className="col gap-2 grow">
        <span className="t-label-m muted">{label}</span>
        <span className="t-body-l">{value || '—'}</span>
      </div>
      {trailing}
    </div>
  );
}

/** "রক্ত দিতে চাই" — যিনি এখনো ডোনারের তথ্য দেননি */
function BecomeDonorCard({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="acct-donor-cta" onClick={onClick}>
      <Icon name="volunteer_activism" size={22} />
      <span className="grow t-title-s">{tr('I want to donate blood', 'রক্ত দিতে চাই')}</span>
      <Icon name="chevron_right" size={20} />
    </button>
  );
}

/** "আপনার কি কোনো ব্যবসা আছে?" — "না" বললে কার্ডের বদলে মেনুতে এক লাইন */
function BusinessQuestionCard({ onYes, onNo }: { onYes: () => void; onNo: () => void }) {
  return (
    <section className="acct-bizq">
      <div className="row gap-12">
        <Icon name="storefront" size={24} />
        <h2 className="t-title-m bold">{tr('Do you have a business?', 'আপনার কি কোনো ব্যবসা আছে?')}</h2>
      </div>
      <p className="t-body-s">
        {tr(
          'Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.',
          'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।',
        )}
      </p>
      <div className="row gap-8">
        <Button onClick={onYes}>{tr('Yes, add it', 'হ্যাঁ, যোগ করুন')}</Button>
        <Button variant="text" onClick={onNo}>{tr('No', 'না')}</Button>
      </div>
    </section>
  );
}

function Menu({ children, label }: { children: ReactNode; label?: string }) {
  const items = (Array.isArray(children) ? children : [children]).flat().filter(Boolean) as ReactNode[];
  return (
    <nav className="acct-menu" aria-label={label}>
      {items.map((c, i) => (
        <div key={i}>
          {i > 0 ? <hr className="divider acct-div" /> : null}
          {c}
        </div>
      ))}
    </nav>
  );
}

// ---------- অতিথি ----------
function GuestProfile() {
  const s = useSession();
  const nav = useNavigate();
  return (
    <div className="acct-page">
      <MobileHeading />
      <div className="acct-narrow col gap-12">
        <section className="card acct-summary">
          <span className="acct-photo static"><Icon name="person_filled" size={56} /></span>
          <p className="t-body-m muted">{tr('We will email you a code. No password needed.', 'ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।')}</p>
          <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
        </section>
        <BecomeDonorCard onClick={() => s.requireLogin(tr('Log in to become a blood donor', 'রক্তদাতা হতে লগইন করুন'), () => {}, { donor: true })} />
        <Menu>
          <ListRow icon="settings_outline" title={tr('App settings', 'অ্যাপ সেটিংস')} sub={tr('Language, theme, notifications, area', 'ভাষা, থিম, নোটিফিকেশন, এলাকা')} onClick={() => nav('/settings')} />
          <ListRow icon="help" title={tr('Help & appeals', 'সাহায্য ও আপিল')} onClick={() => nav('/help')} />
          <ListRow
            icon="storefront"
            title={tr('Add your business', 'আপনার ব্যবসা যোগ করুন')}
            sub={tr('Ambulance, pharmacy, blood bank, lab', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি')}
            onClick={() => s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav('/work/add'))}
          />
        </Menu>
      </div>
    </div>
  );
}

// ---------- লগইন করা ----------
function MemberProfile({ profile }: { profile: Profile }) {
  const s = useSession();
  const nav = useNavigate();
  const { me, work, workLoaded } = useAccountState();
  const photo = usePhotoPicker();
  const [photoMenu, setPhotoMenu] = useState(false);
  const [dateDialog, setDateDialog] = useState(false);
  const [phoneDialog, setPhoneDialog] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const bizKey = `lf.businessNo.${s.uid ?? ''}`;
  const [businessNo, setBusinessNo] = useState(() => store.get(bizKey) === '1');
  const assisting = useCall<{ items?: unknown[] }>('rx_assisting', {}, { staleTime: 5 * 60_000 });

  // নিজের ব্যবসার হিসাব আগে আনা না গেলে আবার
  useEffect(() => {
    if (!s.workLoaded) s.refreshWork().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const donor = isDonor(profile);
  const notEligible = donor ? notEligibleReason(profile) : null;
  const badge = me?.badge ?? null;
  const approved = badge?.status === 'approved' ? badge.type : null;
  const owns = hasBusiness(work);
  const age = ageYears(profile.birthDateMillis);
  const isAdmin = !!me?.isAdmin || s.email.toLowerCase() === SUPPORT_EMAIL;
  const needsPhoneCheck = !!me?.phoneCheck && !me.phoneVerified;

  const badgeSub = (() => {
    switch (badge?.status) {
      case 'approved':
        return badgeLabel(badge.type) + (badge.changePending ? tr(' · correction under review', ' · সংশোধন যাচাই চলছে') : tr(' · tap to correct details', ' · তথ্য ঠিক করতে চাপুন'));
      case 'pending':
        return tr('Under review', 'যাচাই চলছে');
      case 'rejected':
        return tr('Not approved. Tap to apply again.', 'অনুমোদন হয়নি। আবার আবেদন করুন।');
      default:
        return tr('For doctors, nurses and health experts', 'ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞদের জন্য');
    }
  })();
  const badgeLocked = badge?.status === 'pending' || !!badge?.changePending;
  const docs = Array.isArray(badge?.docs) ? (badge!.docs as { type: string; label: string; hint: string; required: boolean }[]) : [];
  const docsMissing = !!badge?.docsMissing && badge?.status === 'pending';
  const docsRequested = !!badge?.docsRequested;

  async function deleteAccount() {
    setDeleting(true);
    setDeleteError(null);
    try {
      await call('delete_account');
      setConfirmDelete(false);
      await s.signOut();
      nav('/', { replace: true });
    } catch (e) {
      setDeleteError(errorText(e));
    } finally {
      setDeleting(false);
    }
  }

  async function logout() {
    await s.signOut();
    nav('/', { replace: true });
  }

  return (
    <div className="acct-page">
      <MobileHeading />
      <div className="acct-profile">
        <div className="acct-col acct-left">
          {/* ---- ছবি, নাম, ব্যাজ ---- */}
          <section className="card acct-summary acct-o1">
            {photo.field}
            <div className="acct-photo-wrap">
              <button
                type="button"
                className="acct-photo"
                disabled={photo.busy}
                aria-label={tr('Profile photo', 'প্রোফাইলের ছবি')}
                onClick={() => (profile.photo ? setPhotoMenu(true) : photo.pick())}
              >
                {photo.busy ? (
                  <span className="spinner" />
                ) : profile.photo ? (
                  <img src={profile.photo.startsWith('data:') ? profile.photo : `data:image/jpeg;base64,${profile.photo}`} alt={tr('Profile photo', 'প্রোফাইলের ছবি')} />
                ) : (
                  <Icon name="person_filled" size={56} />
                )}
              </button>
              <button type="button" className="acct-cam" disabled={photo.busy} aria-label={tr('Change photo', 'ছবি বদলান')} title={tr('Change photo', 'ছবি বদলান')} onClick={photo.pick}>
                <Icon name="photo_camera" size={18} />
              </button>
            </div>
            <h2 className="t-title-l acct-name">{profile.name || '—'}</h2>
            {approved ? <BadgeChip type={approved} /> : null}
            {s.email ? <span className="t-body-s muted acct-email">{s.email}</span> : null}
            {photo.error ? <p className="err-text" role="alert">{photo.error}</p> : null}
          </section>

          {/* ---- রক্তের গ্রুপ ও যোগ্যতা ---- */}
          {donor ? (
            <section className={`acct-elig acct-o2 ${notEligible ? '' : 'ok'}`}>
              <BloodGroupBadge group={profile.bloodGroup} size={56} />
              <div className="col gap-2 grow">
                <span className="t-title-m bold">{notEligible ? tr('Not eligible right now', 'এখন রক্ত দিতে পারবেন না') : tr('You can donate now', 'এখন রক্ত দিতে পারবেন')}</span>
                {notEligible ? <span className="t-body-s">{notEligible}</span> : null}
              </div>
              <Icon name={notEligible ? 'schedule' : 'check_circle'} size={28} />
            </section>
          ) : (
            <div className="acct-o2">
              <BecomeDonorCard onClick={() => s.requireLogin(tr('Add your donor details', 'রক্তদাতার তথ্য দিন'), () => {}, { donor: true })} />
            </div>
          )}

          {/* ---- তথ্য ---- */}
          <section className="card acct-infos acct-o5">
            <InfoLine icon="history" label={tr('Last donation', 'শেষ রক্তদান')} value={profile.lastDonationMillis != null ? formatDate(profile.lastDonationMillis) : tr('Never', 'কখনো দেননি')} />
            <InfoLine
              icon="person_outline"
              label={tr('Age · Weight', 'বয়স · ওজন')}
              value={[age != null ? num(age) + tr(' yrs', ' বছর') : null, profile.weightKg != null ? num(profile.weightKg) + tr(' kg', ' কেজি') : null].filter(Boolean).join(' · ')}
            />
            <InfoLine
              icon="call"
              label={tr('Phone', 'ফোন')}
              value={num(profile.phone)}
              trailing={needsPhoneCheck ? <Button variant="text" small onClick={() => setPhoneDialog(true)}>{tr('Verify', 'যাচাই করুন')}</Button> : null}
            />
            <InfoLine icon="location_on" label={tr('Area', 'এলাকা')} value={districtLabel(profile.placeName)} />
          </section>
        </div>

        <div className="acct-col acct-right">
          {/* নিজের ব্যবসার তথ্য আনা যায়নি: কারণ আর আবার চেষ্টা */}
          {s.workError ? (
            <section className="card danger acct-o3 col gap-4">
              <span className="t-title-s bold">{tr("Couldn't load your business", 'আপনার ব্যবসার তথ্য আনা যায়নি')}</span>
              <span className="t-body-s">{s.workError}</span>
              <div>
                <Button
                  variant="text"
                  small
                  busy={retrying}
                  onClick={async () => {
                    setRetrying(true);
                    await s.refreshWork().catch(() => {});
                    setRetrying(false);
                  }}
                >
                  {tr('Try again', 'আবার চেষ্টা করুন')}
                </Button>
              </div>
            </section>
          ) : null}
          {workLoaded && !owns && !businessNo ? (
            <div className="acct-o4">
              <BusinessQuestionCard
                onYes={() => nav('/work/add')}
                onNo={() => {
                  store.set(bizKey, '1');
                  setBusinessNo(true);
                }}
              />
            </div>
          ) : null}

          {/* ---- কাজ ---- */}
          <div className="acct-o6 col gap-12">
            <Menu>
              <ListRow icon="list_alt" title={tr('My Requests', 'আমার রিকোয়েস্ট')} onClick={() => nav('/my-requests')} />
              <ListRow icon="calendar_month" title={tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')} onClick={() => nav('/my-appointments')} />
              <ListRow icon="medication" title={tr('My prescriptions', 'আমার প্রেসক্রিপশন')} onClick={() => nav('/prescriptions')} />
              <ListRow
                icon="health_and_safety_filled"
                title={tr('Caregivers & family', 'কেয়ারগিভার ও পরিবার')}
                sub={tr('People who can help you in the app', 'যাঁরা অ্যাপে আপনার হয়ে কাজ করতে পারেন')}
                onClick={() => nav('/care')}
              />
              {assisting.data?.items?.length ? <ListRow icon="group_round" title={tr("Doctor's assistant", 'ডাক্তারের সহকারী')} onClick={() => nav('/work/rx')} /> : null}
            </Menu>
            <Menu>
              <ListRow
                icon="history"
                title={tr('Change last donation date', 'শেষ রক্তদানের তারিখ বদলান')}
                sub={profile.lastDonationMillis != null ? formatDate(profile.lastDonationMillis) : tr('Never donated', 'কখনো দেননি')}
                onClick={() => setDateDialog(true)}
              />
              <ListRow icon="edit" title={tr('Edit profile', 'প্রোফাইল এডিট')} onClick={() => nav('/profile/edit')} />
              {workLoaded && !owns && businessNo ? (
                <ListRow
                  icon="storefront"
                  title={tr('Add a business', 'ব্যবসা যোগ করুন')}
                  sub={tr('Ambulance, pharmacy, blood bank, lab', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি')}
                  onClick={() => nav('/work/add')}
                />
              ) : null}
              <div>
                <ListRow icon="workspace_premium" title={tr('Professional badge', 'পেশাদার ব্যাজ')} sub={badgeSub} onClick={badgeLocked ? undefined : () => nav('/health/badge')} />
                {badge && badge.statusReason && ['rejected', 'suspended'].includes(badge.status) ? (
                  <div className="acct-under"><IconLine icon="info">{tr('Reason: ', 'কারণ: ') + badge.statusReason}</IconLine></div>
                ) : null}
                {badge && s.uid && (docsMissing || docsRequested) ? (
                  <div className="acct-under">
                    <DocsNeededCard uid={s.uid} docs={docs} requested={docsRequested} note={String(badge.docsRequestNote ?? '')} onDone={() => s.reloadMe()} />
                  </div>
                ) : null}
              </div>
            </Menu>
            <Menu>
              <ListRow icon="settings_outline" title={tr('App settings', 'অ্যাপ সেটিংস')} sub={tr('Language, theme, notifications, area', 'ভাষা, থিম, নোটিফিকেশন, এলাকা')} onClick={() => nav('/settings')} />
              <ListRow icon="help" title={tr('Help & appeals', 'সাহায্য ও আপিল')} onClick={() => nav('/help')} />
              {isAdmin ? <ListRow icon="admin_panel_settings" title={tr('Admin', 'অ্যাডমিন')} href="/admin" /> : null}
            </Menu>
            <Menu>
              <ListRow icon="logout" title={tr('Log out', 'লগআউট')} onClick={logout} trailing={<span />} />
              <ListRow icon="delete" title={tr('Delete account', 'অ্যাকাউন্ট মুছুন')} danger onClick={() => { setDeleteError(null); setConfirmDelete(true); }} trailing={<span />} />
            </Menu>
          </div>
        </div>
      </div>

      {photoMenu ? <PhotoMenuDialog onClose={() => setPhotoMenu(false)} onChange={photo.pick} onRemove={photo.remove} /> : null}
      {dateDialog ? (
        <DonationDateDialog
          profile={profile}
          onClose={() => setDateDialog(false)}
          onSaved={(d) => {
            setDateDialog(false);
            s.setProfile({ ...profile, lastDonationMillis: d });
            toast(tr('Saved', 'সেভ হয়েছে'));
          }}
        />
      ) : null}
      {phoneDialog ? (
        <PhoneVerifyDialog
          onClose={() => setPhoneDialog(false)}
          onVerified={() => {
            setPhoneDialog(false);
            s.reloadMe();
          }}
        />
      ) : null}
      {confirmDelete ? (
        <ConfirmDialog
          title={tr('Delete your account?', 'অ্যাকাউন্ট মুছবেন?')}
          text={tr(
            'Your account will be deleted permanently after 30 days: profile, photo, phone number, requests, questions, answers, tips, appointments, badge and your businesses. Until then your listings and notifications are off and you are logged out everywhere. Log in again within 30 days to cancel and get everything back.',
            '৩০ দিন পর আপনার অ্যাকাউন্ট চিরতরে মুছে যাবে: প্রোফাইল, ছবি, ফোন নম্বর, রিকোয়েস্ট, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, ব্যাজ আর আপনার ব্যবসা। এর মধ্যে আপনার তালিকা আর নোটিফিকেশন বন্ধ থাকবে, সব ফোন থেকে লগআউট হবে। ৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে।',
          )}
          confirm={tr('Delete account', 'অ্যাকাউন্ট মুছুন')}
          danger
          busy={deleting}
          error={deleteError}
          onConfirm={deleteAccount}
          onClose={() => !deleting && setConfirmDelete(false)}
        />
      ) : null}
    </div>
  );
}
