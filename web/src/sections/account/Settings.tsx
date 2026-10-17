// অ্যাপ সেটিংস (অ্যাপের Settings.kt): ভাষা, থিম, নোটিফিকেশন, দূরত্ব, যাতায়াত খরচ, তালিকার গ্রুপ, এলাকা, অ্যাপ সম্পর্কে।
// প্রতিটা বদল সাথে সাথে সেভ হয়। অতিথি: শুধু ভাষা, থিম, এলাকা, ব্যবসা যোগ আর অ্যাপ সম্পর্কে।
import { useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChoiceChips, ErrorText, ListRow, Loading, Segmented, Switch } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { PlacePicker } from '@/ui/AreaPicker';
import { SubPageBar } from '@/shell/templates';
import { usePageTitle } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { num, setLang, tr, useLang } from '@/lib/i18n';
import { setTheme, useTheme, type Theme } from '@/lib/theme';
import { districtLabel } from '@/lib/geo';
import { BLOOD_GROUPS, FARE_AFTER_CHOICES, PUSH_PER_DAY_CHOICES, RADIUS_CHOICES } from '@/lib/rules';
import { compatibleGroups, effectiveNotifyGroups, effectiveViewGroups, saveSettings, type Profile } from '@/lib/profile';
import { useSession } from '@/lib/session';
import { PRIVACY_URL, SUPPORT_EMAIL, TERMS_URL } from './util';
import { LinkRow, useBack } from './nav';

function Card({ children }: { children: ReactNode }) {
  return <section className="acct-scard">{children}</section>;
}
function Label({ icon, children }: { icon: string; children: ReactNode }) {
  return (
    <h2 className="acct-slabel">
      <Icon name={icon} size={24} />
      <span>{children}</span>
    </h2>
  );
}
function Note({ children }: { children: ReactNode }) {
  return <p className="t-body-s muted">{children}</p>;
}

/** সব মানানসই গ্রুপ বাছা থাকলে null — রক্তের গ্রুপ বদলালে নিজে থেকে মিলে যায় */
function normalize(set: string[], def: string[]): string[] | null {
  const same = set.length === def.length && def.every((g) => set.includes(g));
  return same ? null : BLOOD_GROUPS.filter((g) => set.includes(g));
}

export function SettingsPage() {
  const lang = useLang();
  const theme = useTheme();
  const s = useSession();
  const nav = useNavigate();
  const title = tr('App settings', 'অ্যাপ সেটিংস');
  usePageTitle(title);
  const back = useBack(s.guest ? '/' : '/profile');
  const [error, setError] = useState<string | null>(null);
  const [areaOpen, setAreaOpen] = useState(false);
  const [areaBusy, setAreaBusy] = useState(false);
  const [areaError, setAreaError] = useState<string | null>(null);

  if (!s.ready || (!s.guest && !s.profile)) return <Loading />;
  const guest = s.guest;
  const profile = s.profile;

  function save(updated: Profile) {
    if (!s.uid) return;
    s.setProfile(updated);
    saveSettings(s.uid, updated).then(
      () => setError(null),
      () => setError(tr("Couldn't save. Check your internet.", 'সেভ হয়নি, ইন্টারনেট দেখুন।')),
    );
  }

  const appearance = (
    <Card>
      <Label icon="language">{tr('Language', 'ভাষা')}</Label>
      <div className="acct-seg">
        <Segmented value={lang} label={tr('Language', 'ভাষা')} options={[{ value: 'en', label: 'English' }, { value: 'bn', label: 'বাংলা' }]} onChange={(v) => setLang(v)} />
      </div>
      <hr className="divider" />
      <Label icon="dark_mode">{tr('Theme', 'থিম')}</Label>
      <div className="acct-seg">
        <Segmented<Theme>
          value={theme}
          label={tr('Theme', 'থিম')}
          options={[
            { value: 'system', label: tr('System', 'ফোনের মতো') },
            { value: 'light', label: tr('Light', 'লাইট') },
            { value: 'dark', label: tr('Dark', 'ডার্ক') },
          ]}
          onChange={setTheme}
        />
      </div>
    </Card>
  );

  let notifications: ReactNode = null;
  let distance: ReactNode = null;
  if (!guest && profile) {
    const compatible = compatibleGroups(profile);
    const notifySel = effectiveNotifyGroups(profile);
    notifications = (
      <Card>
        <div className="acct-srow">
          <Icon name="notifications" size={24} />
          <div className="grow">
            <Switch
              checked={profile.notify}
              onChange={(v) => save({ ...profile, notify: v })}
              label={tr('Notifications', 'নোটিফিকেশন')}
              sub={tr('When someone nearby needs blood', 'আশেপাশে কারো রক্ত লাগলে')}
            />
          </div>
        </div>
        {profile.notify ? (
          <div className="acct-sbody">
            <span className="t-label-l">{tr('Maximum per day', 'দিনে সর্বোচ্চ')}</span>
            <ChoiceChips
              options={PUSH_PER_DAY_CHOICES.map((n) => ({ value: n, label: num(n) }))}
              value={profile.maxPushPerDay}
              onChange={(n: number) => save({ ...profile, maxPushPerDay: n })}
            />
            {compatible.length === 0 ? (
              <Note>{tr('Add your blood group in your profile to get donation requests.', 'রক্তদানের নোটিফিকেশন পেতে প্রোফাইলে রক্তের গ্রুপ দিন।')}</Note>
            ) : (
              <>
                <span className="t-label-l">{tr('For patient blood groups', 'রোগীর যে গ্রুপগুলোর জন্য')}</span>
                <ChoiceChips
                  multi
                  options={compatible.map((g) => ({ value: g, label: g }))}
                  value={notifySel}
                  onChange={(next: string[]) => {
                    if (next.length) save({ ...profile, notifyGroups: normalize(next, compatible) });
                  }}
                />
                <Note>{tr(`Only groups you (${profile.bloodGroup}) can donate to.`, `শুধু যাদের আপনি (${profile.bloodGroup}) রক্ত দিতে পারেন।`)}</Note>
              </>
            )}
          </div>
        ) : null}
      </Card>
    );
    distance = (
      <Card>
        <Label icon="location_on">{tr('How far can you go to donate?', 'রক্ত দিতে কতদূর যেতে পারবেন?')}</Label>
        <div className="acct-sbody">
          <ChoiceChips
            options={RADIUS_CHOICES.map((k) => ({ value: k, label: num(k) + tr(' km', ' কিমি') }))}
            value={profile.maxKm}
            onChange={(k: number) => save({ ...profile, maxKm: k })}
          />
          <Note>{tr("You'll be notified only for requests within this distance.", 'এই দূরত্বের মধ্যে রিকোয়েস্ট হলেই নোটিফিকেশন পাবেন।')}</Note>
        </div>
        <hr className="divider" />
        <Label icon="directions_run">{tr('Travel cost', 'যাতায়াত খরচ')}</Label>
        <div className="acct-sbody">
          <ChoiceChips
            options={[
              { value: 0, label: tr('Not needed', 'লাগবে না') },
              ...FARE_AFTER_CHOICES.map((k) => ({ value: k, label: tr(`Beyond ${num(k)} km`, `${num(k)} কিমির বেশি হলে`) })),
            ]}
            value={profile.fareAfterKm ?? 0}
            onChange={(k: number) => save({ ...profile, fareAfterKm: k || null })}
          />
          <Note>
            {tr(
              "If a request doesn't cover travel cost and it's farther than this, you won't be notified.",
              'রিকোয়েস্টে যাতায়াত খরচ না থাকলে আর এর চেয়ে দূরে হলে নোটিফিকেশন আসবে না।',
            )}
          </Note>
        </div>
      </Card>
    );
  }

  const area = (
    <Card>
      {!guest && profile ? (
        <>
          <Label icon="tune">{tr('Show requests for', 'যে গ্রুপের রিকোয়েস্ট দেখাবে')}</Label>
          <div className="acct-sbody">
            <ChoiceChips
              multi
              options={BLOOD_GROUPS.map((g) => ({ value: g, label: g }))}
              value={effectiveViewGroups(profile)}
              onChange={(next: string[]) => {
                if (next.length) save({ ...profile, viewGroups: normalize(next, compatibleGroups(profile)) });
              }}
            />
          </div>
          <hr className="divider" />
        </>
      ) : null}
      <ListRow icon="location_on" title={tr('Area', 'এলাকা')} sub={districtLabel(s.place?.placeName) || tr('Choose area', 'এলাকা বেছে নিন')} onClick={() => setAreaOpen(true)} />
    </Card>
  );

  const business = guest ? (
    <Card>
      <ListRow
        icon="storefront"
        title={tr('Add your business', 'আপনার ব্যবসা যোগ করুন')}
        sub={tr('Ambulance, pharmacy, blood bank, lab', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি')}
        onClick={() => s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav('/work/add'))}
      />
    </Card>
  ) : null;

  const about = (
    <Card>
      <LinkRow icon="privacy_tip" title={tr('Privacy policy', 'প্রাইভেসি পলিসি')} href={PRIVACY_URL} />
      <LinkRow icon="list_alt" title={tr('Terms of use', 'ব্যবহারের শর্ত')} href={TERMS_URL} />
      <ListRow icon="mail" title={tr('Contact support', 'সাপোর্টে যোগাযোগ')} sub={SUPPORT_EMAIL} href={`mailto:${SUPPORT_EMAIL}`} trailing={<span />} />
      <div className="acct-srow static">
        <Icon name="info" size={24} />
        <div className="col gap-2">
          <span className="t-body-l">{tr('Version', 'ভার্সন')}</span>
          <span className="t-body-s muted">{tr('Web', 'ওয়েব')}</span>
        </div>
      </div>
    </Card>
  );

  return (
    <>
      <SubPageBar title={title} onBack={back} />
      <div className="form-page">
        <div className="form-inner acct-settings">
          <ErrorText>{error}</ErrorText>
          <div className="acct-scols">
            <div className="col gap-12">
              {appearance}
              {notifications}
              {distance}
            </div>
            <div className="col gap-12">
              {area}
              {business}
              {about}
            </div>
          </div>
        </div>
      </div>
      {areaOpen ? (
        <PlacePicker
          busy={areaBusy}
          error={areaError}
          onClose={() => setAreaOpen(false)}
          onPicked={async (pl) => {
            setAreaBusy(true);
            setAreaError(null);
            try {
              await s.setPlace(pl);
              setAreaOpen(false);
            } catch (e) {
              setAreaError(errorText(e));
            } finally {
              setAreaBusy(false);
            }
          }}
        />
      ) : null}
    </>
  );
}
