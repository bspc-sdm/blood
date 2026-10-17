// /work/add — কোন ব্যবসা যোগ করবেন (অ্যাপের AddBusinessDialog)
import { Link, useNavigate } from 'react-router-dom';
import { Loading, Tag } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { FormPage } from '@/shell/templates';
import { useSession } from '@/lib/session';
import { num, tr } from '@/lib/i18n';
import { KINDS, MAX_AMBULANCES, MAX_BLOODBANKS, businessIcon, businessName, type Kind } from './shared';

export function AddBusiness() {
  const s = useSession();
  const nav = useNavigate();
  const w = s.work;
  const count = (k: Kind) => (k === 'ambulance' ? w.ambulances.length : k === 'pharmacy' ? (w.pharmacy ? 1 : 0) : k === 'bloodbank' ? w.bloodBanks.length : w.lab ? 1 : 0);
  const max = (k: Kind) => (k === 'ambulance' ? MAX_AMBULANCES : k === 'bloodbank' ? MAX_BLOODBANKS : 1);
  // যেটা আগে থেকে আছে (একটাই হয়) বা সীমা ভরা — নিজের পাতাই খোলে
  const target = (k: Kind) => (count(k) >= max(k) ? `/work/${k}` : `/work/${k}/new`);
  const desc = (k: Kind) =>
    k === 'ambulance' ? tr('Patients nearby call you directly', 'কাছের রোগীরা সরাসরি ফোন করবেন')
      : k === 'pharmacy' ? tr('Licensed shop — calls, medicine requests, prescriptions', 'লাইসেন্সধারী দোকান — ফোন, ওষুধ খোঁজা, প্রেসক্রিপশন')
        : k === 'bloodbank' ? tr('Government, private or volunteer', 'সরকারি, বেসরকারি বা স্বেচ্ছাসেবী')
          : tr('Tests from prescriptions, reports in the app', 'প্রেসক্রিপশনের পরীক্ষা, রিপোর্ট অ্যাপেই');

  function pick(k: Kind) {
    s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav(target(k)));
  }

  return (
    <FormPage title={tr('What is your business?', 'আপনার কোন ব্যবসা?')} backTo="/work">
      {!s.guest && !s.workLoaded && !s.workError ? <Loading /> : (
        <div className="work-choose">
          {KINDS.map((k) => {
            const n = count(k);
            return (
              <button key={k} type="button" className="work-choice" onClick={() => pick(k)}>
                <span className="work-ic"><Icon name={businessIcon(k)} /></span>
                <span className="col gap-2 grow">
                  <span className="t-title-m">{businessName(k)}</span>
                  <span className="t-body-s muted">{desc(k)}</span>
                </span>
                {n > 0 ? <Tag tone="good">{max(k) > 1 ? tr(`Added ${n}`, `${num(n)}টা যোগ করা আছে`) : tr('Added', 'যোগ করা আছে')}</Tag> : null}
                <Icon name="chevron_right" className="work-chev" />
              </button>
            );
          })}
        </div>
      )}
      <p className="t-body-s muted">
        {tr('Doctors, nurses and health experts: apply for a professional badge in your profile.', 'ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞ: প্রোফাইলের “পেশাদার ব্যাজ” থেকে আবেদন করুন।')}{' '}
        <Link to="/health/badge" className="c-primary semi">{tr('Professional badge', 'পেশাদার ব্যাজ')}</Link>
      </p>
    </FormPage>
  );
}
