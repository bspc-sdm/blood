// অ্যাকাউন্ট অংশের নকল উত্তর: নোটিফিকেশন, সাহায্য ও আপিল, কেয়ারগিভার ও পরিবার, ফোন যাচাই,
// অ্যাকাউন্ট মোছা, শেষ রক্তদানের তারিখ, ডাক্তারের সহকারী। সব নাম কাল্পনিক।
const MIN = 60 * 1000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

const inbox = () => {
  const now = Date.now();
  return {
    items: [
      { id: 'u1_8240000000001', title: 'রাকিব হাসান রক্ত দিতে রাজি হয়েছেন', body: 'আপনার B+ রিকোয়েস্টে সাড়া এসেছে। ফোন নম্বর দেখতে খুলুন।', data: { type: 'response', requestId: 'req_b1' }, read: false, createdAt: now - 6 * MIN },
      { id: 'u1_8240000000002', title: 'কাছে A+ রক্ত লাগবে', body: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল · ২ ব্যাগ · আজ বিকাল ৪:০০', data: { type: 'request', requestId: 'req_a7' }, read: false, createdAt: now - 48 * MIN },
      { id: 'u1_8240000000003', title: 'Lifolo টিমের উত্তর এসেছে', body: '"সাহায্য ও আপিল" খুলে দেখুন', data: { type: 'support', ticketId: 't1' }, read: true, createdAt: now - 5 * HOUR },
      { id: 'u1_8240000000004', title: 'অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে', body: 'ডা. ফারহানা ইসলাম — কাল সকাল ১০:৩০, সিরিয়াল ৭', data: { type: 'appointment', role: 'patient' }, read: true, createdAt: now - 20 * HOUR },
      { id: 'u1_8240000000005', title: 'আপনার ফার্মেসি তালিকায় যোগ হয়েছে', body: 'নিরাময় ফার্মেসি এখন আশেপাশের রোগীরা খুঁজে পাবেন।', data: { type: 'services', kind: 'pharmacy' }, read: true, createdAt: now - 2 * DAY },
      { id: 'u1_8240000000006', title: 'সালমা বেগম আপনাকে কেয়ারগিভার করতে চান', body: 'Lifolo → প্রোফাইল → কেয়ারগিভার থেকে রাজি হন', data: { type: 'care' }, read: true, createdAt: now - 3 * DAY },
      { id: 'u1_8240000000007', title: 'আবার রক্ত দিতে পারবেন', body: 'শেষ রক্তদানের ১২০ দিন পেরিয়েছে।', data: { type: 'eligible' }, read: true, createdAt: now - 9 * DAY },
    ],
  };
};

const tickets = () => {
  const now = Date.now();
  return {
    items: [
      {
        id: 't1', category: 'badge', status: 'answered', createdAt: now - 2 * DAY, updatedAt: now - 5 * HOUR,
        messages: [
          { from: 'user', text: 'আমার নার্স ব্যাজের আবেদন তিন দিন ধরে যাচাই চলছে। কবে অনুমোদন হবে জানাবেন?', at: now - 2 * DAY },
          { from: 'admin', text: 'আপনার BNMC সনদের ছবিটা ঝাপসা এসেছে। প্রোফাইল থেকে পরিষ্কার ছবি দিন, তারপর আমরা দ্রুত দেখে নেব।', at: now - 5 * HOUR },
        ],
      },
      {
        id: 't2', category: 'pharmacy', status: 'open', createdAt: now - 26 * HOUR, updatedAt: now - 26 * HOUR,
        messages: [{ from: 'user', text: 'ফার্মেসির খোলার সময় বদলাতে চাই, কিন্তু সেভ চাপলে সমস্যা দেখাচ্ছে।', at: now - 26 * HOUR }],
      },
      {
        id: 't3', category: 'account', status: 'closed', createdAt: now - 12 * DAY, updatedAt: now - 10 * DAY,
        messages: [
          { from: 'user', text: 'লগইনের কোড ইমেইলে আসছে না।', at: now - 12 * DAY },
          { from: 'admin', text: 'Spam ফোল্ডারটা দেখুন। এখন ঠিক আছে কি না জানাবেন।', at: now - 11 * DAY },
          { from: 'user', text: 'পেয়েছি, ধন্যবাদ।', at: now - 10 * DAY },
        ],
      },
    ],
  };
};

let family = [
  { id: 'm_a1b2c3d4', name: 'রাবেয়া খাতুন', relation: 'মা', age: 67, gender: 'female', phone: '01711000111' },
  { id: 'm_e5f6a7b8', name: 'সামির', relation: 'ছেলে', age: 9, gender: 'male', phone: '' },
];

const NOTIFY_CATS = ['blood_nearby', 'blood_emergency', 'my_request', 'appointments', 'rx', 'ambulance', 'calls', 'messages', 'community', 'business'];
const notifyPrefs = (b = {}) => ({ ok: true, all: b.all ?? true, cats: Object.fromEntries(NOTIFY_CATS.map((c) => [c, b.cats?.[c] ?? true])) });

export default {
  inbox: () => inbox(),
  inbox_read: () => ({ ok: true }),
  // নোটিফিকেশনের ধরন: সব চালু; সেভ করলে যা পাঠানো হলো তা (অবস্থা রাখে না — e2e/notify-settings.spec.ts নিজে রাখে)
  notify_prefs_get: () => notifyPrefs(),
  notify_prefs_set: (b) => notifyPrefs(b),

  tickets_mine: () => tickets(),
  ticket_create: () => ({ ok: true, id: 't9' }),
  ticket_reply: () => ({ ok: true }),

  care_list: () => ({
    caretakers: [
      { patientUid: 'u1', caretakerUid: 'c1', patientName: 'আরিফ হোসেন', caretakerName: 'তানভীর আহমেদ', caretakerEmail: 'tanvir.demo@example.com', status: 'active', createdAt: Date.now() - 20 * DAY },
      { patientUid: 'u1', caretakerUid: 'c2', patientName: 'আরিফ হোসেন', caretakerName: '', caretakerEmail: 'nadia.demo@example.com', status: 'invited', createdAt: Date.now() - DAY },
    ],
    caringFor: [
      { patientUid: 'p1', caretakerUid: 'u1', patientName: 'আমেনা বেগম', caretakerName: 'আরিফ হোসেন', caretakerEmail: '', status: 'active', createdAt: Date.now() - 40 * DAY },
      { patientUid: 'p2', caretakerUid: 'u1', patientName: 'করিম মিয়া', caretakerName: 'আরিফ হোসেন', caretakerEmail: '', status: 'invited', createdAt: Date.now() - 2 * DAY },
    ],
    family,
  }),
  care_invite: () => ({ ok: true }),
  care_respond: () => ({ ok: true }),
  care_remove: () => ({ ok: true }),
  family_get: () => ({ family }),
  family_set: (b) => {
    family = (b.family || []).map((m, i) => ({ ...m, id: m.id || `m_new${i}abc` }));
    return { family };
  },

  phone_send_code: () => ({ ok: true, to: '0171*****678' }),
  phone_verify_code: (b) => (b.code === '123456' ? { ok: true } : { __status: 400, body: { error: 'কোড মেলেনি' } }),

  delete_account: () => ({ ok: true, deleteAt: Date.now() + 30 * DAY }),
  donation_date_set: () => ({ ok: true }),
  verify_docs_upload: () => ({ ok: true, saved: 1, docsMissing: false, docsRequested: false }),

  rx_assisting: () => ({ items: [{ doctorUid: 'doc1', name: 'ডা. নাসরিন আক্তার', degrees: 'MBBS, FCPS (মেডিসিন)' }] }),
};
