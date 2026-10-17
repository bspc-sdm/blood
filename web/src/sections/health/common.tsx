// স্বাস্থ্য অংশের ছোট জিনিস: উপরের ট্যাব, লেখকের লাইন, ব্যাজ, রিপোর্ট/বদলানোর ডায়ালগ, ৯৯৯-এর লাইন,
// ছবি ছোট করা (অ্যাপের Photo.toDocumentBase64) — অ্যাপের Community.kt / Photo.kt-এর মতো।
import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate, type NavigateFunction } from 'react-router-dom';
import { Button, Dialog, ErrorText, IconButton, PillTabs, TextArea, TextInput } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { num, tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { store } from '@/lib/store';
import { useSession, type CommunityMe } from '@/lib/session';
import { TEST_MODE } from '@/lib/testmode';
import { errorText } from '@/lib/api';
import './health.css';

// ---------- me (ব্যাজ, অ্যাডমিন) ----------
/** সেশনের "me"; পরীক্ষার বিল্ডে window.__lfTest.me দিয়ে ব্যাজ/অ্যাডমিন বসানো যায় */
export function useMe(): CommunityMe | null {
  const s = useSession();
  const t = TEST_MODE ? ((window.__lfTest as Record<string, unknown> | undefined)?.me as Partial<CommunityMe> | undefined) : undefined;
  if (t && !s.guest) return { ...(s.me ?? { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: true, unread: 0 }), ...t } as CommunityMe;
  return s.me;
}
export const approvedBadge = (me: CommunityMe | null) => (me?.badge && me.badge.status === 'approved' ? me.badge.type : null);

// ---------- ব্যাজ ----------
export const BADGES = ['doctor', 'nurse', 'nutritionist', 'trainer', 'expert'];
export function badgeLabel(type: string): string {
  switch (type) {
    case 'doctor': return tr('Doctor', 'ডাক্তার');
    case 'nurse': return tr('Nurse', 'নার্স');
    case 'nutritionist': return tr('Nutritionist', 'পুষ্টিবিদ');
    case 'trainer': return tr('Fitness trainer', 'ফিটনেস ট্রেইনার');
    default: return tr('Health Expert', 'স্বাস্থ্য বিশেষজ্ঞ');
  }
}
export function badgeIcon(type: string): string {
  switch (type) {
    case 'doctor': return 'stethoscope';
    case 'nurse': return 'medical_services';
    case 'nutritionist': return 'restaurant';
    case 'trainer': return 'fitness_center';
    default: return 'health_and_safety_filled';
  }
}

/** নামের পাশে ব্যাজ (অ্যাপের BadgeChip) */
export function BadgeChip({ type }: { type: string }) {
  return (
    <span className="health-badge">
      <Icon name="verified" />
      {badgeLabel(type)}
    </span>
  );
}

/** নাম · ব্যাজ · কখন (অ্যাপের AuthorLine) */
export function AuthorLine({ name, badge, createdAt }: { name: string | null; badge?: string | null; createdAt: number }) {
  return (
    <div className="health-author">
      <span className="t-label-l semi ellipsis">{name || tr('Anonymous', 'নাম প্রকাশে অনিচ্ছুক')}</span>
      {badge ? <BadgeChip type={badge} /> : null}
      <span className="t-label-s muted none">{timeAgo(createdAt)}</span>
    </div>
  );
}

export const EditedNote = () => <span className="t-label-s muted">{tr('Edited', 'সম্পাদিত')}</span>;

export const answersText = (n: number) => (n === 0 ? tr('No answers yet', 'এখনো উত্তর নেই') : tr(`${n} answer${n > 1 ? 's' : ''}`, `${num(n)}টি উত্তর`));

// ---------- উপরের ট্যাব: আলোচনা / টিপস + আমার প্রেসক্রিপশন ----------
export function HealthTabs({ value }: { value: 'discussion' | 'tips' | 'rx' }) {
  const nav = useNavigate();
  return (
    <div className="health-tabs">
      <PillTabs
        label={tr('Health', 'স্বাস্থ্য')}
        value={value}
        onChange={(k) => nav(k === 'rx' ? '/prescriptions' : `/health/${k}`)}
        tabs={[
          { key: 'discussion', label: tr('Discussion', 'আলোচনা'), icon: 'forum_outline', selectedIcon: 'forum_filled' },
          { key: 'tips', label: tr('Tips', 'টিপস'), icon: 'lightbulb_outline', selectedIcon: 'lightbulb_filled' },
          { key: 'rx', label: tr('My prescriptions', 'আমার প্রেসক্রিপশন'), icon: 'medication' },
        ]}
      />
    </div>
  );
}

/** তালিকার মাথা: বড় শিরোনাম + রিফ্রেশ (+ চওড়া পর্দায় কাজের বাটন) */
export function ListHeading({ title, onRefresh, refreshing, action }: { title: string; onRefresh?: () => void; refreshing?: boolean; action?: ReactNode }) {
  return (
    <div className="row">
      <h2 className="t-headline grow">{title}</h2>
      {action}
      {onRefresh ? <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={onRefresh} disabled={refreshing} className={refreshing ? 'health-spin' : undefined} /> : null}
    </div>
  );
}

// ---------- ৯৯৯ (অ্যাপের EmergencyNotice) ----------
export function EmergencyNotice({ k = 'health' }: { k?: string }) {
  const key = `lf.dismissed.999_${k}`;
  const [hidden, setHidden] = useState(() => store.get(key) === '1');
  if (hidden) return null;
  return (
    <div className="card tonal health-notice" role="note">
      <Icon name="info" />
      <span className="grow t-body-s">{tr('Emergency? Call 999 now. Answers here are general guidance, not a diagnosis.', 'জরুরি অবস্থায় এখনই ৯৯৯-এ ফোন করুন। এখানের উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।')}</span>
      <IconButton
        icon="close"
        small
        label={tr('Hide', 'সরান')}
        onClick={() => {
          store.set(key, '1');
          setHidden(true);
        }}
      />
    </div>
  );
}

// ---------- ডায়ালগ ----------
/** রিপোর্ট করবেন? (অ্যাপের ReportDialog) — নিশ্চিত হলে কাজটা চালায়, ভুল এলে দেখায় */
export function ReportDialog({ onClose, onConfirm }: { onClose: () => void; onConfirm: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      size="small"
      onClose={onClose}
      title={tr('Report this?', 'রিপোর্ট করবেন?')}
      actions={
        <>
          <Button variant="text" onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            variant="danger"
            busy={busy}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await onConfirm();
              } catch (e) {
                setErr(errorText(e));
                setBusy(false);
              }
            }}
          >
            {tr('Report', 'রিপোর্ট করুন')}
          </Button>
        </>
      }
    >
      <p className="t-body-m muted">{tr("Report if it's wrong, harmful or abusive. An admin will review it.", 'ভুল, ক্ষতিকর বা আপত্তিকর মনে হলে রিপোর্ট করুন। অ্যাডমিন দেখবেন।')}</p>
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

/** নিজের লেখা বদলানো (প্রশ্ন, উত্তর; টিপস হলে শিরোনামও) — অ্যাপের EditTextDialog */
export function EditTextDialog({
  title, initial, maxLen, initialTitle, onClose, onSave,
}: { title: string; initial: string; maxLen: number; initialTitle?: string; onClose: () => void; onSave: (text: string, head: string) => Promise<void> }) {
  const [text, setText] = useState(initial);
  const [head, setHead] = useState(initialTitle ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const ok = text.trim() && (initialTitle == null || head.trim());
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={title}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            disabled={!ok}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await onSave(text.trim(), head.trim());
              } catch (e) {
                setErr(errorText(e));
              }
              setBusy(false);
            }}
          >
            {tr('Save', 'সেভ করুন')}
          </Button>
        </>
      }
    >
      {initialTitle != null ? <TextInput label={tr('Title', 'শিরোনাম')} value={head} maxLength={120} onChange={(e) => setHead(e.target.value)} /> : null}
      <TextArea value={text} maxLength={maxLen} rows={6} onChange={(e) => setText(e.target.value)} aria-label={title} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- পথ ----------
/** পেছনে: এই সাইটের ভেতরে আগের পাতা থাকলে সেখানে, নইলে fallback */
export function goBack(nav: NavigateFunction, fallback: string) {
  const idx = (window.history.state as { idx?: number } | null)?.idx ?? 0;
  if (idx > 0) nav(-1);
  else nav(fallback, { replace: true });
}

/** কাজ শেষে ছোট বার্তা কয়েক সেকেন্ড পরে সরে যায় */
export function useFlash(): [string | null, (m: string | null) => void] {
  const [m, setM] = useState<string | null>(null);
  useEffect(() => {
    if (!m) return;
    const t = setTimeout(() => setM(null), 6000);
    return () => clearTimeout(t);
  }, [m]);
  return [m, setM];
}

// ---------- ছবি ----------
/**
 * কাগজের ছবি: লম্বা দিক সর্বোচ্চ ১৪০০ পিক্সেল, JPEG; base64 ২৮০ KB-র মধ্যে না আসা পর্যন্ত মান কমানো
 * (অ্যাপের Photo.toDocumentBase64)। ফেরত: শুধু base64 (data: ছাড়া)।
 */
export async function toDocumentBase64(file: File): Promise<string> {
  const fail = () => new Error(tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'));
  let src: CanvasImageSource;
  let w: number;
  let h: number;
  try {
    const b = await createImageBitmap(file, { imageOrientation: 'from-image' });
    src = b;
    w = b.width;
    h = b.height;
  } catch {
    const url = URL.createObjectURL(file);
    try {
      const img = await new Promise<HTMLImageElement>((ok, no) => {
        const i = new Image();
        i.onload = () => ok(i);
        i.onerror = () => no(fail());
        i.src = url;
      });
      src = img;
      w = img.naturalWidth;
      h = img.naturalHeight;
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  }
  if (!w || !h) throw fail();
  let maxSide = 1400;
  let quality = 75;
  for (;;) {
    const scale = Math.min(1, maxSide / Math.max(w, h));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w * scale));
    c.height = Math.max(1, Math.round(h * scale));
    const ctx = c.getContext('2d');
    if (!ctx) throw fail();
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(src, 0, 0, c.width, c.height);
    const url = c.toDataURL('image/jpeg', quality / 100);
    const b64 = url.slice(url.indexOf(',') + 1);
    if (b64.length <= 280 * 1024 || maxSide <= 700) return b64;
    if (quality > 55) quality -= 10;
    else maxSide -= 200;
  }
}

export const imgSrc = (b64: string) => (b64.startsWith('data:') ? b64 : `data:image/jpeg;base64,${b64}`);
