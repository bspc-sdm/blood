// অফলাইন/ডেস্কটপের সব লেখা এক জায়গায় (নতুন লেখা, React ওয়েবে আগের রূপ নেই: প্রতিটা লাইনে bn-ok)
import { num, tr, trPlain } from '@/lib/i18n';
import type { InvoiceKind, PayMethod } from './types';

/** রসিদের লেখা — রসিদ সবসময় বাংলায় (অঙ্ক আলাদা বাছাই) */
export const R = {
  kind: { pharmacy: 'ফার্মেসি রসিদ', lab: 'ল্যাব বিল', chamber: 'চেম্বার ফি রসিদ', ambulance: 'অ্যাম্বুলেন্স ভাড়ার রসিদ' } as Record<InvoiceKind, string>, // bn-ok
  no: 'রসিদ নং', // bn-ok
  date: 'তারিখ', // bn-ok
  name: 'নাম', // bn-ok
  phone: 'ফোন', // bn-ok
  item: 'বিবরণ', // bn-ok
  amount: 'টাকা', // bn-ok
  subtotal: 'মোট', // bn-ok
  disc: 'ছাড়', // bn-ok
  total: 'সর্বমোট', // bn-ok
  paid: 'পরিশোধ', // bn-ok
  due: 'বাকি', // bn-ok
  void: 'বাতিল', // bn-ok
  reason: 'কারণ', // bn-ok
  offline: 'অফলাইনে তৈরি', // bn-ok
  verify: 'রসিদ যাচাই করুন', // bn-ok
  thanks: 'ধন্যবাদ', // bn-ok
  method: { cash: 'ক্যাশ', bkash: 'বিকাশ', nagad: 'নগদ', card: 'কার্ড', other: 'অন্যান্য' } as Record<PayMethod, string>, // bn-ok
  testTitle: 'টেস্ট প্রিন্ট', // bn-ok
  testLine: 'বাংলা লেখা ঠিকমতো ছাপা হলে প্রিন্টার প্রস্তুত', // bn-ok
};

/** অ্যাপের লেখা (ভাষা অনুযায়ী) */
export const T = {
  notDesktop: () => trPlain('Offline mode works only in the Lifolo desktop app', 'অফলাইন শুধু Lifolo ডেস্কটপ অ্যাপে চলে'), // bn-ok
  needOnline: () => trPlain('Connect to the internet once to set up this business on this computer', 'এই কম্পিউটারে ব্যবসাটা চালু করতে একবার ইন্টারনেটে যুক্ত হোন'), // bn-ok
  notReady: () => trPlain('Open the business first', 'আগে ব্যবসাটা খুলুন'), // bn-ok
  loginExpired: () => trPlain('Lifolo has run without internet for 7 days. Connect to the internet once, then it works again.', '৭ দিন ধরে ইন্টারনেট ছাড়া চলছে। একবার ইন্টারনেটে যুক্ত হোন — তারপর আবার চলবে।'), // bn-ok
  revoked: () => trPlain('This computer was removed from the business. Offline sales are off.', 'এই কম্পিউটার ব্যবসা থেকে সরানো হয়েছে। অফলাইন বিক্রি বন্ধ।'), // bn-ok
  locked: () => trPlain('Unlock Lifolo first', 'আগে Lifolo খুলুন'), // bn-ok
  invalid: () => trPlain('Check the entry', 'লেখা ঠিক আছে কি না দেখুন'), // bn-ok
  invoiceMissing: () => trPlain('Receipt not found on this computer', 'রসিদটা এই কম্পিউটারে নেই'), // bn-ok
  alreadyVoid: () => trPlain('This receipt is already void', 'রসিদটা আগেই বাতিল'), // bn-ok
  tooMuch: () => trPlain('More than the due amount', 'বাকির চেয়ে বেশি'), // bn-ok
  printFailed: () => trPlain('Could not print', 'ছাপা যায়নি'), // bn-ok
  noPrinter: () => trPlain('Choose a printer in desktop settings', 'ডেস্কটপ সেটিংসে প্রিন্টার বাছাই করুন'), // bn-ok

  // ডেস্কটপ সেটিংস
  settings: () => tr('Desktop settings', 'ডেস্কটপ সেটিংস'), // bn-ok
  printer: () => tr('Receipt printer', 'রসিদের প্রিন্টার'), // bn-ok
  printerHint: () => trPlain('58/80 mm thermal printers print directly. A4 opens the print window.', '৫৮/৮০ মিমি থার্মাল প্রিন্টারে সরাসরি ছাপা হয়। A4 হলে প্রিন্টের জানালা খোলে।'), // bn-ok
  choosePrinter: () => tr('Choose printer', 'প্রিন্টার বাছাই করুন'), // bn-ok
  noPrinters: () => trPlain('No printer found. Install the printer driver in Windows first.', 'কোনো প্রিন্টার পাওয়া যায়নি। আগে Windows-এ প্রিন্টারের ড্রাইভার বসান।'), // bn-ok
  paper: () => tr('Paper', 'কাগজ'), // bn-ok
  mm58: () => tr('58 mm', '৫৮ মিমি'), // bn-ok
  mm80: () => tr('80 mm', '৮০ মিমি'), // bn-ok
  testPrint: () => tr('Test print', 'টেস্ট প্রিন্ট'), // bn-ok
  printed: () => trPlain('Sent to the printer', 'প্রিন্টারে পাঠানো হয়েছে'), // bn-ok
  drawer: () => tr('Open cash drawer on cash sales', 'ক্যাশে বিক্রি হলে ক্যাশ ড্রয়ার খুলুন'), // bn-ok
  digitsEn: () => tr('English digits on receipts', 'রসিদে ইংরেজি অঙ্ক'), // bn-ok
  refresh: () => tr('Refresh', 'রিফ্রেশ'),
  startup: () => tr('Start Lifolo when Windows starts', 'Windows চালু হলে Lifolo চালু হবে'), // bn-ok
  notifications: () => tr('Notifications', 'নোটিফিকেশন'), // bn-ok
  notifyHint: () => trPlain('Calls and new orders show a Windows notification while Lifolo runs (also in the tray).', 'Lifolo চালু থাকলে (ট্রেতেও) কল আর নতুন অর্ডারে Windows নোটিফিকেশন আসে।'), // bn-ok
  pin: () => tr('PIN lock', 'পিন লক'), // bn-ok
  pinHint: () => trPlain('After 10 minutes without use, Lifolo locks. Open it with this PIN.', '১০ মিনিট ব্যবহার না হলে Lifolo লক হয়ে যায়। এই পিন দিয়ে খুলবেন।'), // bn-ok
  changePin: () => tr('Change PIN', 'পিন বদলান'), // bn-ok
  setPin: () => tr('Set a PIN', 'পিন সেট করুন'), // bn-ok
  setPinTitle: () => trPlain('Set a PIN for this computer', 'এই কম্পিউটারের জন্য পিন দিন'), // bn-ok
  newPin: () => tr('New PIN (4–6 digits)', 'নতুন পিন (৪–৬ সংখ্যা)'), // bn-ok
  againPin: () => tr('PIN again', 'আবার পিন'), // bn-ok
  pinMismatch: () => trPlain('The two PINs do not match', 'দুটো পিন মেলেনি'), // bn-ok
  pinShort: () => trPlain('Use 4 to 6 digits', '৪ থেকে ৬টা সংখ্যা দিন'), // bn-ok
  pinSaved: () => trPlain('PIN saved', 'পিন সেভ হয়েছে'), // bn-ok
  save: () => tr('Save', 'সেভ করুন'),
  sync: () => tr('Offline sync', 'অফলাইন সিঙ্ক'), // bn-ok
  pendingN: (n: number) => trPlain(`${n} waiting to sync`, `${num(n)}টা সিঙ্কের অপেক্ষায়`), // bn-ok
  rejectedN: (n: number) => trPlain(`${n} not accepted by the server`, `${num(n)}টা সার্ভার নেয়নি`), // bn-ok
  allSynced: () => trPlain('Everything is synced', 'সব সিঙ্ক হয়েছে'), // bn-ok
  syncNow: () => tr('Sync now', 'এখন সিঙ্ক করুন'), // bn-ok
  syncing: () => trPlain('Syncing…', 'সিঙ্ক হচ্ছে…'), // bn-ok
  lastSync: (t: string) => trPlain(`Last sync: ${t}`, `শেষ সিঙ্ক: ${t}`), // bn-ok
  offlineNow: () => tr('Offline', 'অফলাইন'), // bn-ok
  device: () => tr('This computer', 'এই কম্পিউটার'), // bn-ok
  series: (s: string) => trPlain(`Receipt series ${s}`, `রসিদের সিরিজ ${s}`), // bn-ok
  update: () => tr('Update', 'আপডেট'), // bn-ok
  version: (v: string) => trPlain(`Version ${v}`, `ভার্সন ${num(v)}`), // bn-ok
  checkUpdate: () => tr('Check for update', 'আপডেট দেখুন'), // bn-ok
  upToDate: () => trPlain('Lifolo is up to date', 'সর্বশেষ ভার্সন চলছে'), // bn-ok
  updateReady: (v: string) => trPlain(`New version ${v} is available`, `নতুন ভার্সন ${num(v)} এসেছে`), // bn-ok
  updateNow: () => tr('Update now', 'এখন আপডেট করুন'), // bn-ok
  updating: () => trPlain('Updating… Lifolo will restart.', 'আপডেট হচ্ছে… Lifolo আবার চালু হবে।'), // bn-ok
  updateFailed: () => trPlain('Update failed. Try again later.', 'আপডেট হয়নি। পরে আবার চেষ্টা করুন।'), // bn-ok

  // লক
  lockTitle: () => trPlain('Lifolo is locked', 'Lifolo লক করা'), // bn-ok
  enterPin: () => tr('Enter PIN', 'পিন দিন'), // bn-ok
  unlock: () => tr('Unlock', 'খুলুন'), // bn-ok
  wrongPin: () => trPlain('Wrong PIN', 'ভুল পিন'), // bn-ok
  waitPin: (s: number) => trPlain(`Too many tries. Try again in ${s} seconds.`, `অনেকবার ভুল। ${num(s)} সেকেন্ড পর আবার চেষ্টা করুন।`), // bn-ok
  forgotPin: () => tr('Forgot PIN? Log out', 'পিন ভুলে গেছেন? লগআউট করুন'), // bn-ok
  forgotHint: () => trPlain('Unsynced receipts stay on this computer and sync after you log in again.', 'সিঙ্ক না হওয়া রসিদ এই কম্পিউটারেই থাকে, আবার লগইন করলে সিঙ্ক হবে।'), // bn-ok

  // নোটিফিকেশন
  callIn: () => trPlain('Incoming call', 'কল আসছে'), // bn-ok
  videoIn: () => trPlain('Incoming video call', 'ভিডিও কল আসছে'), // bn-ok
  callFrom: (name: string) => trPlain(`${name} is calling`, `${name} কল করছেন`), // bn-ok
  newOrder: () => trPlain('New order', 'নতুন অর্ডার'), // bn-ok
  inTray: () => trPlain('Lifolo keeps running in the tray. Quit from the tray icon.', 'Lifolo ট্রেতে চালু আছে। বন্ধ করতে ট্রের আইকনে চাপুন।'), // bn-ok
  rejectedNote: (n: number) => trPlain(`The server did not accept ${n} offline entries. Check desktop settings.`, `সার্ভার ${num(n)}টা অফলাইন এন্ট্রি নেয়নি। ডেস্কটপ সেটিংস দেখুন।`), // bn-ok
};
