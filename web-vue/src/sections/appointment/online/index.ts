// অনলাইন (ভিডিও) চেম্বার: ফি bKash-এ (ডাক্তারের নিজের নম্বরে, অ্যাপে টাকা নেওয়া নয়), টাকার অবস্থা, কলের সময়ের জানালা।
// চুক্তি: scratchpad calls/CONTRACT.md ("Online chambers") আর সার্ভারের backend/docs/CALLS.md (spring-p15)।
// কল নিজে web-calls শাখার (/appointments/:id/call) — এখানে শুধু লিংক।
// এগুলো নতুন লেখা (React-এ আগের রূপ নেই) — তাই বাংলা লাইনে bn-ok।
import { tr } from '@/lib/i18n';

export type ChamberMode = 'in_person' | 'online';
/** rejected: ডাক্তার/সহকারী "পাইনি" বলেছেন (সার্ভার payState "rejected", অথবা "unpaid" + payRejected/কারণ) */
export type PayState = 'unpaid' | 'claimed' | 'paid' | 'rejected';

/** অ্যাপয়েন্টমেন্টের অনলাইন অংশ (my_appointments / rx_patients.appointments) */
export interface OnlinePart {
  online: boolean;
  payState: PayState;
  payRef: string;
  payReason: string;
  bkashNumber: string;
  fee: string;
  noFee: boolean;
  onlineNote: string;
  /** স্লট শুরুর সময় (ms) — সার্ভার সংখ্যা দিলে; নইলে 0, তখন date + start ("HH:MM", ঢাকা) থেকে */
  startAt: number;
  /** সার্ভার জানালে: ডাক্তার কলে এসেছিলেন (তখন "রিপোর্ট করুন" নয়) */
  doctorJoined: boolean;
}

const str = (v: unknown) => (typeof v === 'string' ? v : '');

export function parsePayState(o: any): PayState {
  const raw = o?.payState;
  if (raw === 'paid' || raw === 'claimed' || raw === 'rejected') return raw;
  if (o?.payRejected === true || str(o?.payRejectReason) || str(o?.payReason)) return 'rejected';
  return 'unpaid';
}
export function parseOnline(o: any): OnlinePart {
  return {
    online: o?.online === true || o?.mode === 'online' || o?.chamberMode === 'online',
    payState: parsePayState(o),
    payRef: str(o?.payRef),
    payReason: str(o?.payRejectReason) || str(o?.payReason),
    bkashNumber: str(o?.bkashNumber),
    fee: str(o?.fee),
    noFee: o?.noFee === true,
    onlineNote: str(o?.onlineNote),
    startAt: typeof o?.start === 'number' ? o.start : typeof o?.startAt === 'number' ? o.startAt : 0,
    doctorJoined: o?.doctorJoined === true,
  };
}

// ---------- bKash নম্বর ----------
/** লেখার সময়: শুধু অঙ্ক আর + (বাংলা অঙ্ক ইংরেজিতে) */
export const bkashInput = (v: string) => v.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d))).replace(/[^0-9+]/g, '').slice(0, 14);
/** সঠিক হলে ১১ অঙ্কের 01XXXXXXXXX, নইলে null (+88 / 88 বাদ) */
export function normBkash(v: string): string | null {
  const d = bkashInput(v).replace(/^\+?88(?=01)/, '');
  return /^01[3-9]\d{8}$/.test(d) ? d : null;
}
/** TrxID: অক্ষর আর অঙ্ক, বড় হাতের, সর্বোচ্চ ২০ */
export const trxInput = (v: string) => v.toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 20);

/** নোটে ফোন নম্বর চলে না (সার্ভার 400 দেয়) — ৭+ অঙ্ক একটানা (ফাঁকা/ড্যাশ বাদে, বাংলা অঙ্কসহ) */
export function hasPhone(v: string): boolean {
  const d = v.replace(/[০-৯]/g, (x) => String('০১২৩৪৫৬৭৮৯'.indexOf(x))).replace(/(\d)[\s.\-()]+(?=\d)/g, '$1');
  return /\d{7,}/.test(d);
}

// ---------- কলের সময় (সার্ভারের appt_join: স্লটের ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত) ----------
export const CALL_EARLY_MS = 15 * 60 * 1000;
export const CALL_LATE_MS = 3 * 3600 * 1000;
const DHAKA_MS = 6 * 3600 * 1000;
/** স্লট শুরু (ms): startAt, নইলে date (ঢাকার দিনের UTC মধ্যরাত) + "HH:MM" ঢাকার সময়, নইলে ঢাকার দিনের শুরু */
export function apptStartMs(a: { date: number; start: string; startAt?: number }): number {
  if (a.startAt && a.startAt > 0) return a.startAt;
  const m = /^(\d{1,2}):(\d{2})$/.exec(a.start || '');
  return a.date - DHAKA_MS + (m ? (+m[1] * 60 + +m[2]) * 60 * 1000 : 0);
}
export function inCallWindow(a: { date: number; start: string; startAt?: number }, now = Date.now()): boolean {
  const st = apptStartMs(a);
  return now >= st - CALL_EARLY_MS && now <= st + CALL_LATE_MS;
}
/** "ভিডিও কলে যুক্ত হোন" দেখানো যায়: অনলাইন, নিশ্চিত, আর সময়ের জানালার ভেতরে */
export const canJoinCall = (a: { online: boolean; status: string; date: number; start: string; startAt?: number }, now = Date.now()) =>
  a.online && a.status === 'confirmed' && inCallWindow(a, now);
/** "রিপোর্ট করুন" (appt_report): ফি পরিশোধিত, নিশ্চিত, সময়ের ১৫ মিনিট পরও ডাক্তার আসেননি (সার্ভার শেষ কথা বলে) */
export const REPORT_AFTER_MS = 15 * 60 * 1000;
export const canReportNoShow = (
  a: { online: boolean; status: string; payState: PayState; doctorJoined?: boolean; date: number; start: string; startAt?: number },
  now = Date.now(),
) => a.online && a.status === 'confirmed' && a.payState === 'paid' && a.doctorJoined !== true && now >= apptStartMs(a) + REPORT_AFTER_MS;

/** web-calls-এর পাতা (ApptCallPage): ডাক্তার/সহকারী ?role=provider, ঐচ্ছিক &name=অন্যজনের নাম */
export function callPath(id: string, provider = false, name = ''): string {
  const q = new URLSearchParams();
  if (provider) q.set('role', 'provider');
  if (name) q.set('name', name);
  const qs = q.toString();
  return `/appointments/${encodeURIComponent(id)}/call${qs ? '?' + qs : ''}`;
}

// ---------- লেখা ----------
export const T = {
  online: () => tr('Online', 'অনলাইন'), // bn-ok
  modeLabel: (m: ChamberMode) => (m === 'online' ? tr('Online (video)', 'অনলাইন (ভিডিও)') : tr('At chamber', 'চেম্বারে')), // bn-ok
  chamberType: () => tr('Chamber type', 'চেম্বারের ধরন'), // bn-ok
  videoLine: () => tr('Consultation by video call in the app', 'অ্যাপে ভিডিও কলে দেখাবেন'), // bn-ok
  videoShort: () => tr('Video call', 'ভিডিও কল'), // bn-ok
  bkash: () => tr('bKash number', 'bKash নম্বর'), // bn-ok
  bkashHint: () => tr('Patients send the fee to this number after booking', 'অ্যাপয়েন্টমেন্টের পর রোগীরা এই নম্বরে ফি পাঠাবেন'), // bn-ok
  noteHint: () => tr('Only patients who booked see it. No phone numbers.', 'শুধু অ্যাপয়েন্টমেন্ট নেওয়া রোগীরা দেখবেন। ফোন নম্বর দেবেন না।'), // bn-ok
  errNotePhone: () => tr("The note can't have a phone number", 'নোটে ফোন নম্বর দেওয়া যাবে না'), // bn-ok
  bkashLater: () => tr('The new number starts after 24 hours', 'নতুন নম্বর ২৪ ঘণ্টা পর চালু হবে'), // bn-ok
  trxUsed: () => tr('This TrxID was already used. Check it and write the right one.', 'এই TrxID আগেই ব্যবহার হয়েছে। মিলিয়ে সঠিক TrxID লিখুন।'), // bn-ok
  report: () => tr('Report', 'রিপোর্ট করুন'), // bn-ok
  reportWhy: () => tr("Paid, but the doctor didn't join the call?", 'ফি দিয়েছেন, কিন্তু ডাক্তার কলে আসেননি?'), // bn-ok
  noteLabel: () => tr('Note for patients (optional)', 'রোগীর জন্য নোট (ঐচ্ছিক)'), // bn-ok
  notePh: () => tr('e.g. Keep the internet on at the time', 'যেমন: সময়মতো ইন্টারনেট চালু রাখবেন'), // bn-ok
  addOnline: () => tr('Add online chamber', 'অনলাইন চেম্বার যোগ করুন'), // bn-ok
  chooseHospital: () => tr('Choose hospital', 'হাসপাতাল বেছে নিন'), // bn-ok
  errBkash: () => tr('Enter a valid bKash number for each online chamber', 'প্রতিটা অনলাইন চেম্বারে সঠিক bKash নম্বর দিন'), // bn-ok
  errHospital: () => tr('Choose the hospital for each chamber', 'প্রতিটা চেম্বারের হাসপাতাল বেছে নিন'), // bn-ok
  filterSub: () => tr('See a doctor by video call from anywhere', 'যেকোনো জায়গা থেকে ভিডিও কলে ডাক্তার দেখান'), // bn-ok
  bookHint: () => tr('After the doctor confirms, send the fee by bKash. The number shows in My appointments.', 'ডাক্তার নিশ্চিত করলে ফি bKash-এ পাঠাবেন — নম্বর "আমার অ্যাপয়েন্টমেন্ট"-এ দেখাবে।'), // bn-ok
  // রোগী
  payTitle: () => tr('Consultation fee', 'পরামর্শের ফি'), // bn-ok
  howTo: () => tr('How to pay', 'কীভাবে ফি দেবেন'), // bn-ok
  step1: () => tr('In the bKash app (or *247#) choose "Send Money"', 'bKash অ্যাপ (বা *247#) থেকে "Send Money" করুন'), // bn-ok
  step2: () => tr('Send the fee to the number above', 'উপরের নম্বরে ফি পাঠান'), // bn-ok
  step3: () => tr('Write the TrxID from the SMS below and tap "I\'ve sent the money"', 'মেসেজে আসা TrxID নিচে লিখে "টাকা পাঠিয়েছি" চাপুন'), // bn-ok
  step4: () => tr('When the doctor checks it, it shows "Fee paid"', 'ডাক্তার মিলিয়ে নিলে "ফি পরিশোধিত" দেখাবে'), // bn-ok
  trx: () => tr('TrxID (optional)', 'TrxID (ঐচ্ছিক)'), // bn-ok
  trxPh: () => tr('e.g. 9GH7K2LM1P', 'যেমন: 9GH7K2LM1P'), // bn-ok
  sent: () => tr("I've sent the money", 'টাকা পাঠিয়েছি'), // bn-ok
  sentToast: () => tr('Done. The doctor will check it.', 'জানানো হয়েছে। ডাক্তার মিলিয়ে দেখবেন।'), // bn-ok
  checking: () => tr('The doctor is checking the payment', 'ডাক্তার টাকা মিলিয়ে দেখছেন'), // bn-ok
  rejected: () => tr("The doctor didn't find this payment. Check the number and TrxID, then tell again.", 'ডাক্তার এই টাকা পাননি। নম্বর আর TrxID মিলিয়ে আবার জানান।'), // bn-ok
  reason: () => tr('Reason: ', 'কারণ: '), // bn-ok
  afterConfirm: () => tr('Send the fee after the doctor confirms', 'ডাক্তার নিশ্চিত করলে ফি পাঠাবেন'), // bn-ok
  copied: () => tr('bKash number copied', 'bKash নম্বর কপি হয়েছে'), // bn-ok
  // কল
  join: () => tr('Join the video call', 'ভিডিও কলে যুক্ত হোন'), // bn-ok
  joinLater: () => tr('The call opens 15 minutes before the time', 'সময়ের ১৫ মিনিট আগে কলে যুক্ত হওয়া যাবে'), // bn-ok
  // ডাক্তার/সহকারী
  got: () => tr('Fee received', 'ফি পেয়েছি'), // bn-ok
  notGot: () => tr('Not received', 'পাইনি'), // bn-ok
  gotTitle: () => tr('Fee received?', 'ফি পেয়েছেন?'), // bn-ok
  gotText: () => tr('Check your bKash first. The patient will see "Fee paid".', 'আগে bKash-এ মিলিয়ে নিন। রোগী "ফি পরিশোধিত" দেখবেন।'), // bn-ok
  notGotTitle: () => tr("Didn't get the fee?", 'ফি পাননি?'), // bn-ok
  notGotText: () => tr('The patient will be asked to check and tell again.', 'রোগীকে মিলিয়ে আবার জানাতে বলা হবে।'), // bn-ok
  reasonLabel: () => tr('Reason (optional)', 'কারণ (ঐচ্ছিক)'), // bn-ok
  reasonPh: () => tr('e.g. No money came with this TrxID', 'যেমন: এই TrxID-তে টাকা আসেনি'), // bn-ok
};

/** টাকার অবস্থা — রোগীর দিক থেকে */
export function payLabel(p: PayState): string {
  switch (p) {
    case 'claimed': return tr('Being checked', 'যাচাই হচ্ছে'); // bn-ok
    case 'paid': return tr('Fee paid', 'ফি পরিশোধিত'); // bn-ok
    case 'rejected': return tr("Doctor didn't get it", 'ডাক্তার পাননি'); // bn-ok
    default: return tr('Not sent', 'পাঠানো হয়নি'); // bn-ok
  }
}
/** টাকার অবস্থা — ডাক্তার/সহকারীর দিক থেকে */
export function payLabelPro(p: PayState): string {
  switch (p) {
    case 'claimed': return tr('Patient sent the fee', 'রোগী ফি পাঠিয়েছেন'); // bn-ok
    case 'paid': return tr('Fee paid', 'ফি পরিশোধিত'); // bn-ok
    case 'rejected': return tr('Fee not received', 'ফি পাননি'); // bn-ok
    default: return tr('Fee not sent', 'ফি পাঠাননি'); // bn-ok
  }
}
export const payTone = (p: PayState): 'good' | 'primary' | 'error' | 'info' => (p === 'paid' ? 'good' : p === 'claimed' ? 'primary' : p === 'rejected' ? 'error' : 'info');
