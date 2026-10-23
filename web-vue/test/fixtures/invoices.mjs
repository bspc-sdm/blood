// রসিদের নকল উত্তর (backend/docs/INVOICES.md-এর আকারে; সব নাম/সংখ্যা বানানো)। অবস্থা রাখে না —
// e2e/invoices.spec.ts বদলের কাজগুলো (বাতিল, আদায়, বিক্রি, বিল, ফি) page.route দিয়ে নিজে দেখে।
const now = Date.now();
const H = 3600 * 1000;
const D = 24 * H;
const ym = (ms) => new Date(ms + 6 * H).toISOString().slice(0, 7);

const inv = (x) => ({
  customer: {}, fees: [], payments: [], status: 'issued', offline: false, issuedBy: 'u-work', disc: 0,
  verifyUrl: `https://lifolo.com/v/${x.verifyCode}`,
  ...x,
});
// রোগী u1-এর রসিদ: ফার্মেসি (অনলাইন অর্ডার o1), প্যাথলজি, চেম্বার (অ্যাপয়েন্টমেন্ট a1), অ্যাম্বুলেন্স (ভাড়ার তালিকার চেয়ে বেশি)
export const MINE = [
  inv({
    id: 'inv1', no: 'W-000041', kind: 'pharmacy', bizId: 'ph2', bizName: 'মেডিপ্লাস ফার্মেসি', bizPhone: '01811000222', bizAddress: 'চরপাড়া, ময়মনসিংহ',
    patientUid: 'u1', customer: { name: 'পারভেজ', phone: '01712345678' }, refType: 'rx_order', refId: 'o1',
    lines: [{ name: 'Napa 500', qty: 20, price: 1.2, disc: 0, amount: 24 }, { name: 'Seclo 20', qty: 14, price: 7, disc: 9.8, amount: 88.2 }],
    subtotal: 112.2, disc: 2.2, fees: [{ label: 'ডেলিভারি চার্জ', amount: 40 }], total: 150, paid: 150, due: 0,
    payments: [{ at: now - 5 * H, amount: 150, method: 'bkash', ref: '9XK2' }], issuedAt: now - 5 * H, verifyCode: 'PH4K2M9QXA',
  }),
  inv({
    id: 'inv2', no: 'W-000007', kind: 'lab', bizId: 'lab1', bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', bizAddress: 'নতুন বাজার, ময়মনসিংহ',
    patientUid: 'u1', customer: { name: 'পারভেজ' }, refType: 'lab_order', refId: 'lab_o1',
    lines: [{ name: 'CBC', qty: 1, price: 400, disc: 0, amount: 400 }, { name: 'RBS', qty: 1, price: 150, disc: 0, amount: 150 }],
    subtotal: 550, disc: 55, fees: [{ label: 'বাসা থেকে নমুনা', amount: 100 }], total: 595, paid: 400, due: 195,
    payments: [{ at: now - 2 * D, amount: 400, method: 'cash' }], issuedAt: now - 2 * D, verifyCode: 'LB7H3N2WQP',
  }),
  inv({
    id: 'inv3', no: 'W-000112', kind: 'chamber', bizId: 'doc9', bizName: 'ডা. মাহমুদা আক্তার', refType: 'appointment', refId: 'a1', patientUid: 'u1',
    lines: [{ name: 'ভিজিট ফি — নতুন', qty: 1, price: 700, disc: 0, amount: 700 }],
    subtotal: 700, total: 700, paid: 700, due: 0, payments: [{ at: now - 6 * D, amount: 700, method: 'cash' }], issuedAt: now - 6 * D, verifyCode: 'CH2P8K4MZR',
  }),
  inv({
    id: 'inv4', no: 'W-000003', kind: 'ambulance', bizId: 'amb1', bizName: 'আল-মদিনা অ্যাম্বুলেন্স সার্ভিস', refType: 'ambreq', refId: 'ar1', patientUid: 'u1',
    lines: [{ name: 'ভাড়া — ময়মনসিংহ মেডিকেল কলেজ', qty: 1, price: 2500, disc: 0, amount: 2500 }],
    subtotal: 2500, fees: [{ label: 'অক্সিজেন', amount: 500 }], total: 3000, paid: 3000, due: 0, payments: [{ at: now - 9 * D, amount: 3000, method: 'cash' }],
    issuedAt: now - 9 * D, verifyCode: 'AM9Q3W7KPX', overFare: true, chartFare: 2200,
  }),
  inv({
    id: 'inv5', no: 'W-000040', kind: 'pharmacy', bizId: 'ph2', bizName: 'মেডিপ্লাস ফার্মেসি', patientUid: 'u1', status: 'void', voidReason: 'ভুল দাম — আবার রসিদ দেওয়া হয়েছে',
    lines: [{ name: 'Napa 500', qty: 20, price: 1.5, disc: 0, amount: 30 }], subtotal: 30, total: 30, paid: 30, due: 0,
    payments: [{ at: now - 5 * H - 60000, amount: 30, method: 'cash' }], issuedAt: now - 5 * H - 60000, verifyCode: 'PH0V0ID9QX',
  }),
];

// ব্যবসার দিক (u-work: ফার্মেসি, প্যাথলজি, চেম্বার; সহকারী হলে doc1-এর চেম্বার)
const biz = (kind, bizId) => {
  const base = {
    pharmacy: [
      inv({ id: 'b1', no: 'W-000201', kind, bizId, bizName: 'নিউ লাইফ ফার্মেসি', customer: { name: 'রাকিব', phone: '01812345678' }, refType: 'book_sale', refId: 's1',
        lines: [{ name: 'Napa', qty: 10, price: 1.2, disc: 0, amount: 12 }, { name: 'Seclo 20', qty: 14, price: 7, disc: 9.8, amount: 88.2 }],
        subtotal: 100.2, disc: 0.2, total: 100, paid: 80, due: 20, payments: [{ at: now - 2 * H, amount: 80, method: 'cash' }], issuedAt: now - 2 * H, verifyCode: 'BZ1A2B3C4D' }),
      inv({ id: 'b2', no: 'D1-000017', kind, bizId, bizName: 'নিউ লাইফ ফার্মেসি', deviceId: 'dev1', offline: true,
        lines: [{ name: 'Fexo 120', qty: 10, price: 9, disc: 0, amount: 90 }], subtotal: 90, disc: 20, total: 70, paid: 70, due: 0,
        payments: [{ at: now - 3 * H, amount: 70, method: 'nagad' }], issuedAt: now - 3 * H, verifyCode: 'BZ5E6F7G8H' }),
      inv({ id: 'b3', no: 'W-000199', kind, bizId, bizName: 'নিউ লাইফ ফার্মেসি', status: 'void', voidReason: 'ক্রেতা ফেরত দিয়েছেন — পাতা খোলেনি',
        lines: [{ name: 'Ace Plus', qty: 10, price: 2.5, disc: 0, amount: 25 }], subtotal: 25, total: 25, paid: 25, due: 0, issuedAt: now - 4 * H, verifyCode: 'BZ9J8K7L6M' }),
    ],
    lab: [
      inv({ id: 'b4', no: 'W-000088', kind, bizId, bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', customer: { name: 'মো. জাহিদ হাসান' }, refType: 'lab_order', refId: 'lo1',
        lines: [{ name: 'CBC', qty: 1, price: 400, disc: 0, amount: 400 }], subtotal: 400, total: 400, paid: 200, due: 200,
        payments: [{ at: now - H, amount: 200, method: 'cash' }], issuedAt: now - H, verifyCode: 'LB1Q2W3E4R' }),
    ],
    chamber: [
      inv({ id: 'b5', no: 'W-000301', kind, bizId, bizName: 'ডা. আরিফুল হক', refType: 'appointment', refId: 'q1', issuedBy: 'u-work',
        lines: [{ name: 'ভিজিট ফি — নতুন', qty: 1, price: 700, disc: 0, amount: 700 }], subtotal: 700, total: 700, paid: 700, due: 0,
        payments: [{ at: now - 30 * 60000, amount: 700, method: 'cash' }], issuedAt: now - 30 * 60000, verifyCode: 'CH1Z2X3C4V' }),
      inv({ id: 'b6', no: 'W-000300', kind, bizId, bizName: 'ডা. আরিফুল হক', issuedBy: 'someone-else', customer: { name: 'সরাসরি রোগী' },
        lines: [{ name: 'ভিজিট ফি — ফলো-আপ', qty: 1, price: 400, disc: 0, amount: 400 }], subtotal: 400, total: 400, paid: 400, due: 0,
        payments: [{ at: now - 3 * H, amount: 400, method: 'bkash' }], issuedAt: now - 3 * H, verifyCode: 'CH5B6N7M8K' }),
    ],
    ambulance: [
      inv({ id: 'b7', no: 'W-000012', kind, bizId, bizName: 'আল-মদিনা অ্যাম্বুলেন্স সার্ভিস', refType: 'ambreq', refId: 'ar9',
        lines: [{ name: 'ভাড়া', qty: 1, price: 1800, disc: 0, amount: 1800 }], subtotal: 1800, total: 1800, paid: 1800, due: 0,
        payments: [{ at: now - D, amount: 1800, method: 'cash' }], issuedAt: now - D, verifyCode: 'AM1A1A1A1A' }),
    ],
  };
  return base[kind] || [];
};
const sumOf = (items) => {
  const live = items.filter((i) => i.status !== 'void');
  const s = (f) => Math.round(live.reduce((a, i) => a + f(i), 0) * 100) / 100;
  return { count: live.length, total: s((i) => i.total), paid: s((i) => i.paid), due: s((i) => i.due) };
};
const all = () => [...MINE, ...['pharmacy', 'lab', 'chamber', 'ambulance'].flatMap((k) => biz(k, 'x'))];
const byId = (id) => all().find((i) => i.id === id);

export default {
  invoice_get: (b) => (byId(b.id) ? { invoice: byId(b.id) } : { __status: 404, body: { error: 'পাওয়া যায়নি' } }),
  invoice_verify: (b) => {
    if (b.code === 'DEMO0A1B2C') return { invoice: { bizName: 'ডেমো লাইফ ফার্মেসি', kind: 'pharmacy', no: 'W-000005', date: now - H, total: 250, paid: 250, due: 0, status: 'valid', demo: true, lines: 2 } };
    const i = all().find((x) => x.verifyCode === b.code);
    if (!i) return { __status: 404, body: { error: 'পাওয়া যায়নি' } };
    return { invoice: { bizName: i.bizName, kind: i.kind, no: i.no, date: i.issuedAt, total: i.total, paid: i.paid, due: i.due, status: i.status === 'void' ? 'void' : 'valid', lines: i.lines.length } };
  },
  invoice_list_biz: (b) => {
    let items = biz(b.kind, b.bizId);
    if (b.q) items = items.filter((i) => i.no.includes(b.q) || (i.customer.name || '').includes(b.q) || (i.customer.phone || '').includes(b.q));
    return { items, cursor: null, sum: sumOf(items) };
  },
  invoice_pay: (b) => {
    const i = byId(b.id);
    const amount = Math.min(i.due, Number(b.amount));
    return { invoice: { ...i, paid: i.paid + amount, due: i.due - amount, payments: [...i.payments, { at: Date.now(), amount, method: b.method }] } };
  },
  invoice_void: (b) => ({ invoice: { ...byId(b.id), status: 'void', voidReason: b.reason } }),
  invoice_export_csv: (b) => ({ csv: 'no,date,total\nW-000201,2026-10-23,100.00\n', filename: `lifolo-${b.kind}-${b.month}.csv` }),
  my_invoices: () => ({ items: MINE, cursor: null }),
  my_expenses: (b) => (b.month === ym(now) ? { month: b.month, total: 4445, byKind: { pharmacy: 150, lab: 595, chamber: 700, ambulance: 3000 }, count: 4 } : { month: b.month, total: 0, byKind: {}, count: 0 }),
  lab_bill_create: (b) => {
    const lines = (b.lines || []).map((l) => ({ ...l, qty: l.qty ?? 1, disc: l.disc ?? 0, amount: (l.qty ?? 1) * l.price - (l.disc ?? 0) }));
    const subtotal = lines.reduce((a, l) => a + l.amount, 0);
    const fees = b.fees || [];
    const total = subtotal - (b.disc || 0) + fees.reduce((a, f) => a + f.amount, 0);
    const paid = (b.payments || []).reduce((a, p) => a + p.amount, 0);
    return { invoice: inv({ id: 'nl1', no: 'W-000089', kind: 'lab', bizId: 'u-work', bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', customer: b.customer || {}, lines, subtotal, disc: b.disc || 0, fees, total, paid, due: total - paid, payments: (b.payments || []).map((p) => ({ ...p, at: Date.now() })), issuedAt: Date.now(), verifyCode: 'LBNEW00001' }) };
  },
  chamber_fee_collect: (b) => ({
    invoice: inv({ id: 'nc1', no: 'W-000302', kind: 'chamber', bizId: b.doctorUid || 'u-work', bizName: 'ডা. আরিফুল হক', customer: { name: b.walkIn?.name }, refType: b.appointmentId ? 'appointment' : undefined, refId: b.appointmentId,
      lines: [{ name: 'ভিজিট ফি', qty: 1, price: b.amount, disc: 0, amount: b.amount }], subtotal: b.amount, total: b.amount, paid: b.amount, due: 0,
      payments: [{ at: Date.now(), amount: b.amount, method: b.method }], issuedAt: Date.now(), verifyCode: 'CHNEW00001' }),
  }),
  chamber_checkin: () => ({ ok: true }),
  invoice_report: () => ({ ok: true }),
  assistant_chambers: (b) => ({
    doctorUid: b.doctorUid || 'u-work', doctorName: 'ডা. আরিফুল হক',
    chambers: [
      { chamberKey: 'dghs_3', name: 'Labib Hospital', nameBn: 'লাবিব হাসপাতাল', fee: '৭০০ টাকা', noFee: false },
      { chamberKey: 'dghs_9', name: 'Mymensingh Medical College Hospital', nameBn: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', fee: '৫০০ টাকা', noFee: false },
    ],
  }),
};
