// সার্ভারের ত্রুটির বার্তা বাংলায় লেখা; অ্যাপ ইংরেজিতে থাকলে (body.lang === 'en') এখান থেকে ইংরেজি করা হয়।
const EXACT = {
  'requestId ঠিক নেই': 'Invalid request',
  'রিকোয়েস্ট পাওয়া যায়নি': 'Request not found',
  'এটা আপনার রিকোয়েস্ট না': 'This is not your request',
  'ব্যাগের সংখ্যা ঠিক নেই': 'Invalid number of bags',
  'হাসপাতালের নাম ঠিক নেই': 'Invalid hospital name',
  'তারিখ ঠিক নেই': 'Invalid date',
  'আজ বা পরের কোনো তারিখ দিন': 'Pick today or a later date',
  'সময় ঠিক নেই': 'Invalid time',
  'জরুরি ঠিক নেই': 'Invalid urgent value',
  'তথ্য ঠিক নেই': 'Invalid data',
  'অতিরিক্ত তথ্য ৩০০ অক্ষরের মধ্যে লিখুন': 'Keep the note under 300 characters',
  'কিছু বদলানো হয়নি': 'Nothing changed',
  'বন্ধ বা মেয়াদ শেষ হওয়া রিকোয়েস্ট এডিট করা যায় না': 'Closed or expired requests can\'t be edited',
  'রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ': 'This request is closed or expired',
  'নোটিফিকেশন পাঠানো চলছে, একটু পরে দেখুন': 'Notifications are being sent, check again shortly',
  'রিকোয়েস্টটা বন্ধ হয়ে গেছে': 'This request is closed',
  'নিজের রিকোয়েস্টে সাড়া দেওয়া যায় না': 'You can\'t respond to your own request',
  'আগে প্রোফাইল পূরণ করুন': 'Complete your profile first',
  'সঠিক মোবাইল নম্বর দিন': 'Enter a valid mobile number',
  'আগে একবার ডোনারদের জানান': 'Notify donors once first',
  'সব ব্যাগের ডোনার পাওয়া গেছে': 'All bags already have donors',
  'রক্ত দিতে আগে প্রোফাইলে আপনার রক্তের গ্রুপ দিন': 'Add your blood group in your profile to donate',
  'এলাকা ঠিক নেই': 'Invalid area',
  'লোকেশন ঠিক নেই': 'Invalid location',
  'রক্তের গ্রুপ ঠিক নেই': 'Invalid blood group',
  'আপনি এখন রক্ত দেওয়ার যোগ্য নন (বয়স ১৮–৬০, ওজন কমপক্ষে ৫০ কেজি, আর শেষ রক্তদানের পর ১২০ দিন লাগে)':
    'You are not eligible right now (age 18–60, weight 50 kg+, and 120 days since your last donation)',
  'রক্তপ্রার্থী অন্য ডোনার বেছে নিয়েছেন': 'The requester chose another donor',
  'id ঠিক নেই': 'Invalid id',
  'অনুরোধ পাওয়া যায়নি': 'Request not found',
  'অনুরোধে সমস্যা আছে': 'Something is wrong with this request',
  'চাওয়া তারিখটা ঠিক নেই': 'The requested date is invalid',
  'সঠিক ইমেইল দিন': 'Enter a valid email',
  'আজ এখান থেকে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।': 'Too many codes requested from here today. Try again tomorrow.',
  'আজকের মতো কোড পাঠানোর সীমা শেষ। কাল আবার চেষ্টা করুন।': 'Today\'s code limit is reached. Try again tomorrow.',
  'একটু আগেই কোড পাঠানো হয়েছে। ১ মিনিট পর আবার চেষ্টা করুন।': 'A code was just sent. Try again in 1 minute.',
  'আজ এই ইমেইলে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।': 'Too many codes for this email today. Try again tomorrow.',
  'ইমেইল পাঠানো যায়নি। একটু পর আবার চেষ্টা করুন।': 'Couldn\'t send the email. Try again shortly.',
  '৬ সংখ্যার কোড দিন': 'Enter the 6-digit code',
  'কোডের মেয়াদ শেষ বা কোড চাওয়া হয়নি। নতুন কোড চান।': 'The code expired or wasn\'t requested. Ask for a new one.',
  'আজ অনেকবার ভুল কোড দেওয়া হয়েছে। কাল আবার চেষ্টা করুন।': 'Too many wrong codes today. Try again tomorrow.',
  'কোড মেলেনি। আবার দেখে লিখুন।': 'Wrong code. Please check and try again.',
  'অনুমতি নেই': 'Not allowed',
  'লগইন দরকার': 'Please log in',
  'লগইনের মেয়াদ শেষ, আবার লগইন করুন': 'Session expired, please log in again',
  'শুধু অ্যাডমিন এটা করতে পারবেন': 'Admins only',
};

const PATTERNS = [
  [/^একটা রিকোয়েস্টে সর্বোচ্চ (\d+) বার জানানো যায়।/, (m) => `Donors can be notified up to ${m[1]} times per request. Tell people you know directly.`],
  [/^(\d+) ঘণ্টা পর আবার জানাতে পারবেন/, (m) => `You can notify again in ${m[1]} hour(s)`],
  [/^একটা রিকোয়েস্ট সর্বোচ্চ (\d+) বার এডিট করা যায়।/, (m) => `A request can be edited at most ${m[1]} times. Close it and create a new one if needed.`],
  [/^দিনে সর্বোচ্চ (\d+)টা রিকোয়েস্ট দেওয়া যায়।/, (m) => `You can create up to ${m[1]} requests a day. If urgent, contact people directly.`],
  [/^একসাথে সর্বোচ্চ (\d+)টা খোলা রিকোয়েস্ট রাখা যায়/, (m) => `You can keep up to ${m[1]} open requests at a time`],
  [/^আপনার গ্রুপ \(([^)]+)\) এই রোগীকে দেওয়া যায় না/, (m) => `Your group (${m[1]}) can't donate to this patient`],
  [/^দিনে সর্বোচ্চ (\d+)টা রিকোয়েস্টে সাড়া দেওয়া যায়।/, (m) => `You can respond to up to ${m[1]} requests a day. Try again tomorrow.`],
];

function toEnglish(msg) {
  if (typeof msg !== 'string') return msg;
  if (EXACT[msg]) return EXACT[msg];
  for (const [re, fn] of PATTERNS) {
    const m = msg.match(re);
    if (m) return fn(m);
  }
  return msg;
}

module.exports = { toEnglish };
