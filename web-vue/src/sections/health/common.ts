// স্বাস্থ্য অংশের ছোট জিনিস (হিসাব): me, ব্যাজ, পথ, ছোট বার্তা, ছবি ছোট করা (অ্যাপের Photo.toDocumentBase64) — অ্যাপের Community.kt / Photo.kt-এর মতো।
import { computed, ref, watch, type ComputedRef, type Ref } from 'vue';
import { num, tr } from '@/lib/i18n';
import { useSession, type CommunityMe } from '@/lib/session';
import { TEST_MODE } from '@/lib/testmode';
import { canGoBack, type NavFn } from '@/lib/nav';

// ---------- me (ব্যাজ, অ্যাডমিন) ----------
/** সেশনের "me"; পরীক্ষার বিল্ডে window.__lfTest.me দিয়ে ব্যাজ/অ্যাডমিন বসানো যায় */
export function useMe(): ComputedRef<CommunityMe | null> {
  const s = useSession();
  return computed(() => {
    const t = TEST_MODE ? ((window.__lfTest as Record<string, unknown> | undefined)?.me as Partial<CommunityMe> | undefined) : undefined;
    if (t && !s.guest) return { ...(s.me ?? { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: true, unread: 0 }), ...t } as CommunityMe;
    return s.me;
  });
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

export const answersText = (n: number) => (n === 0 ? tr('No answers yet', 'এখনো উত্তর নেই') : tr(`${n} answer${n > 1 ? 's' : ''}`, `${num(n)}টি উত্তর`));

// ---------- পথ ----------
/** পেছনে: এই সাইটের ভেতরে আগের পাতা থাকলে সেখানে, নইলে fallback */
export function goBack(nav: NavFn, fallback: string) {
  if (canGoBack()) nav(-1);
  else nav(fallback, { replace: true });
}

/** কাজ শেষে ছোট বার্তা কয়েক সেকেন্ড পরে সরে যায় */
export function useFlash(): [Ref<string | null>, (m: string | null) => void] {
  const m = ref<string | null>(null);
  watch(m, (v, _o, onCleanup) => {
    if (!v) return;
    const t = setTimeout(() => (m.value = null), 6000);
    onCleanup(() => clearTimeout(t));
  });
  return [m, (v) => (m.value = v)];
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
