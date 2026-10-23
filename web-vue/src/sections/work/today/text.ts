// "আজকের কাজ" ("আমার কাজ"-এর প্রথম পাতা) আর "সেবা দিন"-এর লেখা — নতুন লেখা, React ওয়েবে আগের রূপ নেই (তাই bn-ok)।
// সংখ্যা সবসময় num() দিয়ে (বাংলায় বাংলা অঙ্ক); টাকা "৳১,২৩৪"।
import { num, tr } from '@/lib/i18n';
import './icons';

const taka = (n: number) => `৳${num(Math.round(n).toLocaleString('en-IN'))}`; // bn-ok

export const TT = {
  title: () => tr("Today's work", 'আজকের কাজ'), // bn-ok
  serveTab: () => tr('Give service', 'সেবা দিন'), // bn-ok
  myWork: () => tr('My work', 'আমার কাজ'), // bn-ok
  // ভূমিকা (চিপ, সারির ট্যাগ, পাশের মেনু)
  all: () => tr('All', 'সব'), // bn-ok
  chamber: () => tr('Chamber', 'চেম্বার'), // bn-ok
  pharmacy: () => tr('Pharmacy', 'ফার্মেসি'), // bn-ok
  lab: () => tr('Lab', 'ল্যাব'), // bn-ok
  bloodbank: () => tr('Blood bank', 'ব্লাড ব্যাংক'), // bn-ok
  ambulance: () => tr('Ambulance', 'অ্যাম্বুলেন্স'), // bn-ok
  homecare: () => tr('Home care', 'বাসায় সেবা'), // bn-ok
  rolesLabel: () => tr('Show work of', 'কোন কাজ দেখবেন'), // bn-ok
  // খোলা / বিরতি / বন্ধ
  open: () => tr('Open', 'খোলা'), // bn-ok
  paused: () => tr('Break', 'বিরতি'), // bn-ok
  closed: () => tr('Closed', 'বন্ধ'), // bn-ok
  free: () => tr("I'm free", 'খালি আছি'), // bn-ok
  onTrip: () => tr("I'm on a trip", 'ট্রিপে আছি'), // bn-ok
  statusOf: (name: string) => tr(`Status of ${name}`, `${name}-এর অবস্থা`), // bn-ok
  pauseFor: () => tr('Break for how long?', 'কতক্ষণ বিরতি?'), // bn-ok
  p30: () => tr('30 minutes', '৩০ মিনিট'), // bn-ok
  p60: () => tr('1 hour', '১ ঘণ্টা'), // bn-ok
  pDay: () => tr('For today', 'আজকের মতো'), // bn-ok
  cancel: () => tr('Cancel', 'বাতিল'), // bn-ok
  reopensAt: (t: string) => tr(`Opens again at ${t}`, `আবার খুলবে ${t}`), // bn-ok
  reopensTomorrow: () => tr('Opens again tomorrow', 'আবার খুলবে কাল'), // bn-ok
  closedNote: () => tr('Closed — patients see "closed"', 'বন্ধ — রোগীরা "বন্ধ" দেখছেন'), // bn-ok
  pending: () => tr('Under review', 'যাচাই চলছে'), // bn-ok
  notToday: () => tr('No chamber today', 'আজ চেম্বার নেই'), // bn-ok
  stockAt: (ago: string) => tr(`Stock updated ${ago}`, `স্টক হালনাগাদ: ${ago}`), // bn-ok
  stockNever: () => tr('Stock not given yet', 'স্টকের খবর এখনো দেওয়া হয়নি'), // bn-ok
  stockOld: () => tr('Old information', 'পুরনো তথ্য'), // bn-ok
  // আজকের সংখ্যা
  numbersOf: (name: string) => tr(`Today at ${name}`, `${name} — আজ`), // bn-ok
  serials: () => tr("Today's serials", 'আজকের সিরিয়াল'), // bn-ok
  waiting: () => tr('Waiting', 'অপেক্ষায়'), // bn-ok
  newOrders: () => tr('New orders', 'নতুন অর্ডার'), // bn-ok
  activeOrders: () => tr('In progress', 'চলছে'), // bn-ok
  requests: () => tr('New requests', 'নতুন অনুরোধ'), // bn-ok
  trips: () => tr('Trips on', 'চলতি ট্রিপ'), // bn-ok
  salesToday: () => tr("Today's sales", 'আজকের বিক্রি'), // bn-ok
  finds: () => tr('Medicine requests', 'ওষুধ খোঁজা'), // bn-ok
  newRequests: () => tr('New requests', 'নতুন অনুরোধ'), // bn-ok
  reportsDue: () => tr('Reports due', 'রিপোর্ট বাকি'), // bn-ok
  newCalls: () => tr('New calls', 'নতুন ডাক'), // bn-ok
  taka,
  // এখনই দেখুন
  now: () => tr('Look now', 'এখনই দেখুন'), // bn-ok
  nothing: () => tr('No work pending right now', 'এখন কোনো কাজ বাকি নেই'), // bn-ok
  seeAll: (n: number) => tr(`See all (${n})`, `সব দেখুন (${num(n)})`), // bn-ok
  act: (a: string | undefined) =>
    a === 'accept' ? tr('Accept', 'গ্রহণ করুন') // bn-ok
      : a === 'ready' ? tr('Ready', 'তৈরি') // bn-ok
        : a === 'report' ? tr('Send report', 'রিপোর্ট পাঠান') // bn-ok
          : a === 'call' || a === 'next' ? tr('Call in', 'ডাকুন') // bn-ok
            : a === 'reply' ? tr('Reply', 'উত্তর দিন') // bn-ok
            : a === 'receive' ? tr('Sample taken', 'নমুনা নিয়েছি') // bn-ok
            : a === 'stock' ? tr('Give stock', 'স্টক দিন') // bn-ok
              : a === 'answer' ? tr('Answer', 'উত্তর দিন') // bn-ok
                : tr('Open', 'খুলুন'), // bn-ok
  // শুরু করতে যা বাকি
  setupTitle: () => tr('Left to start', 'শুরু করতে যা বাকি'), // bn-ok
  setupDone: (d: number, t: number) => tr(`${d} of ${t} done`, `${num(t)}টার ${num(d)}টা হয়েছে`), // bn-ok
  setupKey: (k: string) =>
    ({
      photo: tr('Photo', 'ছবি'), // bn-ok
      hours: tr('Hours', 'সময়'), // bn-ok
      address: tr('Address', 'ঠিকানা'), // bn-ok
      fee: tr('Fee', 'ফি'), // bn-ok
      chamber: tr('Chamber', 'চেম্বার'), // bn-ok
      docs: tr('Photos of papers', 'কাগজের ছবি'), // bn-ok
      prices: tr('Test prices', 'পরীক্ষার দাম'), // bn-ok
      stock: tr('Blood stock', 'রক্তের মজুদ'), // bn-ok
      location: tr('Location', 'জায়গা'), // bn-ok
      approval: tr('Approval', 'অনুমোদন'), // bn-ok
      approved: tr('Approval', 'অনুমোদন'), // bn-ok
      time: tr('Hours', 'সময়'), // bn-ok
      base: tr('Vehicle location', 'গাড়ির জায়গা'), // bn-ok
      homeVisit: tr('Home visit', 'বাসায় সেবা'), // bn-ok
    })[k] ?? k,
  // রোগীরা আপনাকে যেভাবে দেখেন
  see: () => tr('See', 'দেখুন'), // bn-ok
  openPublic: () => tr('Open the patients\' page', 'রোগীদের পাতায় খুলুন'), // bn-ok
  // নিচের কার্ড
  pages: () => tr('Your work pages', 'আপনার কাজের পাতা'), // bn-ok
  sell: () => tr('Sell', 'বিক্রি করুন'), // bn-ok
  // সেবা দিন (কোনো ভূমিকা নেই)
  serveTitle: () => tr('Give service on Lifolo', 'Lifolo-তে সেবা দিন'), // bn-ok
  serveText: () => tr('Patients nearby will find you. Who can join:', 'কাছের রোগীরা আপনাকে খুঁজে পাবেন। যাঁরা যোগ দিতে পারেন:'), // bn-ok
  serveDoctor: () => tr('Doctor, nurse or health expert', 'ডাক্তার, নার্স বা স্বাস্থ্য বিশেষজ্ঞ'), // bn-ok
  serveDoctorSub: () => tr('Apply for a professional badge', 'পেশাদার ব্যাজের জন্য আবেদন করুন'), // bn-ok
  serveLogin: () => tr('Log in to give service', 'সেবা দিতে লগইন করুন'), // bn-ok
  // আগের ডেটা থেকে বানানো কাজ (সার্ভারে work_today না থাকলে)
  rxOrderOf: (name: string) => tr(`Prescription of ${name}`, `${name}-এর প্রেসক্রিপশন`), // bn-ok
  serialOf: (n: number) => tr(`Serial ${n}`, `সিরিয়াল ${num(n)}`), // bn-ok
  callFrom: (p: string) => (p ? tr(`Call · ${p}`, `ডাক · ${p}`) : tr('New call', 'নতুন ডাক')), // bn-ok
  stockTask: () => tr('Update blood stock', 'রক্তের মজুদ হালনাগাদ করুন'), // bn-ok
  findsTask: (n: number) => tr(`${n} medicine requests`, `${num(n)}টা ওষুধ খোঁজার অনুরোধ`), // bn-ok
  error: () => tr("Couldn't load today's work", 'আজকের কাজ আনা যায়নি'), // bn-ok
};
