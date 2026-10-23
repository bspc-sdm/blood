// পেমেন্টের নকল উত্তর (backend/docs/PAYMENTS.md-এর আকারে; সব নাম/নম্বর বানানো) — test/pages/pay.json-এর পাতাগুলোর জন্য।
// অবস্থা রাখে না; বদলের কাজ e2e/pay.spec.ts নিজে page.route দিয়ে দেখে। সুইচ চালু করে পাতাগুলো নিজেই (window.__lfTest.features)।
const now = Date.now();
const H = 3600 * 1000;
const methods = [{ method: 'bkash', number: '01711223344', type: 'personal' }, { method: 'nagad', number: '01811223344', type: 'merchant' }];
const req = (x) => ({ kind: 'pharmacy', bizId: 'u-work', bizName: 'নিউ লাইফ ফার্মেসি', payerUid: 'u1', refType: 'rx_order', refId: 'o1', purpose: 'delivery', amount: 450, methods, status: 'open', expiresAt: now + 20 * H, createdAt: now - H, ...x });
const MINE = [
  req({ id: 'prq1' }),
  req({ id: 'prq2', kind: 'lab', bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', refType: 'invoice', refId: 'inv2', purpose: 'invoice_due', amount: 195, status: 'claimed', claim: { method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678', at: now - 30 * 60 * 1000 } }),
  req({ id: 'prq3', kind: 'chamber', bizName: 'ডা. মাহমুদা আক্তার', refType: 'appointment', refId: 'a1', purpose: 'booking_advance', amount: 200, status: 'rejected', rejectReason: 'not_received', rejectedAt: now - 2 * H, claim: { method: 'nagad', trxId: 'AB12CD34', senderLast4: '4321', at: now - 3 * H } }),
  req({ id: 'prq4', kind: 'ambulance', bizName: 'জননী অ্যাম্বুলেন্স সার্ভিস', refType: 'ambreq', refId: 'ar1', purpose: 'ambulance_fare', amount: 1500, status: 'refund_claimed', confirmedAt: now - 30 * H, invoiceId: 'inv4', claim: { method: 'bkash', trxId: 'ZX98CV76BN', senderLast4: '1111', at: now - 31 * H }, refund: { trxId: 'RF12345678', amount: 1500, at: now - 2 * H } }),
];
export default {
  my_payments: () => ({ items: MINE, cursor: null }),
  pay_request_get: (b) => {
    const r = b.id ? MINE.find((x) => x.id === b.id) : MINE.find((x) => x.refType === b.refType && x.refId === b.refId && (!b.purpose || x.purpose === b.purpose));
    return r ? { request: r, side: 'payer' } : { __status: 404, body: { error: 'Not found', code: 'not_found' } };
  },
  pay_list_biz: (b) => ({ items: MINE.filter((x) => !b.status || x.status === b.status).map((x) => ({ ...x, payerName: 'রাকিব হাসান' })) }),
  pay_summary: () => ({ day: new Date(now + 6 * H).toISOString().slice(0, 10), collected: 1200, confirmedCount: 3, refunded: 0, openCount: 2, pendingClaims: 1 }),
  pay_methods_get: (b) => ({
    kind: b.kind, bizId: b.bizId, methods: methods.map((m) => ({ ...m, active: true, activeNumber: m.number, pendingUntil: null })),
    history: [{ method: 'nagad', change: 'added', oldLast4: null, newLast4: '3344', by: 'u-work', at: now - 30 * H }],
    ...(b.kind === 'chamber' ? { advance: [{ chamberKey: 'dghs_3', amount: 200, refundableUntilHoursBefore: 24 }], chambers: [{ chamberKey: 'dghs_3', name: 'Labib Hospital', nameBn: 'লাবিব হাসপাতাল' }] } : {}),
  }),
  pgw_status: () => ({ status: 'completed', amount: 299, trxId: 'BK12345678' }),
};
