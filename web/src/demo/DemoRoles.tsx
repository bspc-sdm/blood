// ===== ডেমো (শুধু পরীক্ষার জন্য) — ডেমো মুছলে এই ফোল্ডার আর LoginFlow/Profile-এর "ডেমো" লাইনগুলো মুছবেন =====
// লগইন পাতায় রোলের তালিকা: চাপলেই সেই ডেমো অ্যাকাউন্টে ঢোকা (ইমেইল/কোড নেই)। সার্ভারে ডেমো চালু না থাকলে কিছুই দেখায় না।
import { useEffect, useState } from 'react';
import { signInWithCustomToken, signOut } from 'firebase/auth';
import { useQueryClient } from '@tanstack/react-query';
import { auth } from '@/lib/firebase';
import { postPublic, errorText } from '@/lib/api';
import { setCareMode } from '@/lib/care';
import { tr } from '@/lib/i18n';
import { Dialog, ErrorText, ListRow, toast } from '@/ui/kit';

interface Role { key: string; name: string; role: string }

export const isDemoUid = (uid?: string | null) => !!uid && uid.startsWith('demo-');

export function DemoRoleList({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient();
  const [roles, setRoles] = useState<Role[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    postPublic<{ active: boolean; roles: Role[] }>('/api/auth/verify-code', { demoRoles: true })
      .then((r) => { if (live && r.active) setRoles(r.roles); })
      .catch(() => {});
    return () => { live = false; };
  }, []);

  async function pick(key: string) {
    setBusy(key);
    setError(null);
    try {
      const r = await postPublic<{ token?: string; restored?: boolean }>('/api/auth/verify-code', { demo: key });
      if (!r.token) throw new Error(tr('Login failed', 'লগইন হয়নি'));
      setCareMode(null);
      if (auth.currentUser) await signOut(auth);
      qc.clear();
      await signInWithCustomToken(auth, r.token);
      if (r.restored) toast(tr('Account deletion cancelled.', 'অ্যাকাউন্ট মোছা বাতিল হয়েছে।'));
      onDone();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  }

  if (!roles.length) return null;
  return (
    <section className="col gap-8" aria-label={tr('Demo', 'ডেমো')} style={{ marginTop: 8, paddingTop: 12, borderTop: '1px solid var(--surface-variant)' }}>
      <p className="t-title-s">{tr('Demo (for testing)', 'ডেমো (পরীক্ষার জন্য)')}</p>
      <div className="col">
        {roles.map((r) => (
          <ListRow key={r.key} icon="person_outline" title={r.role} sub={busy === r.key ? tr('Logging in…', 'ঢুকছে…') : r.name} onClick={busy ? undefined : () => pick(r.key)} />
        ))}
      </div>
      <ErrorText>{error}</ErrorText>
    </section>
  );
}

/** প্রোফাইলে "রোল বদলান" — ডেমো অ্যাকাউন্টে থাকলেই দেখায় */
export function DemoSwitchDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  return (
    <Dialog onClose={onClose} title={tr('Switch role', 'রোল বদলান')}>
      <DemoRoleList onDone={onDone} />
    </Dialog>
  );
}
