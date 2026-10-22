// নোটিফিকেশনের ধরন অনুযায়ী চালু/বন্ধ (সার্ভার: notify_prefs_get / notify_prefs_set, backend NotifyCats)।
// বন্ধ করলে শুধু ফোনের নোটিফিকেশন বন্ধ — ঘণ্টার তালিকায় (inbox) সব থাকে। "account" সবসময় চালু।
// blood_nearby = প্রোফাইলের notify (একই জিনিস; সার্ভার দুটো একসাথে রাখে)।
import { call } from './api';
import { tr } from './i18n';

export const NOTIFY_CATS = ['blood_nearby', 'blood_emergency', 'my_request', 'appointments', 'rx', 'ambulance', 'community', 'business'] as const;
export type NotifyCat = (typeof NOTIFY_CATS)[number];
export interface NotifyPrefs { all: boolean; cats: Record<NotifyCat, boolean> }

/** বন্ধ করার আগে "নিশ্চিত?" — জরুরি খবর */
export const URGENT_CATS: NotifyCat[] = ['blood_emergency', 'ambulance'];

/** শুধু ব্যবসা/পেশাদার ভূমিকা থাকলে দেখানো হয় */
export const BUSINESS_CAT: NotifyCat = 'business';

export const defaultPrefs = (): NotifyPrefs => ({ all: true, cats: Object.fromEntries(NOTIFY_CATS.map((c) => [c, true])) as Record<NotifyCat, boolean> });

/** সার্ভারের উত্তর থেকে (না থাকা ঘর = চালু) */
function normalize(r: Partial<NotifyPrefs> | null | undefined): NotifyPrefs {
  return { all: r?.all !== false, cats: Object.fromEntries(NOTIFY_CATS.map((c) => [c, r?.cats?.[c] !== false])) as Record<NotifyCat, boolean> };
}

export async function loadNotifyPrefs(): Promise<NotifyPrefs> {
  return normalize(await call<NotifyPrefs>('notify_prefs_get'));
}

/** শুধু যা বদলেছে তা পাঠানো (সার্ভার মিলিয়ে নেয়); উত্তরে পুরো সেটিংস */
export async function saveNotifyPrefs(change: { all?: boolean; cats?: Partial<Record<NotifyCat, boolean>> }): Promise<NotifyPrefs> {
  return normalize(await call<NotifyPrefs>('notify_prefs_set', change));
}

export function catLabel(c: NotifyCat | 'account'): string {
  switch (c) {
    case 'blood_nearby': return tr('When someone nearby needs blood', 'আশেপাশে রক্ত লাগলে'); // bn-ok
    case 'blood_emergency': return tr('Emergency blood appeals', 'জরুরি রক্তের আহ্বান'); // bn-ok
    case 'my_request': return tr('My blood requests', 'আমার রক্তের অনুরোধ'); // bn-ok
    case 'appointments': return tr('Appointments and serial', 'অ্যাপয়েন্টমেন্ট আর সিরিয়াল'); // bn-ok
    case 'rx': return tr('Prescriptions, medicines and lab', 'প্রেসক্রিপশন, ওষুধ আর ল্যাব'); // bn-ok
    case 'ambulance': return tr('Ambulance', 'অ্যাম্বুলেন্স');
    case 'community': return tr('Questions and tips', 'প্রশ্নোত্তর আর টিপস'); // bn-ok
    case 'business': return tr('My business', 'আমার ব্যবসা'); // bn-ok
    case 'account': return tr('Account and security (always on)', 'অ্যাকাউন্ট আর নিরাপত্তা (সবসময় চালু)'); // bn-ok
  }
}

export function catSub(c: NotifyCat | 'account'): string {
  switch (c) {
    case 'blood_nearby': return tr('Requests near you, and when you can donate again', 'আশেপাশে কারো রক্ত লাগলে, আর আবার রক্ত দিতে পারলে'); // bn-ok
    case 'blood_emergency': return tr('Lifolo team appeals for donors, and when they end', 'ডোনারদের জন্য Lifolo টিমের জরুরি আহ্বান, আর তা শেষ হলে'); // bn-ok
    case 'my_request': return tr("Donor replies on your requests, and news on requests you agreed to", 'আপনার অনুরোধে ডোনারের সাড়া, আর যে অনুরোধে আপনি রাজি হয়েছেন তার খবর'); // bn-ok
    case 'appointments': return tr('Confirmed, cancelled, your turn is near, late notices', 'নিশ্চিত, বাতিল, সিরিয়াল কাছে এলে, দেরির খবর'); // bn-ok
    case 'rx': return tr('New prescriptions, refill reminders, pharmacy and lab updates, reports', 'নতুন প্রেসক্রিপশন, ওষুধ শেষের আগে, ফার্মেসি আর ল্যাবের খবর, রিপোর্ট'); // bn-ok
    case 'ambulance': return tr('Fare offers, the car is coming, cancelled', 'ভাড়ার প্রস্তাব, গাড়ি আসছে, বাতিল'); // bn-ok
    case 'community': return tr('When your question is answered, tips', 'আপনার প্রশ্নের উত্তর এলে, টিপস'); // bn-ok
    case 'business': return tr('New orders and requests, listing and badge news', 'নতুন অর্ডার আর অনুরোধ, তালিকা আর ব্যাজের খবর'); // bn-ok
    case 'account': return tr("Account, messages from the Lifolo team, caregiver invitations — can't be turned off", 'অ্যাকাউন্ট, Lifolo টিমের বার্তা, কেয়ারগিভারের আমন্ত্রণ — বন্ধ করা যায় না'); // bn-ok
  }
}

export const allLabel = () => tr('All notifications', 'সব নোটিফিকেশন'); // bn-ok
export const allSub = () =>
  tr('Off: nothing on the phone except account news. The notification list still shows everything.', 'বন্ধ করলে ফোনে অ্যাকাউন্টের খবর ছাড়া কিছু আসবে না। নোটিফিকেশনের তালিকায় সব থাকবে।'); // bn-ok

/** জরুরি ধরন (বা সব) বন্ধের আগে — পাতার ভেতরের "নিশ্চিত?" */
export const offTitle = () => tr("You won't get urgent news — sure?", 'জরুরি খবর পাবেন না — নিশ্চিত?'); // bn-ok
export function offText(target: 'all' | NotifyCat): string {
  if (target === 'all') return tr('Emergency blood appeals and ambulance news will not reach your phone either.', 'জরুরি রক্তের আহ্বান আর অ্যাম্বুলেন্সের খবরও ফোনে আসবে না।'); // bn-ok
  if (target === 'ambulance') return tr("Ambulance offers and updates won't reach your phone — only the notification list.", 'অ্যাম্বুলেন্সের প্রস্তাব আর খবর ফোনে আসবে না — শুধু নোটিফিকেশনের তালিকায় থাকবে।'); // bn-ok
  return tr("Emergency blood appeals won't reach your phone — only the notification list.", 'জরুরি রক্তের আহ্বান ফোনে আসবে না — শুধু নোটিফিকেশনের তালিকায় থাকবে।'); // bn-ok
}
export const offConfirm = () => tr('Turn off', 'বন্ধ করুন'); // bn-ok
