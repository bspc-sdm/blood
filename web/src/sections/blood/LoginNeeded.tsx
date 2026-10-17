// লগইন ছাড়া (অতিথি) যে পাতায় অ্যাকাউন্ট লাগে: ছোট কার্ড, "লগইন" চাপলে লগইন-গেট
import { Button, EmptyState } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';

export function LoginNeeded({ reason, icon = 'person_outline', then, donor }: { reason: string; icon?: string; then?: () => void; donor?: boolean }) {
  const s = useSession();
  return (
    <EmptyState icon={icon} title={reason}>
      <Button onClick={() => s.requireLogin(reason, then ?? (() => {}), { donor })}>{tr('Log in', 'লগইন')}</Button>
    </EmptyState>
  );
}
