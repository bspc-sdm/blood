// অ্যাকাউন্ট অংশ: /profile, /profile/edit, /settings, /inbox[/:id], /help[/new|/:id], /care, /login
import { matchPath, useLocation } from 'react-router-dom';
import { NotFound } from '@/pages/NotFound';
import { ProfilePage } from './Profile';
import { EditProfilePage } from './EditProfile';
import { SettingsPage } from './Settings';
import { InboxPage } from './Inbox';
import { HelpPage } from './Help';
import { CarePage } from './Care';
import { LoginPage } from './Login';
import './account.css';

const is = (pattern: string, path: string) => !!matchPath({ path: pattern, end: true }, path);

export default function Section() {
  const { pathname } = useLocation();
  const p = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  if (is('/profile', p)) return <ProfilePage />;
  if (is('/profile/edit', p)) return <EditProfilePage />;
  if (is('/settings', p)) return <SettingsPage />;
  if (is('/inbox', p) || is('/inbox/:id', p)) return <InboxPage />;
  if (is('/help', p) || is('/help/:id', p)) return <HelpPage />;
  if (is('/care', p)) return <CarePage />;
  if (is('/login', p)) return <LoginPage />;
  return <NotFound />;
}
