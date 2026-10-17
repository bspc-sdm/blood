// খোলস: মোবাইলে অ্যাপের মতো (উপরে বার, নিচে ট্যাব); ট্যাবলেটে বাঁয়ে সরু রেল; ডেস্কটপে বাঁয়ে পুরো মেনু।
import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { Icon } from '@/ui/Icon';
import { Button, IconButton, cx } from '@/ui/kit';
import { num, tr, useLang } from '@/lib/i18n';
import { useSession, workHas } from '@/lib/session';
import { useCareMode, setCareMode } from '@/lib/care';
import { useChrome } from './chrome';
import { LoginFlow } from '@/account/LoginFlow';

interface NavItem { to: string; label: string; icon: string; iconOn: string; match: (p: string) => boolean }

export function useNavItems(): NavItem[] {
  useLang();
  const { work, guest } = useSession();
  const items: NavItem[] = [
    { to: '/', label: tr('Home', 'হোম'), icon: 'nav_home', iconOn: 'nav_home_fill', match: (p) => p === '/' || p.startsWith('/request') || p.startsWith('/my-requests') },
    { to: '/appointment', label: tr('Appointment', 'অ্যাপয়েন্টমেন্ট'), icon: 'nav_event', iconOn: 'nav_event_fill', match: (p) => p.startsWith('/appointment') || p.startsWith('/my-appointments') },
    { to: '/health', label: tr('Health', 'স্বাস্থ্য'), icon: 'nav_health', iconOn: 'nav_health_fill', match: (p) => p.startsWith('/health') || p.startsWith('/prescriptions') },
    { to: '/services', label: tr('Services', 'সেবা'), icon: 'nav_services', iconOn: 'nav_services_fill', match: (p) => p.startsWith('/services') },
  ];
  // "আমার কাজ" শুধু যাঁর নিজের ব্যবসা, চেম্বার বা বাসায় সেবা আছে
  if (!guest && workHas(work)) items.push({ to: '/work', label: tr('My work', 'আমার কাজ'), icon: 'nav_work', iconOn: 'nav_work_fill', match: (p) => p.startsWith('/work') });
  return items;
}

function MenuAvatar({ photo }: { photo?: string | null }) {
  return (
    <span className="menu-avatar">
      <span className="pic">{photo ? <img src={photo.startsWith('data:') ? photo : `data:image/jpeg;base64,${photo}`} alt="" /> : <Icon name="person_filled" />}</span>
      <span className="ham"><Icon name="menu_round" /></span>
    </span>
  );
}

function TopActions() {
  const s = useSession();
  const nav = useNavigate();
  if (!s.ready) return <div className="top-actions" />;
  if (s.guest) {
    return (
      <div className="top-actions">
        <IconButton icon="settings_outline" label={tr('App settings', 'অ্যাপ সেটিংস')} onClick={() => nav('/settings')} />
        <Button variant="tonal" className="login-btn" onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
      </div>
    );
  }
  return (
    <div className="top-actions">
      <IconButton icon="notifications_round" label={tr('Notifications', 'নোটিফিকেশন')} badge={s.me?.unread || null} onClick={() => nav('/inbox')} className="bell" />
      <button type="button" className="icon-btn" aria-label={tr('Menu and profile', 'মেনু ও প্রোফাইল')} onClick={() => nav('/profile')}>
        <MenuAvatar photo={s.profile?.photo} />
      </button>
    </div>
  );
}

function Logo() {
  return (
    <Link to="/" className="logo" aria-label="Lifolo">
      <span className="logo-mark"><Icon name="bloodtype" /></span>
      <span className="logo-name">Lifolo</span>
    </Link>
  );
}

function SideNav({ items }: { items: NavItem[] }) {
  const { pathname } = useLocation();
  const s = useSession();
  const extra: { to: string; label: string; icon: string; show: boolean }[] = [
    { to: '/profile', label: tr('Profile', 'প্রোফাইল'), icon: 'person_outline', show: !s.guest },
    { to: '/settings', label: tr('Settings', 'সেটিংস'), icon: 'settings_outline', show: true },
    { to: '/help', label: tr('Help', 'সাহায্য'), icon: 'help', show: true },
  ];
  return (
    <aside className="side-nav" aria-label={tr('Main menu', 'মূল মেনু')}>
      <Logo />
      <nav className="col gap-4">
        {items.map((it) => {
          const on = it.match(pathname);
          return (
            <NavLink key={it.to} to={it.to} className="nav-link" aria-current={on ? 'page' : undefined}>
              <span className="ico"><Icon name={on ? it.iconOn : it.icon} /></span>
              <span className="label">{it.label}</span>
            </NavLink>
          );
        })}
      </nav>
      <div className="nav-bottom">
        <div className="nav-sep" />
        {extra.filter((e) => e.show).map((e) => {
          const on = pathname.startsWith(e.to);
          return (
            <NavLink key={e.to} to={e.to} className="nav-link" aria-current={on ? 'page' : undefined}>
              <span className="ico"><Icon name={e.icon} /></span>
              <span className="label">{e.label}</span>
            </NavLink>
          );
        })}
        <p className="nav-extra">{tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা')}</p>
      </div>
    </aside>
  );
}

function BottomNav({ items }: { items: NavItem[] }) {
  const { pathname } = useLocation();
  return (
    <nav className={cx('bottom-nav', items.length > 4 && 'five')} aria-label={tr('Main menu', 'মূল মেনু')}>
      {items.map((it) => {
        const on = it.match(pathname);
        return (
          <Link key={it.to} to={it.to} aria-current={on ? 'page' : undefined}>
            <span className="ind" />
            <span className="sp" />
            <Icon name={on ? it.iconOn : it.icon} />
            <small>{it.label}</small>
            <span className="sp" />
          </Link>
        );
      })}
    </nav>
  );
}

/** কেয়ারগিভার মোডে উপরে ফিতা: কার হয়ে কাজ করছেন */
function CareBanner() {
  const care = useCareMode();
  if (!care) return null;
  return (
    <div className="row" style={{ background: 'var(--tertiary-container)', color: 'var(--on-tertiary-container)', padding: '8px 16px' }}>
      <Icon name="group_round" size={20} />
      <span className="grow t-label-l">{tr(`Acting for ${care.name}`, `${care.name}-এর হয়ে কাজ করছেন`)}</span>
      <Button variant="text" small onClick={() => setCareMode(null)}>{tr('Stop', 'বন্ধ করুন')}</Button>
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const items = useNavItems();
  const { title, detailOpen, subOpen } = useChrome();
  const s = useSession();
  const { pathname } = useLocation();
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const f = () => setScrolled(window.scrollY > 4);
    f();
    window.addEventListener('scroll', f, { passive: true });
    return () => window.removeEventListener('scroll', f);
  }, []);
  // পাতা বদলালে উপরে; নিজের কাজ/প্রোফাইলে গেলে কেয়ারগিভার মোড বন্ধ (অন্যের হয়ে নিজের চেম্বার চালানো নয়)
  useEffect(() => {
    window.scrollTo(0, 0);
    if (/^\/(work|profile|settings|care)(\/|$)/.test(pathname)) setCareMode(null);
  }, [pathname]);
  return (
    <div className={cx('shell', detailOpen > 0 && 'detail-open', subOpen > 0 && 'sub-open')}>
      <SideNav items={items} />
      <div className="frame">
        <header className={cx('topbar main-bar', scrolled && 'scrolled')}>
          <Logo />
          <h1 className="page-title ellipsis">{title}</h1>
          <TopActions />
        </header>
        <CareBanner />
        <main className="content" id="main">{children}</main>
      </div>
      <BottomNav items={items} />
      {s.pending ? <LoginFlow /> : null}
      {s.disabledMessage ? (
        <div className="overlay" role="alertdialog" aria-modal="true">
          <div className="dialog small">
            <h2 className="t-title-l">{tr('Logged out', 'লগআউট হয়েছে')}</h2>
            <p className="t-body-m pre">{s.disabledMessage}</p>
            <div className="dialog-actions"><Button onClick={s.clearDisabled}>{tr('OK', 'ঠিক আছে')}</Button></div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

export const unreadLabel = (n: number) => (n > 99 ? num('99+') : num(n));
