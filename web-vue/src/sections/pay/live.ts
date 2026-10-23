// SSE-র pay_* খবর (calls/events.ts-এর একই স্ট্রিম): pay_requested, pay_claimed (ব্যবসাকে), pay_confirmed, pay_rejected,
// pay_refunded, pay_reminder — কলের স্ট্রিম (calls/events.ts) বা কল বন্ধ থাকলে ফিচার-সুইচের স্ট্রিম (lib/features.ts) — এলে খোলা পাতাগুলো (পেমেন্ট, তালিকা, সারাংশ, অ্যাপয়েন্টমেন্ট/অর্ডার/রসিদ) নতুন করে আনে।
// যতক্ষণ পেমেন্টের কোনো পাতা খোলা, ততক্ষণ একবারই শোনা হয়। SSE না চললে পাতাগুলো নিজেরা মাঝে মাঝে দেখে (sseLive)।
import { onBeforeUnmount, onMounted } from 'vue';
import { sseLive, tapEvents } from '@/calls/events';
import { tapFeatureStream } from '@/lib/features';
import { invalidatePay } from './api';

export const PAY_EVENTS = ['pay_requested', 'pay_claimed', 'pay_confirmed', 'pay_rejected', 'pay_refunded', 'pay_reminder'];
export { sseLive };

type Listener = (name: string, data: any) => void;
const listeners = new Set<Listener>();
let users = 0;
let untap: (() => void) | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;

/** একটা ঘটনা: pay_* হলে (একসাথে কয়েকটা এলে একবার) আবার আনা; true = পেমেন্টের খবর ছিল */
export function handlePayEvent(name: string, data: any): boolean {
  // pay_refund_claimed, pay_methods_changed, pay_frozen … নতুন নামও (সব pay_*)
  if (!PAY_EVENTS.includes(name) && !name.startsWith('pay_')) return false;
  if (!timer) {
    timer = setTimeout(() => {
      timer = null;
      void invalidatePay();
    }, 150);
  }
  listeners.forEach((f) => f(name, data));
  return true;
}

/** পেমেন্টের পাতায়: খোলা থাকলে শোনা (onEvent দিলে নিজেও খবর পায়) */
export function usePayLive(onEvent?: Listener) {
  onMounted(() => {
    users++;
    if (!untap) {
      const a = tapEvents(handlePayEvent);
      const b = tapFeatureStream((name) => void handlePayEvent(name, null));
      untap = () => {
        a();
        b();
      };
    }
    if (onEvent) listeners.add(onEvent);
  });
  onBeforeUnmount(() => {
    users--;
    if (onEvent) listeners.delete(onEvent);
    if (users <= 0 && untap) {
      untap();
      untap = null;
      users = 0;
    }
  });
}
