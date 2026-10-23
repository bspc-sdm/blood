// রসিদ ছাপা: ডেস্কটপে OfflineStore.print (থার্মাল ৫৮/৮০ মিমি ESC/POS বা A4); ওয়েবে ব্রাউজারের প্রিন্ট (PDF হিসেবেও রাখা যায়)।
// (@/offline-এর print() ওয়েবেও চলে, কিন্তু ওয়েবে এই পাতার Receipt.vue-ই ছাপা হয় — পর্দায় যা দেখছেন হুবহু তাই।)
// ওয়েবে পাতার একটা লুকানো কপি (.health-print .inv-print) ছাপা হয় — কাগজের মাপ ছাপার ঠিক আগে @page দিয়ে।
import { ref, watch } from 'vue';
import { store } from '@/lib/store';
import type { Invoice, Paper } from './model';
import { offlineStore } from './offline';

const PAPER_KEY = 'lf.rcptPaper';
const DIGIT_KEY = 'lf.rcptDigits';
const readPaper = (): Paper => {
  const v = store.get(PAPER_KEY);
  return v === '58' || v === '80' || v === 'A4' ? v : 'A4';
};
/** শেষ বাছা কাগজ (এই ব্রাউজারে) */
export const paperPref = ref<Paper>(readPaper());
watch(paperPref, (v) => store.set(PAPER_KEY, v));
/** রসিদের অঙ্ক: বাংলা মূল, ইংরেজি বাছা যায় */
export const digitPref = ref<'bn' | 'en'>(store.get(DIGIT_KEY) === 'en' ? 'en' : 'bn');
watch(digitPref, (v) => store.set(DIGIT_KEY, v));

const PAGE: Record<Paper, string> = {
  A4: '@page { size: A4; margin: 14mm; }',
  '80': '@page { size: 80mm auto; margin: 2mm; }',
  '58': '@page { size: 58mm auto; margin: 1mm; }',
};

/** ছাপা: ডেস্কটপে স্টোর, নইলে window.print() (রসিদের কপি পাতায় আগে থেকেই থাকতে হবে — ReceiptView রাখে) */
export async function printInvoice(inv: Invoice, paper: Paper, digits: 'bn' | 'en' = digitPref.value): Promise<'desktop' | 'browser'> {
  const s = offlineStore();
  if (s) {
    await s.print(inv, { paper, englishDigits: digits === 'en' });
    return 'desktop';
  }
  const el = document.createElement('style');
  el.setAttribute('data-inv-page', paper);
  el.textContent = `@media print { ${PAGE[paper]} }`;
  document.head.appendChild(el);
  try {
    window.print();
  } finally {
    // print() ডায়ালগ বন্ধ হওয়া পর্যন্ত আটকে থাকে (বেশির ভাগ ব্রাউজারে); afterprint-এ সরানো নিরাপদ
    const done = () => el.remove();
    window.addEventListener('afterprint', done, { once: true });
    setTimeout(done, 60_000);
  }
  return 'browser';
}
