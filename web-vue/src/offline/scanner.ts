// USB বারকোড স্ক্যানার (কিবোর্ডের মতো লেখে — "keyboard wedge"): খুব দ্রুত পরপর অক্ষর, শেষে Enter।
// মানুষের টাইপ (ধীর) আলাদা করা যায় ফাঁক দেখে। ওয়েবেও চলে (ব্রাউজারেও স্ক্যানার কিবোর্ডই)।
//
//   onBarcode((code) => addItemByCode(code))             // ফেরত দেয় বন্ধ করার ফাংশন
//   useBarcode((code) => …)                               // Vue: পাতা বন্ধ হলে নিজেই থামে
//
// স্ক্যান ধরা পড়লে শেষের Enter আটকানো হয় (ফর্ম জমা পড়ে না)। ইনপুটে লেখা অক্ষরগুলো সরানো হয় না —
// পর্দা চাইলে `opts.clearInput` দিয়ে সেই ইনপুট খালি করতে পারে।
import { onBeforeUnmount, onMounted } from 'vue';

export interface ScanOptions {
  /** কমপক্ষে কত অক্ষর (ডিফল্ট ৪) */
  minLength?: number;
  /** দুটো অক্ষরের মাঝে সর্বোচ্চ ফাঁক, ms (ডিফল্ট ৫০) */
  maxGapMs?: number;
  /** স্ক্যানের লেখা যে ইনপুটে পড়েছিল সেটা খালি করা */
  clearInput?: boolean;
}

export function scanDetector(onScan: (code: string, target: EventTarget | null) => void, opts: ScanOptions = {}, now: () => number = () => performance.now()) {
  const min = opts.minLength ?? 4;
  const gap = opts.maxGapMs ?? 50;
  let buf = '';
  let last = 0;
  let fastRun = 0;
  return (e: KeyboardEvent): boolean => {
    const t = now();
    if (e.key === 'Enter' || e.key === 'Tab') {
      const ok = buf.length >= min && fastRun >= buf.length - 1 && t - last <= gap * 3;
      const code = buf;
      buf = '';
      fastRun = 0;
      if (ok) {
        e.preventDefault();
        e.stopPropagation();
        const el = e.target as HTMLInputElement | null;
        if (opts.clearInput && el && 'value' in el && typeof el.value === 'string' && el.value.endsWith(code)) {
          el.value = el.value.slice(0, -code.length);
          el.dispatchEvent(new Event('input', { bubbles: true }));
        }
        onScan(code, e.target);
        return true;
      }
      return false;
    }
    if (e.key.length !== 1 || e.ctrlKey || e.altKey || e.metaKey) return false;
    if (buf && t - last <= gap) fastRun++;
    else if (buf) {
      buf = '';
      fastRun = 0;
    }
    buf += e.key;
    last = t;
    return false;
  };
}

export function onBarcode(cb: (code: string) => void, opts: ScanOptions = {}): () => void {
  const h = scanDetector((c) => cb(c), opts);
  const listener = (e: KeyboardEvent) => void h(e);
  window.addEventListener('keydown', listener, true);
  return () => window.removeEventListener('keydown', listener, true);
}

export function useBarcode(cb: (code: string) => void, opts: ScanOptions = {}) {
  let stop: (() => void) | null = null;
  onMounted(() => {
    stop = onBarcode(cb, opts);
  });
  onBeforeUnmount(() => stop?.());
}
