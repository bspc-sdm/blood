// ছোট সাহায্যকারী আর লেবেল (অ্যাপের সাথে মিলিয়ে) — server/public/admin/app.js-এর মতো

/** সার্ভারের উত্তর — আকার পাতাভেদে আলাদা, তাই খোলা রাখা */
export type J = any;

const BN = '০১২৩৪৫৬৭৮৯';
export const BN_DIGITS = BN;
export const bn = (v: unknown) => String(v ?? '').replace(/[0-9]/g, (d) => BN[Number(d)]);

const tz = { timeZone: 'Asia/Dhaka' };
const dFmt = new Intl.DateTimeFormat('bn-BD', { ...tz, day: 'numeric', month: 'short', year: 'numeric' });
const dtFmt = new Intl.DateTimeFormat('bn-BD', { ...tz, day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
export const dayFmt = new Intl.DateTimeFormat('bn-BD', { ...tz, day: 'numeric', month: 'short' });
const valid = (ms: unknown): ms is number => typeof ms === 'number' && ms > 0 && !Number.isNaN(new Date(ms).getTime());
export const fmtDate = (ms: unknown) => (valid(ms) ? dFmt.format(new Date(ms)) : '—');
export const fmtDT = (ms: unknown) => (valid(ms) ? dtFmt.format(new Date(ms)) : '—');
export function ago(ms: unknown) {
  if (!valid(ms)) return '—';
  const m = Math.floor((Date.now() - ms) / 60000);
  if (m < 1) return 'এইমাত্র';
  if (m < 60) return `${bn(m)} মিনিট আগে`;
  const hr = Math.floor(m / 60);
  if (hr < 24) return `${bn(hr)} ঘণ্টা আগে`;
  const d = Math.floor(hr / 24);
  return d < 30 ? `${bn(d)} দিন আগে` : fmtDate(ms);
}
export const num = (n: unknown) => (n == null ? '—' : bn(Number(n).toLocaleString('en-IN')));

export const matches = (q: string, ...fields: unknown[]) => !q || fields.join(' ').toLowerCase().includes(q.trim().toLowerCase());

let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function toast(msg: string) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, 3200);
}
export function store(key: string, value: string | null) {
  try {
    if (value == null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* না থাকলেও চলে */
  }
}
export function load(key: string) {
  try {
    return localStorage.getItem(key) || '';
  } catch {
    return '';
  }
}

export const L: Record<string, Record<string, string>> = {
  badge: { doctor: 'ডাক্তার', nurse: 'নার্স', nutritionist: 'পুষ্টিবিদ', trainer: 'ফিটনেস ট্রেইনার', expert: 'স্বাস্থ্য বিশেষজ্ঞ' },
  hospitalType: {
    govt_mch: 'সরকারি মেডিকেল কলেজ হাসপাতাল', govt_specialized: 'সরকারি বিশেষায়িত হাসপাতাল',
    govt_district: 'জেলা / সদর হাসপাতাল', govt_uhc: 'উপজেলা স্বাস্থ্য কমপ্লেক্স', govt_hospital: 'অন্যান্য সরকারি হাসপাতাল',
    private_hospital: 'বেসরকারি হাসপাতাল', clinic: 'ক্লিনিক', diagnostic: 'ডায়াগনস্টিক সেন্টার',
  },
  amb: { general: 'সাধারণ', ac: 'এসি', icu: 'আইসিইউ', freezer: 'ফ্রিজার ভ্যান' },
  report: { question: 'প্রশ্ন', answer: 'উত্তর', tip: 'টিপস', ambulance: 'অ্যাম্বুলেন্স', pharmacy: 'ফার্মেসি', homecare: 'বাসায় সেবা', provider: 'ডাক্তার/নার্স (চেম্বার)', bloodbank: 'ব্লাড ব্যাংক' },
  bankKind: { govt: 'সরকারি হাসপাতাল', private: 'বেসরকারি', org: 'স্বেচ্ছাসেবী সংগঠন' },
  bankService: { whole_blood: 'পূর্ণ রক্ত', components: 'প্লাজমা ও অন্যান্য অংশ', platelets: 'প্লাটিলেট', screening: 'স্ক্রিনিং ও ক্রস-ম্যাচ', donate_here: 'এখানে রক্ত দেওয়া যায়' },
  listing: { pending: 'অনুমোদনের অপেক্ষায়', approved: 'অনুমোদিত', suspended: 'বন্ধ', rejected: 'বাতিল' },
  appt: { pending: 'অপেক্ষায়', confirmed: 'নিশ্চিত', declined: 'ফিরিয়ে দেওয়া', cancelled: 'বাতিল', expired: 'মেয়াদ শেষ', completed: 'সম্পন্ন' },
  request: { open: 'চালু', closed: 'বন্ধ' },
  ticket: { open: 'উত্তরের অপেক্ষায়', answered: 'উত্তর দেওয়া হয়েছে', closed: 'বন্ধ' },
  category: {
    account: 'অ্যাকাউন্ট', badge: 'ব্যাজ (ডাক্তার/নার্স)', pharmacy: 'ফার্মেসি', ambulance: 'অ্যাম্বুলেন্স', bloodbank: 'ব্লাড ব্যাংক', homevisit: 'হোম ভিজিট (আগের)', homecare: 'বাসায় সেবা',
    medorder: 'ওষুধের অর্ডার', appointment: 'অ্যাপয়েন্টমেন্ট', blood: 'রক্তের অনুরোধ', other: 'অন্য কিছু',
  },
  log: {
    admin_badge_resolve: 'ব্যাজ', admin_pharmacy_resolve: 'ফার্মেসি', admin_ambulance_resolve: 'অ্যাম্বুলেন্স', admin_bloodbank_resolve: 'ব্লাড ব্যাংক', admin_bloodbank_add: 'ব্লাড ব্যাংক যোগ', admin_bloodbank_edit: 'ব্লাড ব্যাংক সম্পাদনা', admin_bloodbank_delete: 'ব্লাড ব্যাংক মোছা',
    admin_hospital_resolve: 'হাসপাতাল', admin_report_resolve: 'রিপোর্ট', admin_delete_post: 'পোস্ট মোছা (অ্যাপ)', admin_user_section: 'ব্যবহারকারীর তথ্য দেখা', admin_verify_docs: 'কাগজের ছবি দেখা', admin_request_docs: 'কাগজের ছবি চাওয়া', admin_ticket_reply: 'আপিল',
    admin_emergency_create: 'জরুরি আহ্বান', admin_emergency_more: 'জরুরি: আরও জানানো', admin_emergency_group: 'জরুরি: গ্রুপ', admin_emergency_close: 'জরুরি: শেষ',
    admin_user_action: 'ব্যবহারকারী', admin_add: 'অ্যাডমিন যোগ', admin_remove: 'অ্যাডমিন বাদ', correction_resolve: 'তারিখ সংশোধন',
    admin_lab_resolve: 'প্যাথলজি', admin_catalog_set: 'বাছাইয়ের তালিকা', admin_labtests_set: 'রিপোর্টের ছক',
  },
  detail: {
    approved: 'অনুমোদন', rejected: 'বাতিল', suspended: 'বন্ধ', approve: 'অনুমোদন', reject: 'বাতিল', removed: 'সরানো হয়েছে', kept: 'রেখে দেওয়া',
    replied: 'উত্তর', 'replied+closed': 'উত্তর দিয়ে বন্ধ', closed: 'বন্ধ', homevisit_unban: 'বাসায় সেবার নিষেধাজ্ঞা তোলা',
    homevisit_ban_patient: 'হোম ভিজিট বন্ধ (রোগী)', homevisit_ban_nurse: 'হোম ভিজিট বন্ধ (নার্স)', homecare_ban: 'বাসায় সেবা বন্ধ', provider_unsuspend: 'ডাক্তারের তালিকা আবার চালু', provider_suspend: 'পেশাজীবীর তালিকা সাময়িক বন্ধ', provider_chambers_remove: 'চেম্বার সরানো', med_unblock: 'ওষুধের অর্ডারের ব্লক তোলা',
    disable: 'অ্যাকাউন্ট বন্ধ', enable: 'অ্যাকাউন্ট খোলা',
  },
};

export const WEEKDAYS = ['রবি', 'সোম', 'মঙ্গল', 'বুধ', 'বৃহস্পতি', 'শুক্র', 'শনি'];
export const FIELD_LABEL: Record<string, string> = {
  name: 'নাম', phone: 'ফোন', vehicleNo: 'গাড়ির নম্বর', licenseNo: 'লাইসেন্স', district: 'জেলা', upazilaId: 'উপজেলা', address: 'ঠিকানা',
  kind: 'ধরন', types: 'ধরন', area: 'এলাকা', fare: 'ভাড়া', openAt: 'খোলে', closeAt: 'বন্ধ হয়', open24: '২৪ ঘণ্টা', services: 'সেবা',
  closedDays: 'সাপ্তাহিক বন্ধ', delivery: 'হোম ডেলিভারি', deliveryNote: 'ডেলিভারির কথা', discount: 'ছাড়', lat: 'লোকেশন',
};
export function showVal(field: string, v: J): string {
  if (v == null || v === '' || (Array.isArray(v) && !v.length)) return '—';
  if (field === 'closedDays') return v.map((d: number) => WEEKDAYS[d]).join(', ');
  if (typeof v === 'boolean') return v ? 'হ্যাঁ' : 'না';
  if (Array.isArray(v)) return v.map((x) => L.amb[x] || L.bankService[x] || x).join(', ');
  return bn(v);
}

export const REASONS = ['লাইসেন্স যাচাই করা যায়নি', 'ফোন নম্বর কাজ করে না', 'তথ্য অসম্পূর্ণ বা ভুল', 'একই তালিকা আগে থেকে আছে', 'রিপোর্ট সত্য পাওয়া গেছে', 'নিয়ম ভঙ্গ হয়েছে'];
export const GROUPS = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];
export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));
