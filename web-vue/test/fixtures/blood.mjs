// হোম / রক্ত: অতিথির আশেপাশের রিকোয়েস্ট, জরুরি আহ্বান, হাসপাতাল, রিকোয়েস্টের কাজ (নকল ডেটা, কোনো আসল রোগী নয়)
const H = 3600 * 1000;
const DAY = 24 * H;
const dhakaDay = (ms) => {
  const d = new Date(ms + 6 * H);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
};
const at = (ms) => ({ neededAtMillis: ms, neededByMillis: dhakaDay(ms) });

function guestItems() {
  const now = Date.now();
  return [
    { id: 'g1', patientGroup: 'A+', bags: 2, hospital: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', placeName: 'ময়মনসিংহ', ...at(now + 5 * H), urgent: true, travelFare: true, status: 'open', acceptedCount: 0, confirmedCount: 0, bagsReceived: 0, km: 2.4 },
    { id: 'g2', patientGroup: 'O-', bags: 3, hospital: 'Community Based Medical College Hospital', placeName: 'ময়মনসিংহ', ...at(now + 20 * H), urgent: false, travelFare: false, status: 'open', acceptedCount: 2, confirmedCount: 1, bagsReceived: 0, km: 4.8 },
    { id: 'g3', patientGroup: 'B+', bags: 1, hospital: 'লাবিব হাসপাতাল', placeName: 'ময়মনসিংহ', neededByMillis: dhakaDay(now + 2 * DAY), neededAtMillis: null, urgent: false, travelFare: false, status: 'open', acceptedCount: 1, confirmedCount: 1, bagsReceived: 0, km: 7.1 },
  ];
}

function appeal(authed) {
  const now = Date.now();
  return {
    id: 'e1',
    hospitalName: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল',
    district: 'ময়মনসিংহ',
    contactPhone: '01700000999',
    note: 'বাস দুর্ঘটনা — জরুরি বিভাগে রক্ত লাগবে।',
    groups: { 'O+': { need: 6, coming: 2, full: false }, 'A+': { need: 4, coming: 4, full: true }, 'B+': { need: 3, coming: 1, full: false } },
    createdAt: now - H,
    endsAt: now + 3 * H,
    myGroup: authed ? 'O+' : '',
    myStatus: '',
    canCome: !!authed,
    eligible: !!authed,
  };
}

export default {
  guest_requests: () => ({ items: guestItems() }),
  emergencies_active: (body, { authed }) => (body.district === 'none' ? { items: [], ask: [] } : { items: [appeal(authed)], ask: [] }),
  emergency_respond: () => ({ ok: true }),
  emergency_donated: () => ({ ok: true }),
  hospitals: () => ({
    items: [
      { id: 'dghs_1', nameEn: 'Mymensingh Medical College Hospital', nameBn: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', type: 'govt_mch', district: 'ময়মনসিংহ', upazilaId: null, unionId: null, address: '', phone: '' },
      { id: 'dghs_2', nameEn: 'Community Based Medical College Hospital', nameBn: '', type: 'private_hospital', district: 'ময়মনসিংহ', upazilaId: null, unionId: null, address: '', phone: '' },
      { id: 'dghs_3', nameEn: 'Labib Hospital', nameBn: 'লাবিব হাসপাতাল', type: 'private_hospital', district: 'ময়মনসিংহ', upazilaId: null, unionId: null, address: '', phone: '' },
    ],
  }),
  hospital_request: () => ({ ok: true }),
  request_create: () => ({ ok: true, id: 'new1' }),
  request_delete: () => ({ ok: true, notified: 0 }),
  request_reopen: () => ({ ok: true }),
  response_update: () => ({ ok: true }),
  confirm_donor: () => ({ ok: true }),
  '/api/respond': () => ({ ok: true }),
  '/api/close-request': () => ({ ok: true, marked: 1, closed: true }),
  '/api/notify-request': () => ({ ok: true, sent: 14 }),
  '/api/edit-request': () => ({ ok: true, notified: 2 }),
};
