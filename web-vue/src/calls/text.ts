// অ্যাপে কল আর অনলাইন চেম্বারের লেখা — এক জায়গায় (নতুন লেখা, React ওয়েবে আগের রূপ নেই: প্রতিটা লাইনে bn-ok)
import { num, tr } from '@/lib/i18n';
import type { CallCtx, CallState, ReportReason, StartRefusal } from './api';
import './icons';

export const T = {
  inApp: () => tr('Call in app', 'অ্যাপে কল'), // bn-ok
  video: () => tr('Video call', 'ভিডিও কল'), // bn-ok
  audioCall: () => tr('Audio call', 'অডিও কল'), // bn-ok
  // যোগাযোগের কলের মেনু (ContactActions): ফোন কল · অ্যাপ কল · ভিডিও কল
  phoneCall: () => tr('Phone call', 'ফোন কল'), // bn-ok
  appCall: () => tr('App call', 'অ্যাপ কল'), // bn-ok
  callMenu: () => tr('Call', 'কল করুন'), // bn-ok
  message: () => tr('Message', 'মেসেজ'), // bn-ok
  phone: () => tr('Call', 'ফোন'),
  answerAudio: () => tr('Answer with audio', 'অডিওতে ধরুন'), // bn-ok
  answerVideo: () => tr('Answer with video', 'ভিডিওতে ধরুন'), // bn-ok
  calling: () => tr('Calling…', 'কল যাচ্ছে…'), // bn-ok
  ringing: () => tr('Ringing', 'রিং হচ্ছে'), // bn-ok
  connecting: () => tr('Connecting…', 'সংযোগ হচ্ছে…'), // bn-ok
  reconnecting: () => tr('Reconnecting…', 'আবার সংযোগ হচ্ছে…'), // bn-ok
  incoming: () => tr('Incoming call', 'অ্যাপে কল আসছে'), // bn-ok
  incomingVideo: () => tr('Incoming video call', 'ভিডিও কল আসছে'), // bn-ok
  answer: () => tr('Answer', 'ধরুন'), // bn-ok
  decline: () => tr('Decline', 'কেটে দিন'), // bn-ok
  hangUp: () => tr('Hang up', 'কল কাটুন'), // bn-ok
  micOff: () => tr('Mute', 'মাইক বন্ধ'), // bn-ok
  micOn: () => tr('Unmute', 'মাইক চালু'), // bn-ok
  camOff: () => tr('Camera off', 'ক্যামেরা বন্ধ'), // bn-ok
  camOn: () => tr('Camera on', 'ক্যামেরা চালু'), // bn-ok
  flip: () => tr('Switch camera', 'ক্যামেরা ঘোরান'), // bn-ok
  devices: () => tr('Change device', 'ডিভাইস বদলান'), // bn-ok
  mic: () => tr('Microphone', 'মাইক্রোফোন'), // bn-ok
  camera: () => tr('Camera', 'ক্যামেরা'), // bn-ok
  speaker: () => tr('Speaker', 'স্পিকার'), // bn-ok
  audioOnly: () => tr('Audio only', 'শুধু অডিও'), // bn-ok
  withVideo: () => tr('Turn video back on', 'আবার ভিডিও চালু'), // bn-ok
  weak: () => tr('Weak connection — switch to audio only?', 'নেট দুর্বল — শুধু অডিওতে চালাবেন?'), // bn-ok
  camFailed: () => tr("Camera didn't start — continuing with audio only", 'ক্যামেরা চালু হয়নি — শুধু অডিওতে চলছে'), // bn-ok
  permAsk: (video: boolean) =>
    video
      ? tr('When the browser asks, allow the microphone and camera (Allow)', 'ব্রাউজার জিজ্ঞেস করলে মাইক্রোফোন আর ক্যামেরার অনুমতি দিন (Allow)') // bn-ok
      : tr('When the browser asks, allow the microphone (Allow)', 'ব্রাউজার জিজ্ঞেস করলে মাইক্রোফোনের অনুমতি দিন (Allow)'), // bn-ok
  permDenied: () =>
    tr(
      'Microphone permission was not given. Tap the lock icon next to the web address, allow the microphone, then try again.',
      'মাইক্রোফোনের অনুমতি দেওয়া হয়নি। ঠিকানার পাশের তালা চিহ্নে চেপে মাইক্রোফোনের অনুমতি দিন, তারপর আবার চেষ্টা করুন।', // bn-ok
    ),
  noMic: () => tr('No microphone found on this device', 'এই ডিভাইসে কোনো মাইক্রোফোন পাওয়া যায়নি'), // bn-ok
  noSupport: () => tr("This browser can't make calls. Use Chrome or the Lifolo app.", 'এই ব্রাউজারে কল করা যায় না। Chrome বা Lifolo অ্যাপ ব্যবহার করুন।'), // bn-ok
  failed: () => tr("Couldn't connect the call. Check your internet.", 'কল সংযোগ হয়নি, ইন্টারনেট দেখুন।'), // bn-ok
  ended: () => tr('Call ended', 'কল শেষ'), // bn-ok
  peerLeft: () => tr('They left the call', 'উনি কল থেকে বেরিয়ে গেছেন'), // bn-ok
  tapSound: () => tr('Tap to turn on sound', 'শব্দ শুনতে চাপুন'), // bn-ok
  phoneInstead: () => tr('Call by phone', 'ফোনে কল করুন'), // bn-ok
  waitingRoom: () => tr('Waiting room', 'অপেক্ষার ঘর'), // bn-ok
  waitDoctor: () => tr('Waiting for the doctor…', 'ডাক্তারের অপেক্ষায়…'), // bn-ok
  waitPatient: () => tr('Waiting for the patient…', 'রোগীর অপেক্ষায়…'), // bn-ok
  waitSub: () => tr('Stay on this page. The call starts as soon as they join.', 'এই পাতায় থাকুন — উনি যুক্ত হলেই কল শুরু হবে।'), // bn-ok
  leave: () => tr('Leave', 'বেরিয়ে যান'), // bn-ok
  leftAppt: () => tr('You left the call. You can join again from the appointment.', 'আপনি কল থেকে বেরিয়েছেন। অ্যাপয়েন্টমেন্ট থেকে আবার যুক্ত হতে পারবেন।'), // bn-ok
  // কলের তালিকা
  history: () => tr('Calls', 'কলের তালিকা'), // bn-ok
  historyEmpty: () => tr('No calls yet', 'এখনো কোনো কল নেই'), // bn-ok
  historySub: () => tr('In-app voice and video calls', 'অ্যাপের ভয়েস আর ভিডিও কল'), // bn-ok
  dirIn: () => tr('Incoming', 'এসেছে'), // bn-ok
  dirOut: () => tr('Outgoing', 'করেছি'), // bn-ok
  more: () => tr('Load more', 'আরও দেখুন'), // bn-ok
  callBack: () => tr('Call back', 'আবার কল'), // bn-ok
  // ভিডিওতে যাওয়া (অডিও কল থেকে)
  askVideo: () => tr('Ask to turn on video', 'ভিডিও চালু করতে অনুরোধ'), // bn-ok
  askedVideo: () => tr('Video request sent — waiting for them', 'ভিডিওর অনুরোধ পাঠানো হয়েছে — ওনার উত্তরের অপেক্ষা'), // bn-ok
  offeredVideo: () => tr('They want to turn on video', 'উনি ভিডিও চালু করতে চান'), // bn-ok
  agree: () => tr('Agree', 'রাজি'), // bn-ok
  notNow: () => tr('Not now', 'এখন না'), // bn-ok
  videoDeclined: () => tr('They did not turn on video', 'উনি ভিডিও চালু করেননি'), // bn-ok
  // ব্লক আর রিপোর্ট
  block: () => tr('Stop contact', 'যোগাযোগ বন্ধ করুন'), // bn-ok
  report: () => tr('Report', 'রিপোর্ট'),
  blockTitle: (name: string) => tr(`Stop contact with ${name}?`, `${name}-এর সাথে যোগাযোগ বন্ধ করবেন?`), // bn-ok
  blockText: () => tr("They won't be able to call or message you in the app, and you won't be able to reach them either. Phone calls are not affected.", 'উনি আর আপনাকে অ্যাপে কল বা মেসেজ করতে পারবেন না, আপনিও ওনাকে পারবেন না। ফোনে কল এতে বন্ধ হয় না।'), // bn-ok
  blocked: () => tr('Contact stopped', 'যোগাযোগ বন্ধ করা হয়েছে'), // bn-ok
  blockedShort: () => tr('You stopped contact', 'যোগাযোগ বন্ধ রেখেছেন'), // bn-ok
  unblock: () => tr('Unblock', 'আনব্লক'), // bn-ok
  unblocked: () => tr('Unblocked', 'আনব্লক করা হয়েছে'), // bn-ok
  blockedList: () => tr('People you stopped contact with', 'যাদের সাথে যোগাযোগ বন্ধ'), // bn-ok
  blockedSub: () => tr('In-app calls and messages', 'অ্যাপে কল আর মেসেজ'), // bn-ok
  blockedNone: () => tr("You haven't stopped contact with anyone", 'কারো সাথে যোগাযোগ বন্ধ করা নেই'), // bn-ok
  reportTitle: () => tr('Report this call', 'এই কল রিপোর্ট করুন'), // bn-ok
  reportNote: () => tr('What happened? (optional)', 'কী হয়েছিল? (ঐচ্ছিক)'), // bn-ok
  reportSend: () => tr('Send report', 'রিপোর্ট পাঠান'), // bn-ok
  reported: () => tr('Report sent. Thank you.', 'রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।'), // bn-ok
};

export function stateText(s: CallState): string {
  switch (s) {
    case 'ringing': return T.ringing();
    case 'accepted': return tr('Answered', 'কথা হয়েছে'); // bn-ok
    case 'declined': return tr('Declined', 'কেটে দিয়েছেন'); // bn-ok
    case 'missed': return tr('Missed', 'মিস হয়েছে'); // bn-ok
    case 'cancelled': return tr('Cancelled', 'বাতিল');
    case 'ended': return T.ended();
  }
}
/** কল শেষের কারণ — কল দেখানোর পর্দায় */
export function endText(reason: CallState | 'failed' | 'left' | null): string {
  switch (reason) {
    case 'declined': return tr('They declined the call', 'উনি কল কেটে দিয়েছেন'); // bn-ok
    case 'missed': return tr('No answer', 'কেউ ধরেননি'); // bn-ok
    case 'cancelled': return tr('Call cancelled', 'কল বাতিল হয়েছে'); // bn-ok
    case 'failed': return T.failed();
    case 'left': return T.peerLeft();
    default: return T.ended();
  }
}
export function refusalText(r: StartRefusal, serverMsg?: string): string {
  switch (r) {
    case 'off': return serverMsg || tr('They have turned off in-app calls — call by phone', 'উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন'); // bn-ok
    case 'busy': return tr("They're on another call. Try again a little later.", 'উনি এখন অন্য কলে আছেন। একটু পরে আবার চেষ্টা করুন।'); // bn-ok
    case 'blocked': return tr('You have stopped contact with this person', 'আপনি এঁর সাথে যোগাযোগ বন্ধ রেখেছেন'); // bn-ok
    case 'unavailable': return serverMsg || tr('In-app calls are not available now — call by phone', 'অ্যাপে কল এখন চালু নেই — ফোনে কল করুন'); // bn-ok
    case 'not_allowed': return serverMsg || tr("You can't call them in the app now.", 'এখন অ্যাপে এঁকে কল করা যায় না।'); // bn-ok
    case 'rate': return tr('Too many calls. Try again in a few minutes.', 'অনেকবার কল করেছেন। কয়েক মিনিট পরে আবার চেষ্টা করুন।'); // bn-ok
    default: return serverMsg || T.failed();
  }
}
export function ctxText(c: CallCtx): string {
  switch (c) {
    case 'blood': return tr('Blood request', 'রক্তের রিকোয়েস্ট'); // bn-ok
    case 'appointment': return tr('Appointment', 'অ্যাপয়েন্টমেন্ট');
    case 'ambulance': return tr('Ambulance', 'অ্যাম্বুলেন্স');
    case 'rx_order': return tr('Medicine order', 'ওষুধের অর্ডার'); // bn-ok
    case 'lab_order': return tr('Lab test', 'প্যাথলজির পরীক্ষা'); // bn-ok
    case 'order': return tr('Order', 'অর্ডার');
  }
}
export function reasonText(r: ReportReason): string {
  switch (r) {
    case 'harassment': return tr('Harassment or abuse', 'হয়রানি বা গালাগাল'); // bn-ok
    case 'spam': return tr('Too many / unwanted calls', 'বারবার / অপ্রয়োজনীয় কল'); // bn-ok
    case 'fake': return tr('Fake or fraud', 'ভুয়া বা প্রতারণা'); // bn-ok
    case 'asked_other_number': return tr('Asked me to send money to another number', 'অন্য নম্বরে টাকা পাঠাতে বলেছে'); // bn-ok
    case 'other': return tr('Other', 'অন্য কিছু');
  }
}
/** কলের সময়: ০৩:০৭ বা ১:০২:০৫ */
export function clock(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const pad = (n: number) => String(n).padStart(2, '0');
  return num(h ? `${h}:${pad(m)}:${pad(s % 60)}` : `${pad(m)}:${pad(s % 60)}`);
}
