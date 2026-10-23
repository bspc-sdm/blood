// পেমেন্ট (PAYMENTS.md, PAYMENTS-SECURITY-REVIEW.md): সার্ভারের আকার, TrxID/শেষ ৪ নিয়ম, অবস্থার ধারা, সুইচ, নোটিফিকেশনের ঠিকানা,
// SSE pay_* → আবার আনা, PaySheet (সতর্কতা, নম্বর লেখার ঘর নেই, ফেরতের নিয়ম, দাবি, আপত্তি, ফেরত "পেয়েছি"), ব্যবসার তিন টিক,
// চ্যাটের "টাকার নম্বর" সতর্কতা।
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { RouterLinkStub, flushPromises, mount, config } from '@vue/test-utils';
import { VueQueryPlugin } from '@tanstack/vue-query';

const call = vi.fn();
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  errorText: (e: unknown) => (e instanceof Error ? e.message : 'x'),
  LoginRequired: class extends Error {},
  onFeatureOff: () => {},
  postPublic: () => Promise.resolve({}),
  ServerError: class extends Error {
    constructor(m: string, public status: number, public code?: string) {
      super(m);
    }
  },
}));
import { ServerError } from '@/lib/api';
import { queryClient } from '@/lib/query';
import { setFeaturesForTest } from '@/lib/features';
import {
  activeMethods, canClaim, canConfirm, canRefund, canReject, last4Clean, last4Valid, linkDue, needsRefundAck, normMobile, parsePayPage, parsePayRequest,
  parseSummary, payLinkOf, pickRequest, timelineOf, trxClean, trxValid,
} from '@/sections/pay/model';
import { payErrText, statusShort, statusText, statusTone } from '@/sections/pay/text';
import { bizKindOn, onlineFeeOnPay, patientPayOn, payOn } from '@/sections/pay/features';
import { handlePayEvent } from '@/sections/pay/live';
import { payTarget, inboxTarget } from '@/sections/account/util';
import PaySheet from '@/sections/pay/PaySheet.vue';
import PayDecide from '@/sections/pay/PayDecide.vue';
import MessageBubble from '@/chat/MessageBubble.vue';
import { parseMessage } from '@/chat/api';

config.global.plugins = [[VueQueryPlugin, { queryClient }]];
const DialogStub = defineComponent({ props: ['title'], setup: (p, { slots }) => () => h('div', { 'data-test': 'dialog' }, [h('h2', p.title), slots.default?.(), slots.actions?.()]) });
const stubs = { RouterLink: RouterLinkStub, Dialog: DialogStub };

const H = 3600_000;
const REQ = {
  id: 'prq_abc123XYZ', kind: 'pharmacy', bizId: 'u-work', bizName: 'নিউ লাইফ ফার্মেসি', payerUid: 'u1', refType: 'rx_order', refId: 'o1', purpose: 'delivery', amount: 450,
  methods: [{ method: 'bkash', number: '01711223344', type: 'personal' }, { method: 'nagad', number: '01811223344', type: 'merchant' }],
  status: 'open', expiresAt: Date.now() + 20 * H, createdAt: Date.now() - H,
};
const req = (o: Record<string, unknown> = {}) => parsePayRequest({ ...REQ, ...o });

beforeEach(() => {
  localStorage.setItem('lf.lang', 'bn');
  setFeaturesForTest({ pay_manual: true, pay_delivery: true, inv_pharmacy: true, pay_lab: true, inv_lab: true, pay_due: true, pay_chamber_advance: true, pay_ambulance: true, inv_ambulance: true });
  call.mockReset();
  call.mockResolvedValue({ ok: true });
  queryClient.clear();
});

describe('সার্ভারের আকার', () => {
  it('pay request: মাধ্যম (সক্রিয় ধরা), দাবি, ফেরত, অচেনা মাধ্যম বাদ', () => {
    const r = req({
      methods: [...REQ.methods, { method: 'paypal', number: '1' }],
      status: 'refund_claimed', claim: { method: 'nagad', trxId: 'AB12CD34', senderLast4: '5678', at: 5, byName: 'রহিম' },
      refund: { trxId: 'RF123456', amount: 450, at: 9 }, confirmedAt: 7, invoiceId: 'inv9', refundableUntil: 11, rejectedAt: null,
    });
    expect(r.methods.map((m) => m.method)).toEqual(['bkash', 'nagad']);
    expect(r.methods[1].type).toBe('merchant');
    expect(activeMethods(r)).toHaveLength(2);
    expect(r.claim).toMatchObject({ method: 'nagad', trxId: 'AB12CD34', senderLast4: '5678', byName: 'রহিম' });
    expect(r.refund).toEqual({ trxId: 'RF123456', amount: 450, at: 9, ackAt: null });
    expect(r.refundableUntil).toBe(11);
    expect(parsePayRequest({ status: 'weird' }).status).toBe('open');
  });
  it('উত্তর থেকে অনুরোধ, পাতা (cursor), সারাংশ (openCount/pendingClaims)', () => {
    expect(pickRequest({ ok: true, request: { id: 'r1', status: 'open' } })?.id).toBe('r1');
    expect(pickRequest({ ok: true })).toBeNull();
    expect(parsePayPage({ items: [{ id: 'a' }], cursor: 'c1' })).toMatchObject({ next: 'c1' });
    expect(parseSummary({ collected: 900, confirmedCount: 2, openCount: 3, pendingClaims: 1 })).toMatchObject({ collected: 900, confirmedCount: 2, open: 3, pending: 1 });
  });
  it('জিনিসের সাথে লাগানো পেমেন্ট: payRequest {…} বা payRequestId; বাকি কি না', () => {
    expect(payLinkOf({ payRequestId: 'p1', payStatus: 'claimed' })).toMatchObject({ id: 'p1', status: 'claimed', request: null });
    expect(payLinkOf({ payRequest: { id: 'p2', status: 'open', amount: 200 } })?.request?.amount).toBe(200);
    expect(payLinkOf({})).toBeNull();
    expect(linkDue(payLinkOf({ payRequestId: 'p1' }))).toBe(true);
    expect(linkDue(payLinkOf({ payRequestId: 'p1', payStatus: 'confirmed' }))).toBe(false);
  });
});

describe('নিয়ম', () => {
  it('TrxID: bKash ঠিক ১০, বাকিরা ৬–২০, বড় হাতের, ফাঁকা/ড্যাশ বাদ', () => {
    expect(trxClean('9gh7 k2-lm1pzz', 'bkash')).toBe('9GH7K2LM1P');
    expect(trxValid('9GH7K2LM1P', 'bkash')).toBe(true);
    expect(trxValid('9GH7K2LM1', 'bkash')).toBe(false);
    expect(trxValid('7A3K9Q', 'nagad')).toBe(true);
    expect(trxValid('7A3K9', 'rocket')).toBe(false);
    expect(trxClean('a'.repeat(30), 'upay')).toHaveLength(20);
  });
  it('শেষ ৪ অঙ্ক (বাংলা অঙ্কও), মোবাইল নম্বর', () => {
    expect(last4Clean('৫৬৭৮৯')).toBe('5678');
    expect(last4Valid('5678')).toBe(true);
    expect(last4Valid('567')).toBe(false);
    expect(normMobile('+8801711223344')).toBe('01711223344');
    expect(normMobile('০১৭১১২২৩৩৪৪')).toBe('01711223344');
    expect(normMobile('01211223344')).toBeNull();
  });
  it('কে কী পারে: দাবি (খোলা/পায়নি, মেয়াদ থাকলে), নিশ্চিত (রোগী জানালে, বা ভুল করে "পাইনি" বলার পর), পাইনি, ফেরত, ফেরত পেয়েছি', () => {
    expect(canClaim(req())).toBe(true);
    expect(canClaim(req({ expiresAt: Date.now() - 1 }))).toBe(false);
    expect(canClaim(req({ status: 'claimed' }))).toBe(false);
    const claim = { method: 'bkash', trxId: 'X', senderLast4: '1', at: 1 };
    expect(canConfirm(req({ status: 'claimed', claim }))).toBe(true);
    expect(canConfirm(req({ status: 'rejected', claim }))).toBe(true);
    expect(canConfirm(req({ status: 'open' }))).toBe(false);
    expect(canReject(req({ status: 'rejected', claim }))).toBe(false);
    expect(canRefund(req({ status: 'confirmed' }))).toBe(true);
    expect(canRefund(req({ status: 'refund_due' }))).toBe(true);
    expect(canRefund(req({ status: 'claimed' }))).toBe(false);
    expect(needsRefundAck(req({ status: 'refund_claimed', refund: { trxId: 'R', amount: 1, at: 1 } }))).toBe(true);
    expect(needsRefundAck(req({ status: 'refunded', refund: { trxId: 'R', amount: 1, at: 1, ackAt: 2 } }))).toBe(false);
  });
  it('অবস্থার ধারা: খোলা → অপেক্ষা; ফেরত পাঠিয়েছে → রোগীর "পেয়েছি" বাকি', () => {
    expect(timelineOf(req()).map((s) => [s.k, s.done])).toEqual([['created', true], ['claimed', false]]);
    const t = timelineOf(req({ status: 'refund_claimed', claim: { method: 'bkash', trxId: 'X', at: 2 }, confirmedAt: 3, refund: { trxId: 'R', amount: 1, at: 4 } }));
    expect(t.map((s) => s.k)).toEqual(['created', 'claimed', 'confirmed', 'refunded', 'refund_ack']);
    expect(t.at(-1)!.done).toBe(false);
  });
  it('লেখা: "জানিয়েছেন" কখনো সবুজ নয়; চেম্বারের খোলা অগ্রিম "অগ্রিম বাকি"; সার্ভারের কোড বাংলায়', () => {
    expect(statusTone('claimed')).not.toBe('good');
    expect(statusTone('confirmed')).toBe('good');
    expect(statusText('refund_due')).toBe('ফেরত পাওনা');
    expect(statusShort('open', 'booking_advance')).toBe('অগ্রিম বাকি');
    expect(statusShort('open', 'delivery')).toBe('পাঠানো হয়নি');
    expect(payErrText('pay_ref_used', 'bkash', 'x')).toContain('আবার টাকা পাঠাবেন না');
    expect(payErrText('pay_ref_rejected', 'bkash', 'x')).toContain('দোকান বাতিল করেছে');
    expect(payErrText('nope', 'bkash', 'fallback')).toBe('fallback');
  });
});

describe('ফিচার সুইচ', () => {
  it('প্রতিটা প্রবেশপথ: pay_manual + নিজের চাবি + যা লাগে', () => {
    expect(payOn('pay_delivery')).toBe(true);
    setFeaturesForTest({ pay_manual: true, pay_delivery: true });
    expect(payOn('pay_delivery')).toBe(false); // inv_pharmacy বন্ধ
    setFeaturesForTest({ pay_delivery: true, inv_pharmacy: true });
    expect(payOn('pay_delivery')).toBe(false); // pay_manual বন্ধ
    expect(patientPayOn()).toBe(false);
    expect(bizKindOn('pharmacy')).toBe(false);
  });
  it('অনলাইন চেম্বারের ফি শুধু online_chamber-এ (pay_manual ছাড়াও); সব বন্ধ হলে কিছুই নয়', () => {
    setFeaturesForTest({ online_chamber: true });
    expect(onlineFeeOnPay()).toBe(true);
    expect(bizKindOn('chamber')).toBe(true);
    expect(bizKindOn('lab')).toBe(false);
    expect(patientPayOn()).toBe(true);
    setFeaturesForTest({});
    expect(onlineFeeOnPay() || patientPayOn() || payOn('pay_due') || bizKindOn('chamber')).toBe(false);
  });
});

describe('নোটিফিকেশন আর SSE', () => {
  const work = { ambulances: [], pharmacy: null, bloodBanks: [], lab: null, professional: null, assisting: [] };
  it('pay_* → পেমেন্টের পাতা (requestId থাকলেও রক্তের অনুরোধে নয়)', () => {
    expect(inboxTarget({ type: 'pay_confirmed', requestId: 'prq1' }, null, work)).toBe('/invoices/payments/prq1');
    expect(inboxTarget({ type: 'pay_claimed', kind: 'pharmacy', bizId: 'u-work' }, null, work)).toBe('/work/payments?biz=pharmacy%3Au-work');
    expect(payTarget({ type: 'pay_methods_changed', kind: 'chamber', bizId: 'd1' })).toBe('/work/payments/settings?biz=chamber%3Ad1');
    expect(payTarget({ type: 'pay_requested' })).toBe('/invoices/payments');
  });
  it('SSE pay_* এলে পেমেন্ট আর লাগানো তালিকা আবার আনা; অন্য খবর নয়', async () => {
    vi.useFakeTimers();
    const spy = vi.spyOn(queryClient, 'invalidateQueries');
    expect(handlePayEvent('chat_message', {})).toBe(false);
    expect(handlePayEvent('pay_confirmed', { id: 'x' })).toBe(true);
    expect(handlePayEvent('pay_refund_claimed', {})).toBe(true);
    vi.advanceTimersByTime(200);
    expect(spy).toHaveBeenCalledTimes(1);
    const pred = (spy.mock.calls[0][0] as any).predicate;
    expect(pred({ queryKey: ['my_payments'] })).toBe(true);
    expect(pred({ queryKey: ['my_appointments'] })).toBe(true);
    expect(pred({ queryKey: ['questions'] })).toBe(false);
    vi.useRealTimers();
  });
});

describe('PaySheet', () => {
  it('সবসময় সতর্কতা; নম্বর শুধু দেখানো (লেখার ঘর নেই); মাধ্যম বদলালে ধরন অনুযায়ী ধাপ; ফেরতের নিয়ম আগে', async () => {
    const w = mount(PaySheet, { props: { req: req() }, global: { stubs } });
    expect(w.get('[data-testid="pay-warn"]').text()).toContain('শুধু এখানে দেখানো নম্বরে টাকা পাঠান। কেউ চ্যাটে বা ফোনে অন্য নম্বর দিলে পাঠাবেন না, রিপোর্ট করুন।');
    expect(w.get('[data-testid="pay-number"]').element.tagName).toBe('SPAN');
    expect(w.findAll('input').map((i) => i.attributes('data-testid'))).toEqual(['trx-input', 'last4-input']);
    expect(w.get('[data-testid="pay-steps"]').text()).toContain('Send Money');
    expect(w.get('[data-testid="pay-refund-policy"]').text()).toContain('যে নম্বর থেকে দিয়েছেন সেখানেই টাকা ফেরত');
    // নগদ (মার্চেন্ট) → Payment আর রেফারেন্স
    await w.findAll('[data-testid="pay-methods"] button')[1].trigger('click');
    expect(w.get('[data-testid="pay-number"]').text()).toBe('০১৮১১২২৩৩৪৪');
    expect(w.get('[data-testid="pay-steps"]').text()).toContain('Payment');
    expect(w.get('[data-testid="pay-ref-code"]').text()).toBe('123XYZ');
  });
  it('দাবি: ভুল TrxID/শেষ ৪ হলে সার্ভারে নয়; ঠিক হলে pay_claim {id, method, trxId, senderLast4}', async () => {
    call.mockResolvedValue({ ok: true, request: { ...REQ, status: 'claimed' } });
    const w = mount(PaySheet, { props: { req: req() }, global: { stubs } });
    await w.get('input[data-testid="trx-input"]').setValue('AB12');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    expect(call).not.toHaveBeenCalled();
    expect(w.text()).toContain('ঠিক ১০ অক্ষরের');
    await w.get('input[data-testid="trx-input"]').setValue('9gh7k2lm1p');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    expect(w.text()).toContain('শেষ ৪ অঙ্ক লিখুন');
    await w.get('input[data-testid="last4-input"]').setValue('৫৬৭৮');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('pay_claim', { id: 'prq_abc123XYZ', method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678' });
    expect(w.emitted('changed')![0][0]).toMatchObject({ status: 'claimed' });
  });
  it('409 pay_ref_used: আবার পাঠাবেন না + রিপোর্টের বোতাম', async () => {
    call.mockRejectedValue(new (ServerError as any)('used', 409, 'pay_ref_used'));
    const w = mount(PaySheet, { props: { req: req() }, global: { stubs } });
    await w.get('input[data-testid="trx-input"]').setValue('9GH7K2LM1P');
    await w.get('input[data-testid="last4-input"]').setValue('5678');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('আবার টাকা পাঠাবেন না');
    expect(w.find('[data-testid="pay-ref-report"]').exists()).toBe(true);
  });
  it('দোকান "পাইনি": আলাদা বার্তা, যা জানিয়েছিলেন সব, আপত্তি (pay_report not_confirmed), আবার জানানো যায়', async () => {
    const w = mount(PaySheet, {
      props: { req: req({ status: 'rejected', rejectReason: 'not_received', rejectNote: 'আসেনি', rejectedAt: Date.now(), claim: { method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678', at: Date.now() - H } }) },
      global: { stubs },
    });
    const box = w.get('[data-testid="pay-rejected"]');
    expect(box.text()).toContain('দোকান বলছে টাকা আসেনি');
    expect(box.text()).toContain('9GH7K2LM1P');
    expect(box.text()).toContain('টাকা আসেনি — আসেনি');
    expect(w.find('[data-testid="pay-claim"]').exists()).toBe(true);
    await w.get('[data-testid="pay-dispute"]').trigger('click');
    await w.get('[data-testid="report-send"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('pay_report', { id: 'prq_abc123XYZ', reason: 'not_confirmed' });
  });
  it('ফেরত পাঠিয়েছে বলছে: "পেয়েছি" (pay_refund_ack) / "পাইনি" (রিপোর্ট no_refund); ডেমো ব্যানার', async () => {
    const w = mount(PaySheet, { props: { req: req({ demo: true, status: 'refund_claimed', confirmedAt: 1, refund: { trxId: 'RF123456', amount: 450, at: 2 } }) }, global: { stubs } });
    expect(w.get('[data-testid="pay-demo"]').text()).toBe('ডেমো — আসল টাকা পাঠাবেন না');
    expect(w.get('[data-testid="pay-refund"]').text()).toContain('ব্যবসা বলছে');
    await w.get('[data-testid="refund-not-got"]').trigger('click');
    expect(w.get('[data-testid="report-reasons"]').find('[aria-pressed="true"]').text()).toContain('ফেরত পাইনি');
    await w.get('[data-testid="refund-ack"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('pay_refund_ack', { id: 'prq_abc123XYZ' });
  });
  it('চেম্বারের অগ্রিম: ডাক্তার বাতিল করলে পুরো ফেরত, কবে পর্যন্ত; কোনো নম্বর নেই → নগদে দিন', () => {
    const adv = mount(PaySheet, { props: { req: req({ purpose: 'booking_advance', refundableUntil: Date.now() + 5 * H }) }, global: { stubs } });
    expect(adv.get('[data-testid="pay-refund-policy"]').text()).toContain('ডাক্তার বাতিল করলে পুরো টাকা ফেরত');
    const none = mount(PaySheet, { props: { req: req({ methods: [] }) }, global: { stubs } });
    expect(none.get('[data-testid="pay-no-methods"]').text()).toContain('নগদে দিন');
    expect(none.find('[data-testid="pay-claim"]').exists()).toBe(false);
  });
});

describe('ব্যবসা: "পেয়েছি"-তে তিন টিক', () => {
  it('তিনটা না দিলে বোতাম বন্ধ; দিলে checks সাথে', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const texts = { confirmTitle: 'টাকা পেয়েছেন?', confirmText: '', confirmLabel: 'পেয়েছি', rejectTitle: '', rejectText: '', rejectLabel: '', reasonLabel: '', reasonPh: '' };
    const w = mount(PayDecide, {
      props: { mode: 'confirm', texts, claim: { method: 'nagad', trxId: 'AB12CD34', senderLast4: '5678', at: Date.now(), byName: '' }, amountText: '৳৪৫০', onConfirm, onReject: vi.fn() },
      global: { stubs },
    });
    expect(w.get('[data-testid="confirm-claim"]').text()).toContain('AB12CD34');
    expect(w.get('[data-testid="confirm-go"]').attributes('disabled')).toBeDefined();
    await w.get('[data-testid="check-trx"] input').setValue(true);
    await w.get('[data-testid="check-amount"] input').setValue(true);
    expect(w.get('[data-testid="confirm-go"]').attributes('disabled')).toBeDefined();
    await w.get('[data-testid="check-last4"] input').setValue(true);
    await w.get('[data-testid="confirm-go"]').trigger('click');
    await flushPromises();
    expect(onConfirm).toHaveBeenCalledWith({ trxId: true, amount: true, last4: true });
  });
  it('"পাইনি": "অন্য কারণ" হলে নোট লাগে', async () => {
    const onReject = vi.fn().mockResolvedValue(undefined);
    const texts = { confirmTitle: '', confirmText: '', confirmLabel: '', rejectTitle: 'টাকা পাননি?', rejectText: '', rejectLabel: 'পাইনি', reasonLabel: '', reasonPh: '' };
    const w = mount(PayDecide, { props: { mode: 'reject', texts, onConfirm: vi.fn(), onReject }, global: { stubs } });
    await w.findAll('[data-testid="reject-code"] button')[3].trigger('click');
    await w.get('[data-testid="reject-confirm"]').trigger('click');
    expect(onReject).not.toHaveBeenCalled();
    await w.get('input[data-testid="reject-note"]').setValue('অর্ধেক এসেছে');
    await w.get('[data-testid="reject-confirm"]').trigger('click');
    await flushPromises();
    expect(onReject).toHaveBeenCalledWith('other', 'অর্ধেক এসেছে');
  });
});

describe('চ্যাট: টাকার নম্বরসহ মেসেজ', () => {
  const m = parseMessage({ id: 'm1', from: 'p', at: 1, text: 'এই নম্বরে বিকাশ করুন 01711223344', warn: 'money_number' });
  const base = { selected: false, now: 2, canReport: true };
  it('প্রাপকের কাছে সতর্কতা; রক্তের কথোপকথনে আরও কড়া; নিজের মেসেজে নয়', () => {
    expect(m.warn).toBe('money_number');
    const w = mount(MessageBubble, { props: { m, mine: false, ...base } });
    expect(w.get('[data-testid="msg-warn"]').text()).toContain('অ্যাপের পেমেন্ট পাতা ছাড়া টাকা পাঠাবেন না');
    const b = mount(MessageBubble, { props: { m, mine: false, blood: true, ...base } });
    expect(b.get('[data-testid="msg-warn"]').text()).toContain('রক্তের জন্য কোনো টাকা দিতে হয় না');
    expect(mount(MessageBubble, { props: { m, mine: true, ...base } }).find('[data-testid="msg-warn"]').exists()).toBe(false);
    expect(mount(MessageBubble, { props: { m: parseMessage({ id: 'm2', from: 'p', at: 1, text: 'হ্যালো' }), mine: false, ...base } }).find('[data-testid="msg-warn"]').exists()).toBe(false);
  });
});
