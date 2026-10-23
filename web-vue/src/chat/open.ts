// "মেসেজ" বোতাম (calls/ContactActions.vue-র `message` emit) → কথোপকথন খোলা (chat_open) → /messages/:threadId।
// সম্পর্ক না থাকলে / ব্লক থাকলে সার্ভারের "না" বাংলায় টোস্টে।
import { ref } from 'vue';
import { router } from '@/router';
import { toast } from '@/ui/toast';
import { featureOn } from '@/lib/features';
import { refusalOf, type ChatCtx } from './api';
import { openThreadFor } from './store';
import { T, refusalText } from './text';

/** চাপার পর উত্তর আসা পর্যন্ত (বোতাম বন্ধ রাখতে) */
export const opening = ref(false);

/** অন্য ধরনের প্রসঙ্গ (কলের 'order' ইত্যাদি) মেসেজের প্রসঙ্গে */
export function chatCtxOf(ctx: string, refKind?: 'rx' | 'lab'): ChatCtx | null {
  if (ctx === 'blood' || ctx === 'appointment' || ctx === 'ambulance' || ctx === 'rx_order' || ctx === 'lab_order') return ctx;
  if (ctx === 'order') return refKind === 'lab' ? 'lab_order' : 'rx_order';
  return null;
}

export async function openChat(ctx: ChatCtx | string, refId: string, peerUid?: string | null): Promise<string | null> {
  const c = chatCtxOf(ctx);
  if (!c || !refId || opening.value) return null;
  opening.value = true;
  try {
    const threadId = await openThreadFor(c, refId, peerUid || undefined);
    if (!threadId) throw new Error(T.openFailed());
    await router.push(`/messages/${encodeURIComponent(threadId)}`);
    return threadId;
  } catch (e) {
    const r = refusalOf(e);
    toast(r === 'other' ? T.openFailed() : refusalText(r));
    return null;
  } finally {
    opening.value = false;
  }
}

/** ContactActions-এর `@message` সরাসরি: <ContactActions … message @message="openChatAt" /> */
export const openChatAt = (t: { ctx: string; refId: string; peerUid?: string }) => void openChat(t.ctx, t.refId, t.peerUid);

/** "মেসেজ" বোতাম দেখাবে কি না (ফিচার সুইচ `chat`) — <ContactActions :message="chatOn()" …> */
export const chatOn = () => featureOn('chat');
