// কোন যোগাযোগের বোতাম দেখাব (অডিও কল · ভিডিও কল · মেসেজ) — সার্ভারের একটাই কাজ `contact_can {items}` (≤ ৫০),
// একই পাতায় যতগুলো ContactActions বসে সবগুলো একসাথে এক অনুরোধে (একই মুহূর্তে বসা সব জমিয়ে)।
// উত্তর ১ মিনিট মনে রাখা হয়। কাজটা সার্ভারে না থাকলে/ভুল হলে null — তখন পাতার নিজের হিসাব (show) চলে।
import { reactive } from 'vue';
import { call } from '@/lib/api';
import type { CallCtx } from './api';

export interface ContactItem { ctx: CallCtx; refId: string; peerUid?: string }
export interface ContactCan { canCall: boolean; canVideo: boolean; canMessage: boolean; blocked: boolean }

const TTL = 60_000;
/** চাবি → উত্তর (null = জানা নেই/ভুল) */
const cache = reactive(new Map<string, { at: number; v: ContactCan | null }>());
let queue = new Map<string, ContactItem>();
let timer: ReturnType<typeof setTimeout> | null = null;
/** সার্ভারে contact_can নেই (পুরোনো সার্ভার) — আর চাওয়া নয় */
let unsupported = false;

export const contactKey = (i: ContactItem) => `${i.ctx}|${i.refId}|${i.peerUid ?? ''}`;

async function flush() {
  timer = null;
  const batch = [...queue.entries()];
  queue = new Map();
  for (let i = 0; i < batch.length; i += 50) {
    const part = batch.slice(i, i + 50);
    try {
      const r = await call<{ items?: any[] }>('contact_can', { items: part.map(([, it]) => ({ ctx: it.ctx, refId: it.refId, ...(it.peerUid ? { peerUid: it.peerUid } : {}) })) });
      const got = Array.isArray(r?.items) ? r.items : [];
      const now = Date.now();
      for (const [key, it] of part) {
        // উত্তরের peerUid সার্ভার বসায় (না পাঠালেও) — তাই ctx + refId (+ পাঠানো peerUid) দিয়ে মেলানো
        const o = got.find((x) => x?.ctx === it.ctx && String(x?.refId) === it.refId && (!it.peerUid || x?.peerUid === it.peerUid));
        cache.set(key, {
          at: now,
          v: o ? { canCall: o.canCall === true, canVideo: o.canVideo === true, canMessage: o.canMessage === true, blocked: o.blocked === true } : null,
        });
      }
    } catch (e: any) {
      if (e?.status === 400 && /unknown|fixture missing/i.test(String(e?.message))) unsupported = true;
      const now = Date.now();
      for (const [key] of part) cache.set(key, { at: now, v: null });
    }
  }
}

/** এই প্রসঙ্গের উত্তর (reactive): undefined = আসছে, null = জানা নেই */
export function contactCan(it: ContactItem): ContactCan | null | undefined {
  const key = contactKey(it);
  const c = cache.get(key);
  if (c && Date.now() - c.at < TTL) return c.v;
  if (unsupported) return null;
  if (!queue.has(key)) {
    queue.set(key, it);
    timer ??= setTimeout(() => void flush(), 0);
  }
  return c?.v;
}
/** ব্লক/আনব্লকের পর, বা কল শেষে — আবার জানা */
export function forgetContactCan() {
  cache.clear();
}
