// ফার্মেসি ও প্যাথলজির কাজের পাতার সাধারণ হিসাব (কম্পোনেন্ট: PartnerPage, PartnerGate, CodeBox, Panel, ModeTag …)
import { computed } from 'vue';
import { useQueryClient } from '@tanstack/vue-query';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';

/** চওড়া পর্দা: টেবিল; নইলে কার্ড */
export const useWide = () => useMedia('(min-width: 1000px)');
/** পাশে সেটিং-এর ঘরসহ পাতার ছক (অনলাইন অর্ডার): মাঝের জায়গা ছোট, তাই ছক শুধু বড় পর্দায় — ১৩৬৬ পর্দায় কাজের বোতাম কেটে যেত */
export const useWideWithSide = () => useMedia('(min-width: 1600px)');

/** দরজা খোলা কি না (React-এর usePartnerGate() === null): লগইন, কাজ আনা, ব্যবসা অনুমোদিত */
export function usePartnerReady(kind: 'pharmacy' | 'lab') {
  const s = useSession();
  return computed(() => {
    if (!s.ready || s.guest || !s.workLoaded) return false;
    const biz = (kind === 'pharmacy' ? s.work.pharmacy : s.work.lab) as { status: string } | null;
    return !!biz && biz.status === 'approved';
  });
}

/** সার্ভারের কাজের পর ক্যাশ নতুন করে আনা (কাজের নাম ধরে) */
export function useInvalidate() {
  const qc = useQueryClient();
  return (...actions: string[]) => qc.invalidateQueries({ predicate: (q) => actions.includes(String(q.queryKey[0])) });
}

/** Enter চাপলে পরের ঘরে (একই data-rxp-nav দলের) — শেষ ঘরে onLast */
export function focusNext(el: HTMLElement, group: string, onLast?: () => void) {
  const all = Array.from(document.querySelectorAll<HTMLElement>(`[data-rxp-nav="${group}"]:not([disabled])`));
  const i = all.indexOf(el);
  const next = i >= 0 ? all[i + 1] : undefined;
  if (next) {
    next.focus();
    if (next instanceof HTMLInputElement) next.select();
  } else onLast?.();
}
