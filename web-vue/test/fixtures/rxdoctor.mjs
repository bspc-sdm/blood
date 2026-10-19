// প্রেসক্রিপশন — ডাক্তার ও সহকারীর (/work/rx/*) নকল উত্তর, সার্ভারের আসল আকারে (server/lib/prescriptions.js, rxnet.js)।
// সব নাম, নম্বর আর ইমেইল বানানো। ডাক্তার নিজে = পরীক্ষার ব্যবহারকারী (work.mjs-এর অনুমোদিত ডাক্তার ব্যাজ);
// সহকারী হিসেবে doc1 (account.mjs-এর rx_assisting)। অন্য অংশের id (health.mjs) এলে তাদের উত্তরই যায়।
import health from './health.mjs';

const now = Date.now();
const MIN = 60 * 1000;
const H = 60 * MIN;
const D = 24 * H;
const dhakaToday = () => {
  const d = new Date(now + 6 * H);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
};
const T0 = dhakaToday();

const ME = { name: 'ডা. আরিফুল হক', degrees: 'এমবিবিএস, এফসিপিএস (মেডিসিন)', chambers: ['লাবিব হাসপাতাল', 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল'] };
const DOCTOR = {
  name: ME.name, degrees: ME.degrees, specialties: ['medicine', 'endocrinology'], regNo: 'A-45012',
  chamber: { name: 'লাবিব হাসপাতাল', address: 'চরপাড়া মোড়, ময়মনসিংহ', phone: '01700000101' },
};

// ---------- রোগী ----------
const PATIENTS = [
  { id: 'rxp1', name: 'মোছা. রাশিদা বেগম', age: 52, gender: 'female', phone: '01700000201', linked: true, createdAt: now - 40 * D, lastRxAt: now - 3 * D },
  { id: 'rxp2', name: 'মো. জসিম উদ্দিন', age: 61, gender: 'male', phone: '01700000202', linked: false, createdAt: now - 25 * D, lastRxAt: now - 25 * D },
  { id: 'rxp3', name: 'তানজিলা আক্তার', age: 27, gender: 'female', phone: '01700000203', linked: false, createdAt: now - 2 * H, lastRxAt: 0 },
  { id: 'rxp4', name: 'আব্দুল মালেক', age: 70, gender: 'male', phone: '01700000204', linked: true, createdAt: now - 90 * D, lastRxAt: now - 12 * D },
  { id: 'rxp5', name: 'নুসরাত জাহান', age: 8, gender: 'female', phone: '01700000205', linked: false, createdAt: now - 6 * D, lastRxAt: now - 6 * D },
  { id: 'rxp6', name: 'মো. শাহিন আলম', age: null, gender: '', phone: '01700000206', linked: false, createdAt: now - 30 * MIN, lastRxAt: 0 },
];
const APPOINTMENTS = [
  { id: 'rxa1', name: 'আব্দুল করিম', date: T0, serial: 1, status: 'confirmed', chamber: 'লাবিব হাসপাতাল' },
  { id: 'rxa2', name: 'সালমা খাতুন', date: T0, serial: 2, status: 'confirmed', chamber: 'লাবিব হাসপাতাল' },
  { id: 'rxa3', name: 'মো. হাবিবুর রহমান', date: T0, serial: 3, status: 'completed', chamber: 'লাবিব হাসপাতাল' },
  { id: 'rxa4', name: 'রবিউল আলম', date: T0 + D, serial: null, status: 'pending', chamber: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল' },
];
const APP_PATIENTS = [
  { appointmentId: 'rxa9', name: 'ফাতেমা ইয়াসমিন', count: 3, last: T0 - 9 * D },
  { appointmentId: 'rxa8', name: 'কামরুল হাসান', count: 1, last: T0 - 21 * D },
];
// সহকারী হিসেবে doc1-এর তালিকা
const DOC1 = {
  role: 'assistant',
  patients: [
    { id: 'rxq1', name: 'মো. আনোয়ার হোসেন', age: 45, gender: 'male', phone: '01700000301', linked: false, createdAt: now - 1 * H, lastRxAt: 0 },
    { id: 'rxq2', name: 'শারমিন সুলতানা', age: 33, gender: 'female', phone: '01700000302', linked: true, createdAt: now - 8 * D, lastRxAt: now - 8 * D },
  ],
  appointments: [{ id: 'rxb1', name: 'জাহিদুল ইসলাম', date: T0, serial: 4, status: 'confirmed', chamber: 'সিটি ডায়াগনস্টিক সেন্টার' }],
  appPatients: [],
};

// ---------- প্রেসক্রিপশন ----------
const rx = (o) => ({
  status: 'active', doctorUid: 'u-work', doctor: DOCTOR, complaints: '', findings: {}, diagnosis: '', medicines: [], tests: [], testPlace: '', testPlaceId: null,
  advice: '', followUpDate: null, followUpBookDate: null, followUpNote: '', chamberIndex: 0, linked: false, ...o,
});
const RXS = [
  rx({
    id: 'rxd1', code: 'M4KT8QZ2PA', createdAt: now - 3 * D, patient: { name: 'মোছা. রাশিদা বেগম', age: 52, gender: 'female' }, linked: true,
    complaints: 'এক মাস ধরে বেশি পিপাসা, বারবার প্রস্রাব, ক্লান্তি', findings: { bp: '১৩০/৮৫', pulse: '৮৮', weight: '৬৮ কেজি' }, diagnosis: 'টাইপ ২ ডায়াবেটিস',
    medicines: [
      { name: 'Comet', form: 'Tablet', strength: 'Metformin Hydrochloride', dose: '1+0+1', timing: 'after', days: 30, note: '' },
      { name: 'Secrin', form: 'Tablet', strength: 'Glimepiride', dose: '1+0+0', timing: 'before', days: 30, note: 'নাশতার ১৫ মিনিট আগে' },
    ],
    tests: ['HbA1c', 'Lipid profile', 'S. Creatinine'], testPlace: 'পপুলার ডায়াগনস্টিক, ময়মনসিংহ',
    advice: 'চিনি আর মিষ্টি বাদ দিন।\nরোজ ৩০ মিনিট হাঁটুন।', followUpDate: T0 + 27 * D, followUpBookDate: T0 + 27 * D, followUpNote: 'রিপোর্টসহ আসবেন',
  }),
  rx({
    id: 'rxd2', code: 'R7WD3NC9XH', createdAt: now - 40 * D, patient: { name: 'মোছা. রাশিদা বেগম', age: 52, gender: 'female' }, linked: true,
    complaints: 'মাথাব্যথা', diagnosis: 'টেনশন হেডেক', medicines: [{ name: 'Napa', form: 'Tablet', strength: 'Paracetamol', dose: '1+1+1', timing: 'after', days: 3, note: '' }],
  }),
  rx({
    id: 'rxd3', code: 'B2HP6TJ8KM', createdAt: now - 12 * D, patient: { name: 'আব্দুল মালেক', age: 70, gender: 'male' }, linked: true, chamberIndex: 1,
    complaints: 'হাঁটলে বুকে চাপ', findings: { bp: '১৫০/৯৫', pulse: '৯২', spo2: '৯৬%' }, diagnosis: 'উচ্চ রক্তচাপ, ইস্কেমিক হৃদরোগ সন্দেহ',
    medicines: [
      { name: 'Amdocal', form: 'Tablet', strength: 'Amlodipine', dose: '0+0+1', timing: 'after', days: 0, note: '' },
      { name: 'Ecosprin', form: 'Tablet', strength: 'Aspirin', dose: '0+1+0', timing: 'after', days: 0, note: '' },
      { name: 'Rosuva', form: 'Tablet', strength: 'Rosuvastatin', dose: '0+0+1', timing: 'after', days: 30, note: '' },
    ],
    tests: ['ECG', 'Lipid profile', 'Echocardiogram'], advice: 'লবণ কম খান। ভারী কাজ করবেন না।', followUpDate: T0 + 2 * D, followUpNote: 'ইসিজি নিয়ে আসবেন',
  }),
  rx({
    id: 'rxd4', code: 'H9CQ2VM5TW', createdAt: now - 25 * D, patient: { name: 'মো. জসিম উদ্দিন', age: 61, gender: 'male' }, status: 'cancelled',
    complaints: 'কাশি ২ সপ্তাহ', diagnosis: 'শ্বাসনালীর সংক্রমণ', medicines: [{ name: 'Azithrocin', form: 'Tablet', strength: 'Azithromycin', dose: '1+0+0', timing: 'before', days: 5, note: '' }],
  }),
  rx({
    id: 'rxd5', code: 'K3NA7XP4QD', createdAt: now - 6 * D, patient: { name: 'নুসরাত জাহান', age: 8, gender: 'female' },
    complaints: 'জ্বর ও গলা ব্যথা', findings: { temp: '১০১°F' }, diagnosis: 'টনসিলাইটিস',
    medicines: [
      { name: 'Fimoxyl', form: 'Suspension', strength: 'Amoxicillin', dose: '1+1+1', timing: 'after', days: 7, note: '১ চামচ করে' },
      { name: 'Napa', form: 'Suspension', strength: 'Paracetamol', dose: '1+1+1', timing: 'after', days: 3, note: 'জ্বর থাকলে' },
    ],
    advice: 'কুসুম গরম পানি দিয়ে গড়গড়া।',
  }),
  rx({
    id: 'rxd6', code: 'P8ZR4MB6WC', createdAt: now - 9 * D, patient: { name: 'ফাতেমা ইয়াসমিন', age: 39, gender: 'female' }, linked: true,
    complaints: 'থাইরয়েড ফলো-আপ', diagnosis: 'হাইপোথাইরয়েডিজম', medicines: [{ name: 'Thyrox', form: 'Tablet', strength: 'Levothyroxine', dose: '1+0+0', timing: 'before', days: 0, note: 'খালি পেটে' }],
    tests: ['TSH'],
  }),
];
const HISTORY = { rxp1: ['rxd1', 'rxd2'], rxp4: ['rxd3'], rxp2: ['rxd4'], rxp5: ['rxd5'], rxa9: ['rxd6'] };
let created = null;

// ---------- রিপোর্ট দেখা ----------
const REVIEWS = [
  {
    id: 'rvd1', rxId: 'rxd1', labOrderId: 'lod1', doctorUid: 'u-work', doctorName: ME.name, patientName: 'মোছা. রাশিদা বেগম', labName: 'পপুলার ডায়াগনস্টিক সেন্টার',
    tests: ['HbA1c', 'Lipid profile'], status: 'claimed', fee: '৩০০ টাকা', noFee: false, payNumber: '01700000555', payMethods: ['bkash', 'nagad'], method: 'bkash',
    trxId: 'BKH7Q2M9XA', rejectNote: '', comment: '', newRxId: null, createdAt: now - 5 * H, updatedAt: now - 40 * MIN,
  },
  {
    id: 'rvd2', rxId: 'rxd3', labOrderId: 'lod2', doctorUid: 'u-work', doctorName: ME.name, patientName: 'আব্দুল মালেক', labName: 'ল্যাবএইড ডায়াগনস্টিক',
    tests: ['Lipid profile', 'ECG'], status: 'paid', fee: '৩০০ টাকা', noFee: false, payNumber: '01700000555', payMethods: ['bkash', 'nagad'], method: 'nagad',
    trxId: 'NG5T8KD3PW', rejectNote: '', comment: '', newRxId: null, createdAt: now - 26 * H, updatedAt: now - 3 * H,
  },
  {
    id: 'rvd3', rxId: 'rxd6', labOrderId: 'lod3', doctorUid: 'u-work', doctorName: ME.name, patientName: 'ফাতেমা ইয়াসমিন', labName: 'পপুলার ডায়াগনস্টিক সেন্টার',
    tests: ['TSH'], status: 'done', fee: '৩০০ টাকা', noFee: false, payNumber: '01700000555', payMethods: ['bkash'], method: 'bkash', trxId: 'BKX2W6R8TN',
    rejectNote: '', comment: 'TSH স্বাভাবিক হয়েছে। একই ডোজে ওষুধ চলবে, ৩ মাস পর আবার TSH করাবেন।', newRxId: null, createdAt: now - 4 * D, updatedAt: now - 3 * D,
  },
  {
    id: 'rvd4', rxId: 'rxd5', labOrderId: 'lod4', doctorUid: 'u-work', doctorName: ME.name, patientName: 'নুসরাত জাহান', labName: 'সিটি ডায়াগনস্টিক সেন্টার',
    tests: ['CBC'], status: 'unpaid', fee: '৩০০ টাকা', noFee: false, payNumber: '01700000555', payMethods: ['bkash'], method: '', trxId: '',
    rejectNote: '', comment: '', newRxId: null, createdAt: now - 2 * D, updatedAt: now - 2 * D,
  },
];
const DOC1_REVIEWS = [
  {
    id: 'rvq1', rxId: 'rxq9', labOrderId: 'loq1', doctorUid: 'doc1', doctorName: 'ডা. নাসরিন আক্তার', patientName: 'শারমিন সুলতানা', labName: 'সিটি ডায়াগনস্টিক সেন্টার',
    tests: ['CBC', 'RBS'], status: 'claimed', fee: '২০০ টাকা', noFee: false, payNumber: '01700000666', payMethods: ['bkash'], method: 'bkash', trxId: 'BKA4N7C2QE',
    rejectNote: '', comment: '', newRxId: null, createdAt: now - 3 * H, updatedAt: now - 1 * H,
  },
];
const LAB_ORDERS = {
  lod2: {
    item: {
      id: 'lod2', rxId: 'rxd3', code: 'LQ7M2K', labName: 'ল্যাবএইড ডায়াগনস্টিক', patientName: 'আব্দুল মালেক', patientAge: 70, patientGender: 'male', doctorName: ME.name,
      tests: ['Lipid profile', 'ECG'], status: 'reported', createdAt: now - 2 * D,
      report: {
        items: [
          { test: 'Total cholesterol', result: '242', unit: 'mg/dL', range: '< 200', flag: 'high', group: 'Lipid profile', code: 'lipid', key: 'tc' },
          { test: 'Triglycerides', result: '188', unit: 'mg/dL', range: '< 150', flag: 'high', group: 'Lipid profile', code: 'lipid', key: 'tg' },
          { test: 'HDL', result: '38', unit: 'mg/dL', range: '> 40', flag: 'low', group: 'Lipid profile', code: 'lipid', key: 'hdl' },
          { test: 'LDL', result: '166', unit: 'mg/dL', range: '< 130', flag: 'high', group: 'Lipid profile', code: 'lipid', key: 'ldl' },
          { test: 'ECG', result: 'Sinus rhythm, rate 88/min. ST depression in V4–V6.', unit: '', range: '', flag: 'abnormal', group: 'ECG', long: true },
        ],
        note: 'রোগী খালি পেটে নমুনা দিয়েছেন।', reportedAt: now - 5 * H, photoCount: 0, photos: [],
      },
    },
    history: { 'lipid.tc': [{ result: '258', flag: 'high', at: now - 120 * D }], 'lipid.ldl': [{ result: '171', flag: 'high', at: now - 120 * D }] },
  },
};

const LABTESTS = [
  ['cbc', 'CBC (Complete Blood Count)'], ['rbs', 'RBS (Random Blood Sugar)'], ['fbs', 'FBS (Fasting Blood Sugar)'], ['hba1c', 'HbA1c'],
  ['lipid', 'Lipid profile'], ['creat', 'S. Creatinine'], ['sgpt', 'SGPT (ALT)'], ['tsh', 'TSH'], ['urine', 'Urine R/E'], ['esr', 'ESR'],
  ['crp', 'CRP'], ['uric', 'S. Uric acid'], ['ecg', 'ECG'], ['echo', 'Echocardiogram'], ['xray', 'X-ray chest P/A'], ['usg', 'USG of whole abdomen'],
].map(([code, name]) => ({ code, name, aliases: [], params: [] }));

const isDoc1 = (b) => b.doctorUid === 'doc1';

export default {
  rx_me: () => ME,
  rx_patients: (b) => (isDoc1(b) ? DOC1 : { role: 'doctor', patients: PATIENTS, appointments: APPOINTMENTS, appPatients: APP_PATIENTS }),
  rx_patient_add: (b) => {
    if (!String(b.name || '').trim()) return { __status: 400, body: { error: 'রোগীর নাম লিখুন' } };
    if (!/^01\d{9}$/.test(String(b.phone || '').replace(/\D/g, '').replace(/^88/, ''))) return { __status: 400, body: { error: 'রোগীর মোবাইল নম্বর দিন' } };
    return { patient: { id: 'rxp9', name: b.name, age: b.age ?? null, gender: b.gender || '', phone: b.phone, linked: false, createdAt: now, lastRxAt: 0 }, existed: false };
  },
  rx_patient_history: (b) => ({ items: (HISTORY[b.rxPatientId || b.appointmentId] || []).map((id) => RXS.find((r) => r.id === id)).filter(Boolean) }),
  rx_doctor_list: () => ({ items: [...RXS].sort((a, b) => b.createdAt - a.createdAt) }),
  rx_create: (b) => {
    if (!b.patient?.name) return { __status: 400, body: { error: 'রোগীর নাম লিখুন' } };
    created = rx({
      id: 'rxdnew', code: 'N6TF2WQ8HD', createdAt: Date.now(), patient: b.patient, complaints: b.complaints || '', findings: b.findings || {}, diagnosis: b.diagnosis || '',
      medicines: b.medicines || [], tests: b.tests || [], testPlace: b.testPlace || '', advice: b.advice || '', chamberIndex: b.chamberIndex ?? null,
      followUpDate: b.followUpDays ? T0 + b.followUpDays * D : null, followUpNote: b.followUpNote || '',
    });
    return { id: 'rxdnew', code: created.code };
  },
  rx_get: (b) => {
    if (b.id === 'rxdnew' && created) return { item: created };
    const r = RXS.find((x) => x.id === b.id);
    return r ? { item: r } : health.rx_get(b);
  },
  rx_cancel: () => ({ ok: true }),
  rx_team: () => ({ emails: ['rahima.assistant@example.com', 'serial.desk@example.com'] }),
  rx_team_set: (b) => {
    const emails = [...new Set((b.emails || []).map((e) => String(e).trim().toLowerCase()))];
    if (emails.length > 5) return { __status: 400, body: { error: 'সর্বোচ্চ ৫ জন সহকারী' } };
    if (emails.some((e) => !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(e))) return { __status: 400, body: { error: 'ইমেইলগুলো দেখে নিন' } };
    return { emails };
  },
  labtests_get: () => ({ tests: LABTESTS }),
  reviews_doctor: (b) => ({ items: isDoc1(b) ? DOC1_REVIEWS : REVIEWS }),
  review_confirm: () => ({ ok: true }),
  review_done: (b) => (String(b.comment || '').trim() ? { ok: true } : { __status: 400, body: { error: 'রোগীকে কী জানাবেন লিখুন' } }),
  review_settings: (b) => (b.set ? { enabled: !!b.enabled, noFee: !!b.noFee, fee: b.fee || '', payNumber: b.payNumber || '', payMethods: b.payMethods || [] }
    : { enabled: true, noFee: false, fee: '৩০০ টাকা', payNumber: '01700000555', payMethods: ['bkash', 'nagad'] }),
  lab_order_get: (b) => LAB_ORDERS[b.id] || health.lab_order_get(b),
};
