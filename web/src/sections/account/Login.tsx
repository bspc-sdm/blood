// /login?next=/path — লগইন থাকলে সরাসরি next (না থাকলে প্রোফাইল); নইলে লগইনের ডায়ালগ খোলে, লগইন হলে next-এ
import { useEffect, useRef } from 'react';
import { Navigate, useSearchParams } from 'react-router-dom';
import { Button, EmptyState, Loading } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';
import { tr, useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { safeNext } from './util';

export function LoginPage() {
  useLang();
  const s = useSession();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next')) ?? '/profile';
  const opened = useRef(false);
  usePageTitle(tr('Log in', 'লগইন'));

  // একবারই নিজে থেকে খোলে (বন্ধ করলে আবার জোর করে খোলে না)
  useEffect(() => {
    if (s.ready && s.guest && !opened.current) {
      opened.current = true;
      s.login();
    }
  }, [s.ready, s.guest, s.login]);

  if (!s.ready) return <Loading />;
  if (!s.guest) return <Navigate to={next} replace />;
  return (
    <div className="acct-page">
      <EmptyState icon="person_outline" title={tr('Log in', 'লগইন')} text={tr('We will email you a code. No password needed.', 'ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।')}>
        <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
      </EmptyState>
    </div>
  );
}
