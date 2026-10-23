// স্বাস্থ্য অংশের নকল উত্তর: প্রশ্ন-উত্তর, টিপস, প্রেসক্রিপশন আর তার কাজ (সার্ভারের আসল আকারে, কাল্পনিক ডেটা)
const now = Date.now();
const MIN = 60 * 1000;
const H = 60 * MIN;
const D = 24 * H;
const dhakaToday = () => {
  const d = new Date(now + 6 * H);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
};

const QUESTIONS = [
  { id: 'q1', text: 'আমার বয়স ৩৪, গত দুই সপ্তাহ ধরে সকালে ঘুম থেকে উঠলে মাথা ঘোরে আর হালকা বমি ভাব হয়। প্রেসার মেপেছি ১৩০/৯০। এটা কি চিন্তার কিছু? কোন ডাক্তার দেখানো উচিত?', authorName: 'রাশেদ আহমেদ', uid: 'x1', createdAt: now - 40 * MIN, answerCount: 2 },
  { id: 'q2', text: 'বাচ্চার (৩ বছর) জ্বর ১০২ ডিগ্রি, প্যারাসিটামল সিরাপ দিচ্ছি। কতক্ষণ পর পর দেওয়া যায় আর কখন হাসপাতালে নেওয়া দরকার?', authorName: null, anonymous: true, uid: 'x2', createdAt: now - 3 * H, answerCount: 1 },
  { id: 'q3', text: 'ডায়াবেটিস ধরা পড়েছে (HbA1c ৭.৮)। ভাত পুরোপুরি বাদ দিতে হবে কি? রোজ কতটুকু হাঁটা দরকার?', authorName: 'পারভেজ', uid: 'u1', createdAt: now - 26 * H, answerCount: 0, editedAt: now - 20 * H },
  { id: 'q4', text: 'রক্ত দেওয়ার পর কতদিন ভারী কাজ বা জিম করা যাবে না?', authorName: 'সুমাইয়া ইসলাম', uid: 'x3', createdAt: now - 3 * D, answerCount: 3 },
  { id: 'q5', text: 'গ্যাস্ট্রিকের ওষুধ (ওমিপ্রাজল) কি দীর্ঘদিন খাওয়া নিরাপদ? প্রায় এক বছর ধরে রোজ খাচ্ছি।', authorName: 'মাহবুব হাসান', uid: 'x4', createdAt: now - 9 * D, answerCount: 1 },
];

const ANSWERS = {
  q1: [
    { id: 'a1', text: 'সকালে মাথা ঘোরা আর প্রেসার ১৩০/৯০ — এটা প্রাথমিক উচ্চ রক্তচাপের লক্ষণ হতে পারে। এক সপ্তাহ সকাল-বিকাল প্রেসার মেপে লিখে রাখুন, লবণ কমান। মেডিসিন বিশেষজ্ঞ দেখান। হঠাৎ বুকে ব্যথা বা এক পাশ অবশ লাগলে সাথে সাথে হাসপাতালে যান।', authorName: 'ডা. ফারহানা ইয়াসমিন', badge: 'doctor', uid: 'd1', createdAt: now - 25 * MIN },
    { id: 'a2', text: 'পর্যাপ্ত পানি খাচ্ছেন কি না দেখুন। রাতে ৭–৮ ঘণ্টা ঘুম আর সকালে খালি পেটে না থাকাও জরুরি।', authorName: 'নাসরিন আক্তার', badge: 'nurse', uid: 'n1', createdAt: now - 12 * MIN, editedAt: now - 10 * MIN },
  ],
  q2: [
    { id: 'a3', text: 'ওজন অনুযায়ী প্রতি ৬ ঘণ্টা পর পর দেওয়া যায়, ২৪ ঘণ্টায় ৪ বারের বেশি নয়। খিঁচুনি, শ্বাসকষ্ট, নেতিয়ে পড়া বা ৩ দিনের বেশি জ্বর থাকলে দেরি না করে শিশু বিশেষজ্ঞ দেখান।', authorName: 'ডা. তানভীর রহমান', badge: 'doctor', uid: 'd2', createdAt: now - 2 * H },
  ],
};

const TIPS = [
  { id: 't1', title: 'গরমে পানিশূন্যতা এড়াতে', body: 'দিনে অন্তত ৮–১০ গ্লাস পানি খান। বাইরে বেরোলে সাথে পানির বোতল রাখুন।\n\nপ্রস্রাবের রং গাঢ় হলে বুঝবেন পানি কম হচ্ছে। শিশু আর বয়স্কদের দিকে বিশেষ খেয়াল রাখুন — তারা অনেক সময় পিপাসা টের পান না।', authorName: 'ডা. ফারহানা ইয়াসমিন', badge: 'doctor', uid: 'd1', createdAt: now - 5 * H },
  { id: 't2', title: 'ডায়াবেটিসে পায়ের যত্ন', body: 'প্রতিদিন রাতে পা ভালো করে দেখুন — কাটা, ফোসকা বা রং বদলেছে কি না। খালি পায়ে হাঁটবেন না। নখ সোজা করে কাটুন।\n\nছোট ক্ষতও না সারলে দেরি না করে ডাক্তার দেখান।', authorName: 'ডা. তানভীর রহমান', badge: 'doctor', uid: 'u1', createdAt: now - 2 * D, editedAt: now - D },
  { id: 't3', title: 'সকালের নাশতায় প্রোটিন', body: 'ডিম, ডাল বা দই — সকালের নাশতায় একটা প্রোটিন রাখুন। এতে দুপুর পর্যন্ত ক্ষুধা কম লাগে আর রক্তে চিনি হঠাৎ বাড়ে না।', authorName: 'সাবরিনা হক', badge: 'nutritionist', uid: 'p1', createdAt: now - 4 * D },
  { id: 't4', title: 'হাঁটার সঠিক নিয়ম', body: 'সপ্তাহে অন্তত ১৫০ মিনিট দ্রুত হাঁটুন। শুরুতে ১০ মিনিট দিয়ে শুরু করে ধীরে ধীরে বাড়ান। আরামদায়ক জুতা পরুন।', authorName: 'আরিফ চৌধুরী', badge: 'trainer', uid: 'p2', createdAt: now - 8 * D },
];

const DOCTOR = {
  name: 'ডা. ফারহানা ইয়াসমিন', degrees: 'এমবিবিএস, এফসিপিএস (মেডিসিন)', regNo: 'A-54321', specialties: ['medicine', 'endocrinology'],
  chamber: { name: 'সিটি ডায়াগনস্টিক সেন্টার', address: 'চরপাড়া মোড়, ময়মনসিংহ', phone: '01711000000' },
};
const RX = [
  {
    id: 'rx1', code: 'K7M2Q9XA4T', status: 'active', createdAt: now - 2 * D, doctorUid: 'd1', doctor: DOCTOR,
    patient: { name: 'পারভেজ', age: 34, gender: 'male' },
    complaints: 'দুই সপ্তাহ ধরে সকালে মাথা ঘোরা, ক্লান্তি', findings: { bp: '১৪০/৯০', pulse: '৮৪', weight: '৭২ কেজি' },
    diagnosis: 'প্রাথমিক উচ্চ রক্তচাপ', tests: ['CBC', 'Lipid profile', 'S. Creatinine'], testPlace: 'সিটি ডায়াগনস্টিক সেন্টার', testPlaceId: 'h1',
    medicines: [
      { name: 'Amlodipine', form: 'Tablet', strength: '5 mg', dose: '0+0+1', timing: 'after', days: 30, note: '' },
      { name: 'Omeprazole', form: 'Capsule', strength: '20 mg', dose: '1+0+1', timing: 'before', days: 14, note: 'খাবারের ৩০ মিনিট আগে' },
      { name: 'Napa', form: 'Tablet', strength: '500 mg', dose: '1+1+1', timing: 'after', days: 0, note: 'মাথাব্যথা হলে' },
    ],
    advice: 'লবণ কম খান।\nরোজ ৩০ মিনিট হাঁটুন।\nধূমপান করবেন না।', followUpDate: dhakaToday() + 14 * D, followUpBookDate: dhakaToday() + 14 * D, followUpNote: 'রিপোর্টসহ আসবেন', chamberIndex: 0, linked: true,
  },
  {
    id: 'rx2', code: 'P3V8N2WC6H', status: 'active', createdAt: now - 20 * D, doctorUid: 'd2',
    doctor: { name: 'ডা. তানভীর রহমান', degrees: 'এমবিবিএস, ডিসিএইচ', regNo: 'A-61230', specialties: ['pediatrics'], chamber: { name: 'শিশু স্বাস্থ্য কেন্দ্র', address: 'গাঙ্গিনারপাড়, ময়মনসিংহ' } },
    patient: { name: 'আয়ান', age: 3, gender: 'male' }, complaints: 'জ্বর ও কাশি ৩ দিন', findings: { temp: '১০২°F' }, diagnosis: 'ভাইরাল জ্বর',
    medicines: [{ name: 'Napa', form: 'Suspension', strength: '120 mg/5 ml', dose: '1 চামচ', timing: 'after', days: 3, note: 'জ্বর ১০০-র বেশি হলে' }],
    tests: [], testPlace: '', advice: 'প্রচুর তরল খাবার দিন।', followUpDate: null, followUpBookDate: null, followUpNote: '', chamberIndex: null, linked: true,
  },
  {
    id: 'rx3', code: 'Z9A4B7C2DE', status: 'cancelled', createdAt: now - 45 * D, doctorUid: 'd1', doctor: DOCTOR,
    patient: { name: 'পারভেজ', age: 34, gender: 'male' }, complaints: '', findings: {}, diagnosis: '',
    medicines: [{ name: 'Fexofenadine', form: 'Tablet', strength: '120 mg', dose: '0+0+1', timing: '', days: 7 }],
    tests: [], testPlace: '', advice: '', followUpDate: null, followUpBookDate: null, followUpNote: '', chamberIndex: 0, linked: true,
  },
];

// এখন কোন অবস্থায় — সার্ভারের RxState (backend/docs/RX-STATE.md): তালিকায় state, rx_get-এ টাইমলাইনসহ
const RX_STATE = {
  rx1: {
    phase: 'active', primary: 'accepted', tone: 'primary', cancelled: false, cancelledAt: null,
    medicine: { state: 'accepted', bought: 3, total: 3, pharmacyName: 'মেডিপ্লাস ফার্মেসি', mode: 'online' },
    lab: { state: 'sent', orders: 4, reported: 1, labName: 'রয়েল ল্যাব', reviewed: false },
    followUpDate: dhakaToday() + 14 * D, lastActivityAt: now - 2 * H,
  },
  rx2: {
    phase: 'active', primary: 'not_bought', tone: 'neutral', cancelled: false, cancelledAt: null,
    medicine: { state: 'not_bought', bought: 0, total: 1, pharmacyName: null, mode: null }, lab: null, followUpDate: null, lastActivityAt: now - 17 * D,
  },
  rx3: {
    phase: 'past', primary: 'cancelled', tone: 'muted', cancelled: true, cancelledAt: now - 44 * D,
    medicine: { state: 'not_bought', bought: 0, total: 1, pharmacyName: null, mode: null }, lab: null, followUpDate: null, lastActivityAt: now - 38 * D,
  },
};
const RX_TIMELINE = {
  rx1: [
    { kind: 'written', at: now - 2 * D },
    { kind: 'pharmacy_sent', at: now - 2 * D + H, name: 'লাইফ কেয়ার ফার্মেসি', mode: 'shop' },
    { kind: 'bought', at: now - 2 * D + 2 * H, name: 'লাইফ কেয়ার ফার্মেসি', count: 3 },
    { kind: 'lab_sent', at: now - 30 * H, name: 'রয়েল ল্যাব' },
    { kind: 'lab_sent', at: now - D, name: 'সিটি ডায়াগনস্টিক সেন্টার' },
    { kind: 'lab_sent', at: now - 20 * H, name: 'পপুলার ডায়াগনস্টিক' },
    { kind: 'lab_received', at: now - 10 * H, name: 'পপুলার ডায়াগনস্টিক' },
    { kind: 'lab_reported', at: now - 6 * H, name: 'সিটি ডায়াগনস্টিক সেন্টার' },
    { kind: 'pharmacy_sent', at: now - 5 * H, name: 'মেডিপ্লাস ফার্মেসি', mode: 'online' },
    { kind: 'pharmacy_accepted', at: now - 4 * H, name: 'মেডিপ্লাস ফার্মেসি', mode: 'online' },
    { kind: 'lab_sent', at: now - 2 * H, name: 'সিটি ডায়াগনস্টিক সেন্টার' },
    { kind: 'follow_up', at: dhakaToday() + 14 * D, ahead: true },
  ],
  rx2: [{ kind: 'written', at: now - 20 * D }],
  rx3: [{ kind: 'written', at: now - 45 * D }, { kind: 'cancelled', at: now - 44 * D }],
};
for (const r of RX) r.state = RX_STATE[r.id];

const ACTIVITY = {
  rx1: {
    medicines: [
      { i: 0, name: 'Amlodipine', form: 'Tablet', dose: '0+0+1', days: 30, need: 30, got: 15, left: 15, done: false },
      { i: 1, name: 'Omeprazole', form: 'Capsule', dose: '1+0+1', days: 14, need: 28, got: 28, left: 0, done: true },
      { i: 2, name: 'Napa', form: 'Tablet', dose: '1+1+1', days: 0, need: null, got: 10, left: null, done: false },
    ],
    dispensed: [{ name: 'লাইফ কেয়ার ফার্মেসি', at: now - 2 * D + 2 * H, items: [{ i: 0, qty: 15 }, { i: 1, qty: 28 }, { i: 2, qty: 10 }] }],
    orders: [
      { id: 'o1', pharmacyId: 'ph2', pharmacyName: 'মেডিপ্লাস ফার্মেসি', status: 'accepted', reason: '', createdAt: now - 5 * H, mode: 'online', price: '৳৬৪০', eta: 'আজ সন্ধ্যা ৭টার মধ্যে', deliveryFee: '৳৪০', missed: [] },
      { id: 'o2', pharmacyId: 'ph1', pharmacyName: 'লাইফ কেয়ার ফার্মেসি', status: 'done', reason: '', createdAt: now - 2 * D, mode: 'shop', price: '', eta: '', deliveryFee: '', missed: ['Napa'] },
    ],
    labOrders: [
      {
        id: 'l1', rxId: 'rx1', code: 'K7M2Q9XA4T', labId: 'lab1', labName: 'সিটি ডায়াগনস্টিক সেন্টার', patientName: 'পারভেজ', patientAge: 34, patientGender: 'male', doctorName: DOCTOR.name,
        tests: ['CBC', 'Lipid profile'], status: 'reported', createdAt: now - D, report: { items: [], note: '', reportedAt: now - 6 * H, photoCount: 0 },
      },
      {
        id: 'l2', rxId: 'rx1', code: 'K7M2Q9XA4T', labId: 'lab2', labName: 'পপুলার ডায়াগনস্টিক', patientName: 'পারভেজ', patientAge: 34, patientGender: 'male', doctorName: DOCTOR.name,
        tests: ['S. Creatinine'], status: 'received', createdAt: now - 20 * H, report: null,
        prices: { items: [{ test: 'S. Creatinine', name: 'Serum Creatinine', price: 350, tat: 'একই দিন' }], missing: [], subtotal: 350, discountPct: null, estimate: 350 },
      },
      {
        id: 'l3', rxId: 'rx1', code: 'K7M2Q9XA4T', labId: 'lab1', labName: 'সিটি ডায়াগনস্টিক সেন্টার', patientName: 'পারভেজ', patientAge: 34, patientGender: 'male', doctorName: DOCTOR.name,
        tests: ['S. Creatinine'], status: 'sent', createdAt: now - 2 * H, report: null,
        prices: { items: [{ test: 'S. Creatinine', name: 'Serum Creatinine', price: 400, tat: 'একই দিন' }], missing: [], subtotal: 400, discountPct: 10, estimate: 360 },
        home: {
          status: 'confirmed', date: dhakaToday() + D, slot: 'early', note: 'খালি পেটে থাকব', fee: 150, visitDate: dhakaToday() + D, time: '08:00', at: dhakaToday() + D + 2 * H,
          confirmNote: 'রাত ১০টার পর কিছু খাবেন না', declineNote: '', address: 'বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ', phone: '01711000401',
        },
      },
      // বাসা থেকে নমুনা — চাওয়া সময় পেরিয়ে গেছে (প্যাথলজি এখনো জানায়নি); ECG-র দাম তালিকায় নেই
      {
        id: 'l4', rxId: 'rx1', code: 'K7M2Q9XA4T', labId: 'lab3', labName: 'রয়েল ল্যাব', patientName: 'পারভেজ', patientAge: 34, patientGender: 'male', doctorName: DOCTOR.name,
        tests: ['CBC', 'ECG'], status: 'sent', createdAt: now - 30 * H, report: null,
        prices: { items: [{ test: 'CBC', name: 'CBC (Complete Blood Count)', price: 350, tat: 'একই দিন' }], missing: ['ECG'], subtotal: 350, discountPct: null, estimate: 350 },
        home: {
          status: 'expired', date: dhakaToday() - D, slot: 'morning', note: '', fee: 100, visitDate: 0, time: '', at: 0,
          confirmNote: '', declineNote: '', address: 'বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ', phone: '01711000401',
        },
      },
    ],
    reviews: [
      { id: 'l1', rxId: 'rx1', labOrderId: 'l1', doctorUid: 'd1', doctorName: DOCTOR.name, patientName: 'পারভেজ', labName: 'সিটি ডায়াগনস্টিক সেন্টার', tests: ['CBC', 'Lipid profile'], status: 'unpaid', fee: '৩০০ টাকা', noFee: false, payNumber: '01711000000', payMethods: ['bkash', 'nagad'], method: '', rejectNote: '', comment: '', createdAt: now - 6 * H, updatedAt: now - 6 * H },
    ],
    nextOpen: dhakaToday() + 2 * D,
  },
};

const LAB_REPORT = {
  item: {
    id: 'l1', rxId: 'rx1', code: 'K7M2Q9XA4T', labId: 'lab1', labName: 'সিটি ডায়াগনস্টিক সেন্টার', patientName: 'পারভেজ', patientAge: 34, patientGender: 'male', doctorName: DOCTOR.name,
    tests: ['CBC', 'Lipid profile'], status: 'reported', createdAt: now - D,
    report: {
      items: [
        { test: 'Hemoglobin', result: '13.8', unit: 'g/dL', range: '13 – 17', flag: '', group: 'CBC', code: 'cbc', key: 'hb' },
        { test: 'WBC', result: '11800', unit: '/cumm', range: '4000 – 11000', flag: 'high', group: 'CBC', code: 'cbc', key: 'wbc' },
        { test: 'Platelets', result: '2,40,000', unit: '/cumm', range: '150000 – 450000', flag: '', group: 'CBC', code: 'cbc', key: 'plt' },
        { test: 'Total cholesterol', result: '232', unit: 'mg/dL', range: '< 200', flag: 'high', group: 'Lipid profile', code: 'lipid', key: 'tc' },
        { test: 'HDL', result: '36', unit: 'mg/dL', range: '> 40', flag: 'low', group: 'Lipid profile', code: 'lipid', key: 'hdl' },
        { test: 'Comment', result: 'রক্তে চর্বির মাত্রা কিছুটা বেশি।', unit: '', range: '', flag: '', group: 'Lipid profile', code: 'lipid', key: 'note', long: true },
      ],
      note: 'নমুনা সকাল ৮টায় খালি পেটে নেওয়া।', reportedAt: now - 6 * H, photoCount: 0, photos: [],
    },
  },
  history: { 'lipid.tc': [{ result: '248', flag: 'high', at: now - 120 * D }], 'cbc.wbc': [{ result: '9200', flag: '', at: now - 120 * D }] },
};

const PHARMACIES = [
  { id: 'ph1', name: 'লাইফ কেয়ার ফার্মেসি', phone: '01811000001', address: 'চরপাড়া মোড়, ময়মনসিংহ', km: 0.4, openNow: true, keepsStock: true, have: 2, total: 2, missing: [], online: false, deliveryFee: '', onlineNote: '' },
  { id: 'ph2', name: 'মেডিপ্লাস ফার্মেসি', phone: '01811000002', address: 'মেডিকেল কলেজ রোড, ময়মনসিংহ', km: 1.2, openNow: true, keepsStock: true, have: 1, total: 2, missing: ['Napa'], online: true, deliveryFee: '৳৪০ (৩ কিমির মধ্যে)', onlineNote: 'রাত ১০টা পর্যন্ত ডেলিভারি' },
  { id: 'ph3', name: 'নিউ মা ফার্মেসি', phone: '01811000003', address: 'গাঙ্গিনারপাড়, ময়মনসিংহ', km: 2.6, openNow: false, keepsStock: false, have: 0, total: 2, missing: [], online: true, deliveryFee: '৳৫০', onlineNote: '' },
];
/** rx_lab_options — rx1-এর পরীক্ষা (CBC, Lipid profile, S. Creatinine) কোথায় কয়টা, কত */
const LAB_OPTIONS = [
  {
    id: 'lab1', name: 'সিটি ডায়াগনস্টিক সেন্টার', phone: '01911000001', address: 'চরপাড়া মোড়, ময়মনসিংহ', district: 'ময়মনসিংহ', upazilaId: null, facilityId: 'h1', homeCollection: true, hours: 'সকাল ৮টা – রাত ১০টা',
    suggested: true, priced: true, have: 3, total: 3, missing: [], subtotal: 2000, discountPct: 10, estimate: 1800, homeFee: 150, tat: 'একই দিন',
    items: [{ test: 'CBC', name: 'CBC (Complete Blood Count)', price: 400, tat: 'একই দিন' }, { test: 'Lipid profile', name: 'Lipid Profile', price: 1200, tat: 'একই দিন' }, { test: 'S. Creatinine', name: 'Serum Creatinine', price: 400, tat: 'একই দিন' }],
  },
  {
    id: 'lab2', name: 'পপুলার ডায়াগনস্টিক', phone: '01911000002', address: 'নতুন বাজার, ময়মনসিংহ', district: 'ময়মনসিংহ', upazilaId: null, facilityId: null, homeCollection: false, hours: '২৪ ঘণ্টা',
    suggested: false, priced: true, have: 2, total: 3, missing: ['Lipid profile'], subtotal: 700, discountPct: null, estimate: 700, homeFee: null, tat: 'একই দিন / ২৪ ঘণ্টা',
    items: [{ test: 'CBC', name: 'CBC (Complete Blood Count)', price: 350, tat: 'একই দিন' }, { test: 'S. Creatinine', name: 'Serum Creatinine', price: 350, tat: '২৪ ঘণ্টা' }],
  },
  {
    id: 'lab3', name: 'রয়েল ল্যাব', phone: '01911000003', address: 'গাঙ্গিনারপাড়, ময়মনসিংহ', district: 'ময়মনসিংহ', upazilaId: null, facilityId: null, homeCollection: true, hours: 'সকাল ৮টা – রাত ৯টা',
    suggested: false, priced: false, have: null, total: 3, missing: [], subtotal: null, discountPct: null, estimate: null, homeFee: null, tat: '', items: [],
  },
  {
    id: 'lab4', name: 'ত্রিশাল প্যাথলজি', phone: '01911000004', address: 'ত্রিশাল পৌরসভা', district: 'ময়মনসিংহ', upazilaId: null, facilityId: null, homeCollection: false, hours: 'সকাল ৮টা – সন্ধ্যা ৭টা',
    suggested: false, priced: true, have: 0, total: 3, missing: ['CBC', 'Lipid profile', 'S. Creatinine'], subtotal: null, discountPct: null, estimate: null, homeFee: null, tat: '', items: [],
  },
];
const LABS = [
  { id: 'lab1', name: 'সিটি ডায়াগনস্টিক সেন্টার', phone: '01911000001', address: 'চরপাড়া মোড়, ময়মনসিংহ', district: 'ময়মনসিংহ', upazilaId: null, facilityId: 'h1', homeCollection: true, hours: 'সকাল ৮টা – রাত ১০টা' },
  { id: 'lab2', name: 'পপুলার ডায়াগনস্টিক', phone: '01911000002', address: 'নতুন বাজার, ময়মনসিংহ', district: 'ময়মনসিংহ', upazilaId: null, facilityId: null, homeCollection: false, hours: '২৪ ঘণ্টা' },
];

const qView = (q, authed) => ({
  id: q.id, text: q.text, authorName: q.anonymous || !authed ? null : q.authorName, mine: authed && q.uid === 'u1', createdAt: q.createdAt, answerCount: q.answerCount,
});
const page = (list, before) => list.filter((x) => !before || x.createdAt < before).slice(0, 30);

export default {
  questions: (b, { authed }) => ({ items: page(QUESTIONS, b.before).map((q) => qView(q, authed)) }),
  question: (b, { authed }) => {
    const q = QUESTIONS.find((x) => x.id === b.id);
    if (!q) return { __status: 404, body: { error: 'প্রশ্নটা পাওয়া যায়নি' } };
    const mine = authed && q.uid === 'u1';
    return {
      question: { ...qView(q, authed), ...(mine ? { anonymous: !!q.anonymous } : {}), editedAt: q.editedAt || 0 },
      answers: (ANSWERS[q.id] || []).map((a) => ({ id: a.id, text: a.text, authorName: a.authorName, badge: a.badge, mine: authed && a.uid === 'u1', createdAt: a.createdAt, editedAt: a.editedAt || 0 })),
    };
  },
  ask: () => ({ id: 'q1' }),
  answer: () => ({ ok: true }),
  edit_question: () => ({ ok: true }),
  edit_answer: () => ({ ok: true }),
  delete_question: () => ({ ok: true }),
  delete_answer: () => ({ ok: true }),
  tips: (b, { authed }) => ({ items: page(TIPS, b.before).map((t) => ({ id: t.id, title: t.title, body: t.body, authorName: t.authorName, badge: t.badge, mine: authed && t.uid === 'u1', createdAt: t.createdAt, editedAt: t.editedAt || 0 })) }),
  post_tip: () => ({ id: 't1' }),
  edit_tip: () => ({ ok: true }),
  delete_tip: () => ({ ok: true }),
  badge_apply: () => ({ ok: true }),
  verify_docs_upload: () => ({ ok: true, saved: 1 }),
  report: () => ({ ok: true }),

  rx_mine: () => ({ items: RX }),
  rx_get: (b) => {
    const r = RX.find((x) => x.id === b.id);
    return r ? { item: { ...r, state: r.state && { ...r.state, timeline: RX_TIMELINE[r.id] ?? [] } } } : { __status: 404, body: { error: 'পাওয়া যায়নি' } };
  },
  rx_claim: (b) => (b.code === 'K7M2Q9XA4T' ? { ok: true, added: 1 } : { __status: 400, body: { error: 'কোড আর ফোন নম্বর মেলেনি' } }),
  rx_activity: (b) => ACTIVITY[b.rxId] || { medicines: (RX.find((x) => x.id === b.rxId)?.medicines || []).map((m, i) => ({ i, name: m.name, form: m.form || '', dose: m.dose || '', days: m.days || 0, need: null, got: 0, left: null, done: false })), dispensed: [], orders: [], labOrders: [], reviews: [], nextOpen: null },
  rx_pharmacy_options: (b) => ({ items: PHARMACIES.filter((p) => (b.mode !== 'online' || p.online) && (!b.q || p.name.includes(b.q))) }),
  rx_shop_find: (b) => (b.code === 'ABC234' ? { item: { id: 'ph1', name: PHARMACIES[0].name, address: PHARMACIES[0].address, phone: PHARMACIES[0].phone, online: false, deliveryFee: '' } } : { __status: 404, body: { error: 'এই কোডের কোনো ফার্মেসি নেই' } }),
  rx_send_pharmacy: () => ({ id: 'o9' }),
  rx_order_withdraw: () => ({ ok: true }),
  rx_send_lab: () => ({ id: 'l9' }),
  labs: () => ({ items: LABS }),
  rx_lab_options: () => ({ items: LAB_OPTIONS, lastHome: { address: 'বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ', phone: '01711000401' } }),
  lab_home_change: () => ({ ok: true }),
  lab_home_cancel: () => ({ ok: true }),
  lab_order_get: () => LAB_REPORT,
  review_pay: () => ({ ok: true }),
};
