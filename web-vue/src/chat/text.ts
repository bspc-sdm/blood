// অ্যাপে মেসেজের লেখা — এক জায়গায় (নতুন লেখা, React ওয়েবে আগের রূপ নেই: প্রতিটা লাইনে bn-ok)
import { num, tr } from '@/lib/i18n';
import { formatDayOf, formatTime } from '@/lib/format';
import type { ChatCtx, ChatRefusal, ReportReason } from './api';
import './icons';

export const T = {
  messages: () => tr('Messages', 'মেসেজ'), // bn-ok
  message: () => tr('Message', 'মেসেজ'), // bn-ok
  empty: () => tr('No messages yet', 'এখনো কোনো মেসেজ নেই'), // bn-ok
  emptySub: () =>
    tr(
      'You can message people you are dealing with — a donor, doctor, ambulance, pharmacy or lab — from that request or order.',
      'যাদের সাথে কাজ চলছে (ডোনার, ডাক্তার, অ্যাম্বুলেন্স, ফার্মেসি, ল্যাব) — সেই রিকোয়েস্ট বা অর্ডারের পাতা থেকে মেসেজ করতে পারবেন।', // bn-ok
    ),
  pick: () => tr('Choose a conversation', 'একটা কথোপকথন বেছে নিন'), // bn-ok
  noThread: () => tr('This conversation was not found. It may have been deleted.', 'কথোপকথনটা পাওয়া যায়নি — হয়তো মুছে গেছে।'), // bn-ok
  firstMsg: () => tr('Say hello. Keep it about this request — never share OTPs or passwords.', 'কথা শুরু করুন। এই কাজের বিষয়েই লিখুন — OTP বা পাসওয়ার্ড কখনো দেবেন না।'), // bn-ok
  placeholder: () => tr('Write a message', 'মেসেজ লিখুন'), // bn-ok
  send: () => tr('Send', 'পাঠান'),
  photo: () => tr('Photo', 'ছবি'), // bn-ok
  location: () => tr('Location', 'লোকেশন'), // bn-ok
  photoLast: () => tr('📷 Photo', '📷 ছবি'), // bn-ok
  locationLast: () => tr('📍 Location', '📍 লোকেশন'), // bn-ok
  openMap: () => tr('Open in map', 'ম্যাপে দেখুন'), // bn-ok
  myLocation: () => tr('My location', 'আমার লোকেশন'), // bn-ok
  sendLocTitle: () => tr('Send your current location?', 'আপনার এখনকার লোকেশন পাঠাবেন?'), // bn-ok
  sendLocText: () => tr('Only this person will see it, in this conversation.', 'শুধু এই কথোপকথনে উনি দেখতে পাবেন।'), // bn-ok
  locFailed: () => tr("Couldn't get your location. Allow location access and try again.", 'লোকেশন পাওয়া যায়নি — লোকেশনের অনুমতি দিয়ে আবার চেষ্টা করুন।'), // bn-ok
  photoFailed: () => tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'),
  photoBig: () => tr('This photo is too large even after shrinking. Choose another.', 'ছোট করার পরেও ছবিটা অনেক বড় — অন্য ছবি দিন।'), // bn-ok
  photoExpired: () => tr('Loading the photo again…', 'ছবি আবার আনা হচ্ছে…'), // bn-ok
  sending: () => tr('Sending…', 'যাচ্ছে…'), // bn-ok
  sent: () => tr('Sent', 'গেছে'), // bn-ok
  read: () => tr('Seen', 'দেখেছেন'), // bn-ok
  failed: () => tr('Not sent', 'যায়নি'), // bn-ok
  retry: () => tr('Try again', 'আবার চেষ্টা'), // bn-ok
  discard: () => tr('Discard', 'বাদ দিন'), // bn-ok
  unsent: () => tr('Message deleted', 'মেসেজ মুছে ফেলা হয়েছে'), // bn-ok
  unsend: () => tr('Delete for both', 'দুজনের জন্যই মুছুন'), // bn-ok
  unsendTitle: () => tr('Delete this message?', 'মেসেজটা মুছবেন?'), // bn-ok
  unsendText: () => tr('It will be removed for both of you. You can do this within 10 minutes of sending.', 'দুজনের কাছ থেকেই মুছে যাবে। পাঠানোর ১০ মিনিটের মধ্যে মোছা যায়।'), // bn-ok
  unsendLate: () => tr('Messages can only be deleted within 10 minutes', 'শুধু ১০ মিনিটের মধ্যে মোছা যায়'), // bn-ok
  copy: () => tr('Copy', 'কপি'), // bn-ok
  copied: () => tr('Copied', 'কপি হয়েছে'), // bn-ok
  more: () => tr('More', 'আরও'), // bn-ok
  older: () => tr('Earlier messages', 'আগের মেসেজ'), // bn-ok
  newBelow: () => tr('New message', 'নতুন মেসেজ'), // bn-ok
  // রিপোর্ট আর ব্লক
  report: () => tr('Report', 'রিপোর্ট'),
  reportTitle: () => tr('Report this message', 'এই মেসেজ রিপোর্ট করুন'), // bn-ok
  reportNote: () => tr('What happened? (optional)', 'কী হয়েছিল? (ঐচ্ছিক)'), // bn-ok
  reportSend: () => tr('Send report', 'রিপোর্ট পাঠান'), // bn-ok
  reported: () => tr('Report sent. Thank you.', 'রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।'), // bn-ok
  block: () => tr('Block', 'ব্লক'), // bn-ok
  blockTitle: (name: string) => tr(`Block ${name}?`, `${name}-কে ব্লক করবেন?`), // bn-ok
  blockText: () =>
    tr(
      "Neither of you will be able to message or call each other in the app. Phone calls are not affected.",
      'দুজনের কেউই আর অ্যাপে একে অপরকে মেসেজ বা কল করতে পারবেন না। ফোনে কল এতে বন্ধ হয় না।', // bn-ok
    ),
  blocked: () => tr('Blocked', 'ব্লক করা হয়েছে'), // bn-ok
  // শুধু পড়া যায়
  readOnly: () => tr('This conversation is closed', 'এই কথোপকথন বন্ধ হয়ে গেছে'), // bn-ok
  readOnlySub: () =>
    tr(
      'The request or order is over, so you can only read it now. It is deleted 90 days later, photos included.',
      'রিকোয়েস্ট বা অর্ডার শেষ, তাই এখন শুধু পড়া যায়। ৯০ দিন পরে ছবিসহ মুছে যাবে।', // bn-ok
    ),
  blockedSub: () => tr('You blocked this person. You can only read this conversation.', 'আপনি এঁকে ব্লক করেছেন — এই কথোপকথন এখন শুধু পড়া যায়।'), // bn-ok
  notAllowedSub: () => tr("You can't message this person in the app now. You can still read the conversation.", 'এখন অ্যাপে এঁকে মেসেজ করা যায় না — কথোপকথন শুধু পড়া যায়।'), // bn-ok
  // ভুল
  openFailed: () => tr("Couldn't open messages. Check your internet.", 'মেসেজ খোলা যায়নি, ইন্টারনেট দেখুন।'), // bn-ok
  tooLong: (max: number) => tr(`At most ${max} characters`, `সর্বোচ্চ ${num(max)} অক্ষর`), // bn-ok
};

export function refusalText(r: ChatRefusal, serverMsg?: string): string {
  switch (r) {
    case 'rate': return tr('You are sending too fast. Wait a minute and try again.', 'খুব দ্রুত পাঠাচ্ছেন — এক মিনিট পরে আবার চেষ্টা করুন।'); // bn-ok
    case 'not_allowed': return tr("You can't message this person in the app now.", 'এখন অ্যাপে এঁকে মেসেজ করা যায় না।'); // bn-ok
    case 'blocked': return tr('You have blocked this person, so messages are off.', 'আপনি এঁকে ব্লক করেছেন — মেসেজ যাবে না।'); // bn-ok
    case 'closed': return tr('This conversation is closed. You can only read it.', 'এই কথোপকথন বন্ধ — এখন শুধু পড়া যায়।'); // bn-ok
    case 'net': return tr('No internet connection. Check and try again.', 'ইন্টারনেট সংযোগ নেই। দেখে আবার চেষ্টা করুন।');
    default: return serverMsg && /[ঀ-৿]/.test(serverMsg) ? serverMsg : tr("Couldn't send. Try again.", 'পাঠানো যায়নি — আবার চেষ্টা করুন।'); // bn-ok
  }
}

export function ctxText(c: ChatCtx | null): string {
  switch (c) {
    case 'blood': return tr('Blood request', 'রক্তের রিকোয়েস্ট'); // bn-ok
    case 'appointment': return tr('Appointment', 'অ্যাপয়েন্টমেন্ট');
    case 'ambulance': return tr('Ambulance', 'অ্যাম্বুলেন্স');
    case 'rx_order': return tr('Medicine order', 'ওষুধের অর্ডার'); // bn-ok
    case 'lab_order': return tr('Lab test', 'প্যাথলজির পরীক্ষা'); // bn-ok
    default: return '';
  }
}

export function reasonText(r: ReportReason): string {
  switch (r) {
    case 'harassment': return tr('Harassment or abuse', 'হয়রানি বা গালাগাল'); // bn-ok
    case 'spam': return tr('Too many / unwanted messages', 'বারবার / অপ্রয়োজনীয় মেসেজ'); // bn-ok
    case 'fake': return tr('Fake or fraud', 'ভুয়া বা প্রতারণা'); // bn-ok
    case 'other': return tr('Other', 'অন্য কিছু');
  }
}

/** থ্রেডের তালিকায় শেষ মেসেজের সময়: আজ হলে ঘড়ির সময়, নইলে তারিখ */
export function shortWhen(at: number, now = Date.now()): string {
  if (!at) return '';
  return formatDayOf(at) === formatDayOf(now) ? formatTime(at) : formatDayOf(at);
}

/** তালিকায় শেষ মেসেজ: "📷"/"📍" হলে লেখায় */
export function lastText(t: string): string {
  if (t === '📷') return T.photoLast();
  if (t === '📍') return T.locationLast();
  return t;
}
