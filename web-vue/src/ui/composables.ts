// ছোট সাহায্যকারী: সময় গোনা, ক্লাস জোড়া, ঘরে ফোকাস (React-এর autoFocus)
import { onBeforeUnmount, ref, type Directive } from 'vue';

export const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');

/** সময় গুনতে: প্রতি সেকেন্ডে এখনকার সময় (ref) */
export function useNow(intervalMs = 1000) {
  const now = ref(Date.now());
  const t = setInterval(() => (now.value = Date.now()), intervalMs);
  onBeforeUnmount(() => clearInterval(t));
  return now;
}

/** v-focus — পাতায় বসার সাথে সাথে ফোকাস (React-এর autoFocus-এর মতো; v-focus="false" দিলে নয়) */
export const vFocus: Directive<HTMLElement, boolean | undefined> = {
  mounted(el, b) {
    if (b.value !== false) el.focus();
  },
};

/** নিয়ন্ত্রিত ঘর: মান বদলে (যেমন শুধু অঙ্ক) বসানো হলে ঘরেও সেটাই দেখায় */
export function inputValue(e: Event, transform?: (v: string) => string): string {
  const t = e.target as HTMLInputElement;
  const v = transform ? transform(t.value) : t.value;
  if (t.value !== v) t.value = v;
  return v;
}

/** কার্ড চাপলে খোলে (কিবোর্ডেও): Enter/Space */
export function cardKey(e: KeyboardEvent, onClick?: () => void) {
  if (onClick && (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
    e.preventDefault();
    onClick();
  }
}
