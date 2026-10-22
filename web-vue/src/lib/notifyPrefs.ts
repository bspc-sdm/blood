// নোটিফিকেশনের ধরন অনুযায়ী চালু/বন্ধ (সার্ভার: notify_prefs_get / notify_prefs_set, backend NotifyCats)।
// বন্ধ করলে শুধু ফোনে আসা বন্ধ — ঘণ্টার তালিকায় (inbox) সব থাকে। "account" সবসময় চালু, পাঠানো হয় না।
// blood_nearby = প্রোফাইলের notify (একই জিনিস; সার্ভার দুটো একসাথে রাখে)। লেখা অ্যাপের NotifyCats.kt-এর মতো।
import { call } from './api';
import { tr } from './i18n';

export const NOTIFY_CATS = ['blood_nearby', 'blood_emergency', 'my_request', 'appointments', 'rx', 'ambulance', 'community', 'business'] as const;
export type NotifyCat = (typeof NOTIFY_CATS)[number];
export interface NotifyPrefs { all: boolean; cats: Record<NotifyCat, boolean> }
export interface NotifyChange { all?: boolean; cats?: Partial<Record<NotifyCat, boolean>> }

/** বন্ধ করার আগে পাতার ভেতরে "নিশ্চিত?" — জরুরি খবর */
export const URGENT_CATS: readonly NotifyCat[] = ['blood_emergency', 'ambulance'];

/** শুধু ব্যবসা/পেশাদার ভূমিকা থাকলে দেখানো হয় */
export const BUSINESS_CAT: NotifyCat = 'business';

/** সার্ভারের উত্তর থেকে (না থাকা ঘর = চালু) */
export function normalizePrefs(r: Partial<NotifyPrefs> | null | undefined): NotifyPrefs {
  return { all: r?.all !== false, cats: Object.fromEntries(NOTIFY_CATS.map((c) => [c, r?.cats?.[c] !== false])) as Record<NotifyCat, boolean> };
}

/** বদলটা বসানো (আগেরটা না বদলে) */
export function withChange(p: NotifyPrefs, ch: NotifyChange): NotifyPrefs {
  return { all: ch.all ?? p.all, cats: { ...p.cats, ...ch.cats } };
}

// সেভ না হলে ফেরানো: শুধু এই বদলের ঘরগুলো, আর তাও যদি এখনো এই বদলের মানেই থাকে
// (মাঝে আরেকটা বদল সেই ঘর ছুঁয়ে থাকলে সেটাই থাকে)।
export function revertChange(cur: NotifyPrefs, before: NotifyPrefs, ch: NotifyChange): NotifyPrefs {
  const cats = { ...cur.cats };
  for (const [c, v] of Object.entries(ch.cats ?? {}) as [NotifyCat, boolean][]) if (cats[c] === v) cats[c] = before.cats[c];
  const all = ch.all != null && cur.all === ch.all ? before.all : cur.all;
  return { all, cats };
}

export async function loadNotifyPrefs(): Promise<NotifyPrefs> {
  return normalizePrefs(await call<NotifyPrefs>('notify_prefs_get'));
}

/** শুধু যা বদলেছে তা পাঠানো (সার্ভার মিলিয়ে নেয়); উত্তরে পুরো সেটিংস */
export async function saveNotifyPrefs(change: NotifyChange): Promise<NotifyPrefs> {
  return normalizePrefs(await call<NotifyPrefs>('notify_prefs_set', { ...change }));
}

export function catLabel(c: NotifyCat | 'account'): string {
  switch (c) {
    case 'blood_nearby': return tr('When someone nearby needs blood', 'আশেপাশে রক্ত লাগলে'); // bn-ok
    case 'blood_emergency': return tr('Emergency blood calls', 'জরুরি রক্তের আহ্বান'); // bn-ok
    case 'my_request': return tr('My blood requests', 'আমার রক্তের অনুরোধ'); // bn-ok
    case 'appointments': return tr('Appointments and queue', 'অ্যাপয়েন্টমেন্ট আর সিরিয়াল'); // bn-ok
    case 'rx': return tr('Prescriptions, medicines and lab', 'প্রেসক্রিপশন, ওষুধ আর ল্যাব'); // bn-ok
    case 'ambulance': return tr('Ambulance', 'অ্যাম্বুলেন্স');
    case 'community': return tr('Questions and tips', 'প্রশ্নোত্তর আর টিপস'); // bn-ok
    case 'business': return tr('My business', 'আমার ব্যবসা'); // bn-ok
    case 'account': return tr('Account and security (always on)', 'অ্যাকাউন্ট আর নিরাপত্তা (সবসময় চালু)'); // bn-ok
  }
}

export function catSub(c: NotifyCat | 'account'): string {
  switch (c) {
    case 'blood_nearby': return tr('Requests near you that match your blood group', 'কাছের রিকোয়েস্ট, যাদের আপনি রক্ত দিতে পারেন'); // bn-ok
    case 'blood_emergency': return tr('Urgent calls for blood in your area', 'এলাকায় জরুরি রক্তের ডাক'); // bn-ok
    case 'my_request': return tr('When donors respond to your request', 'আপনার রিকোয়েস্টে কেউ রাজি হলে বা সরে গেলে'); // bn-ok
    case 'appointments': return tr('Bookings, reminders and your turn in the queue', 'বুকিং, রিমাইন্ডার আর সিরিয়াল এগোলে'); // bn-ok
    case 'rx': return tr('New prescriptions, medicine orders and lab reports', 'নতুন প্রেসক্রিপশন, ওষুধের অর্ডার আর ল্যাব রিপোর্ট'); // bn-ok
    case 'ambulance': return tr('Updates on the ambulance you called', 'আপনার ডাকা অ্যাম্বুলেন্সের খবর'); // bn-ok
    case 'community': return tr('Answers to your questions and health tips', 'আপনার প্রশ্নের উত্তর আর স্বাস্থ্য টিপস'); // bn-ok
    case 'business': return tr('Orders, bookings and approvals for your business or chamber', 'আপনার ব্যবসা বা চেম্বারের অর্ডার, বুকিং আর অনুমোদন'); // bn-ok
    case 'account': return tr("Login, caregivers, corrections and support replies. These can't be turned off.", 'লগইন, কেয়ারগিভার, তথ্য সংশোধন আর সাপোর্টের উত্তর — এগুলো বন্ধ করা যায় না।'); // bn-ok
  }
}

export const allLabel = () => tr('All notifications', 'সব নোটিফিকেশন'); // bn-ok
export const allSub = (on: boolean) =>
  on
    ? tr('Choose below what you want to hear about', 'নিচে বেছে নিন কোন খবর পাবেন') // bn-ok
    : tr('Off: only account and security messages', 'বন্ধ: শুধু অ্যাকাউন্ট আর নিরাপত্তার খবর আসবে'); // bn-ok

/** অংশের নিচে: এগুলো শুধু ফোনে আসা ঠিক করে, ঘণ্টায় সব থাকে */
export const bellNote = () =>
  tr(
    'These choose what reaches your phone. The bell (notification list) still keeps every notice.',
    'এগুলো শুধু ফোনে কোন নোটিফিকেশন আসবে তা ঠিক করে। ঘণ্টার তালিকায় (ইনবক্স) সব খবর আগের মতোই থাকবে।', // bn-ok
  );

/** জরুরি ধরন বন্ধের আগে — পাতার ভেতরের "নিশ্চিত?" */
export const offTitle = () => tr("You won't get urgent news. Sure?", 'জরুরি খবর পাবেন না — নিশ্চিত?'); // bn-ok
export const offText = (c: NotifyCat) => `${catLabel(c)}: ${catSub(c)}`;
export const offConfirm = () => tr('Turn off', 'বন্ধ করুন'); // bn-ok
export const saveFailed = () => tr("Couldn't save. Check your internet.", 'সেভ হয়নি, ইন্টারনেট দেখুন।');
