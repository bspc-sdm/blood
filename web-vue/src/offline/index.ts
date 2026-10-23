// অফলাইন স্টোর — পর্দাগুলো শুধু এটাই import করে:  import { offline } from '@/offline'
//
//   if (offline.isDesktop()) {
//     await offline.ready('pharmacy', bizId);
//     const { invoice } = await offline.record({ type: 'sale', data: { lines, customer } });
//     await offline.print(invoice!, { paper: '80' });
//   } else {
//     // ওয়েব/ফোন: আগের মতো সার্ভারে (books_sale_add …)
//   }
//
// ওয়েবে (isDesktop() false) ইঞ্জিনের কোড বিল্ডেই থাকে না: ডেস্কটপের শুরু (desktop-main.ts) installOffline() দিয়ে আসলটা বসায়।
// print() ওয়েবেও চলে (ব্রাউজারের প্রিন্ট — A4 বা ৫৮/৮০ মিমি মাপের পাতা)।
import { T } from './text';
import { OfflineError, type OfflineStatus, type OfflineStore } from './types';

export * from './types';
export { onBarcode, useBarcode, scanDetector, type ScanOptions } from './scanner';

const notDesktop = () => new OfflineError(T.notDesktop(), 'not_desktop');

const webStore: OfflineStore = {
  isDesktop: () => false,
  ready: () => Promise.reject(notDesktop()),
  items: () => Promise.resolve([]),
  labPrices: () => Promise.resolve({ items: [], fees: [] }),
  chamberDay: (date) => Promise.resolve({ date, chambers: [], appointments: [], fees: {} }),
  invoices: () => Promise.resolve([]),
  record: () => Promise.reject(notDesktop()),
  pending: () => 0,
  syncNow: () => Promise.resolve(),
  onChange: () => () => {},
  role: () => null,
  requireOwner: () => Promise.resolve(true),
  print: async (invoice, opts) => {
    const { printHtml } = await import('./print/html');
    await printHtml(invoice, opts);
  },
};

let impl: OfflineStore = webStore;
let shell: 'web' | 'desktop' = 'web';

/** কোন খোলসে চলছে — ডেস্কটপের শুরু (boot.ts) markDesktopShell() ডাকে; ওয়েবের বিল্ডে Tauri-র কোনো নাম থাকে না */
export const appShell = () => shell;
export function markDesktopShell() {
  shell = 'desktop';
}

/** ডেস্কটপের শুরুতে একবার (desktop-main.ts) */
export function installOffline(x: OfflineStore) {
  impl = x;
}

/** পর্দার জন্য — সবসময় এটাই (ভেতরে ওয়েব বা ডেস্কটপ) */
export const offline: OfflineStore = {
  isDesktop: () => impl.isDesktop(),
  ready: (kind, bizId) => impl.ready(kind, bizId),
  items: (q) => impl.items(q),
  labPrices: () => impl.labPrices(),
  chamberDay: (date) => impl.chamberDay(date),
  invoices: (query) => impl.invoices(query),
  record: (op) => impl.record(op),
  pending: () => impl.pending(),
  syncNow: () => impl.syncNow(),
  onChange: (cb: (s: OfflineStatus) => void) => impl.onChange(cb),
  print: (invoice, opts) => impl.print(invoice, opts),
  role: () => impl.role(),
  requireOwner: () => impl.requireOwner(),
};

export const isDesktop = () => impl.isDesktop();
