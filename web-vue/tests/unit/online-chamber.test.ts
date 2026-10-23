// অনলাইন (ভিডিও) চেম্বার: bKash নম্বর, টাকার অবস্থা, কলের সময়ের জানালা, সেভের রূপ, রোগীর "টাকা পাঠিয়েছি",
// ডাক্তার/সহকারীর "ফি পেয়েছি"/"পাইনি" (নিশ্চিত ধাপসহ), "ভিডিও কলে যুক্ত হোন"-এর লিংক।
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils';

const call = vi.fn();
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  errorText: (e: unknown) => (e instanceof Error ? e.message : 'x'),
  LoginRequired: class extends Error {},
  postPublic: () => Promise.resolve({}),
  ServerError: class extends Error {
    constructor(m: string, public status: number, public code?: string) {
      super(m);
    }
  },
}));
import { ServerError } from '@/lib/api';

import { apptStartMs, callPath, canJoinCall, canReportNoShow, hasPhone, normBkash, parseOnline, parsePayState, trxInput, trxOk, CALL_EARLY_MS, CALL_LATE_MS } from '@/sections/appointment/online';
import { chamberLabel, parseAppointment, parseChamber } from '@/sections/appointment/model';
import { blankOnline, chamberJson } from '@/sections/appointment/pro/chamber';
import OnlinePayCard from '@/sections/appointment/online/OnlinePayCard.vue';
import OnlinePayPro from '@/sections/appointment/online/OnlinePayPro.vue';
import JoinCall from '@/sections/appointment/online/JoinCall.vue';
import ChamberEditor from '@/sections/appointment/pro/ChamberEditor.vue';
import { setFeaturesForTest } from '@/sections/appointment/online/flags';

const H = 3600_000;
const DAY = 24 * H;
const D0 = Date.UTC(2026, 9, 23); // ঢাকার ২৩ অক্টোবর (UTC মধ্যরাত)
/** ঢাকার সময় hh:mm, D0 দিনে */
const dhaka = (h: number, m = 0) => D0 - 6 * H + (h * 60 + m) * 60_000;

const ConfirmStub = defineComponent({
  props: ['title', 'text', 'confirm'],
  emits: ['confirm', 'close'],
  setup(p, { emit }) {
    return () => h('div', { 'data-test': 'confirm' }, [h('p', p.text), h('button', { 'data-test': 'yes', onClick: () => emit('confirm') }, p.confirm)]);
  },
});
const DialogStub = defineComponent({ setup: (_, { slots }) => () => h('div', { 'data-test': 'dialog' }, [slots.default?.(), slots.actions?.()]) });
const stubs = { RouterLink: RouterLinkStub, ConfirmDialog: ConfirmStub, Dialog: DialogStub };

const appt = (o: Record<string, unknown> = {}) =>
  parseAppointment({
    id: 'a9', role: 'patient', otherName: 'ডা. রুবিনা', type: 'doctor', chamber: '', start: '19:30', date: D0, status: 'confirmed', createdAt: 1,
    online: true, payState: 'unpaid', bkashNumber: '01712345678', fee: '৫০০ টাকা', noFee: false, onlineNote: 'নেট চালু রাখবেন', ...o,
  });

beforeEach(() => {
  localStorage.setItem('lf.lang', 'bn');
  setFeaturesForTest({ online_chamber: true, appt_video: true });
  call.mockReset();
  call.mockResolvedValue({ ok: true });
});

describe('নিয়ম', () => {
  it('bKash নম্বর: +88/বাংলা অঙ্ক ঠিক করে, ভুল নম্বর null', () => {
    expect(normBkash('01712345678')).toBe('01712345678');
    expect(normBkash('+8801912345678')).toBe('01912345678');
    expect(normBkash('০১৮১২৩৪৫৬৭৮')).toBe('01812345678');
    expect(normBkash('01212345678')).toBeNull();
    expect(normBkash('0171234567')).toBeNull();
    expect(trxInput(' 9gh7-k2lm 1p ')).toBe('9GH7K2LM1P');
    expect(trxOk('')).toBe(true);
    expect(trxOk('ABC123')).toBe(false);
    expect(trxOk('ABC1234567')).toBe(true);
    expect(trxInput('ABC1234567XYZ')).toBe('ABC1234567');
  });

  it('টাকার অবস্থা: rejected সরাসরি বা unpaid + কারণ', () => {
    expect(parsePayState({ payState: 'claimed' })).toBe('claimed');
    expect(parsePayState({ payState: 'paid' })).toBe('paid');
    expect(parsePayState({ payState: 'rejected' })).toBe('rejected');
    expect(parsePayState({ payState: 'unpaid', payRejected: true })).toBe('rejected');
    expect(parsePayState({ payState: 'unpaid', payRejectReason: 'আসেনি' })).toBe('rejected');
    expect(parsePayState({})).toBe('unpaid');
    // সার্ভারের রূপ (CHAMBERS-ONLINE.md): "পাইনি" = unpaid + payNote; payState null = কিছু দেওয়ার নেই
    expect(parsePayState({ payState: 'unpaid', payNote: 'আসেনি' })).toBe('rejected');
    expect(parsePayState({ payState: 'claimed', payNote: null })).toBe('claimed');
    expect(parseOnline({ online: true, payState: 'unpaid', payNote: 'আসেনি' }).payReason).toBe('আসেনি');
    expect(parseOnline({ online: true, payState: null, noFee: false }).noFee).toBe(true);
    expect(parseOnline({ online: false, payState: null }).noFee).toBe(false);
    expect(parseOnline({ online: true, payRef: 'X1', start: 123 })).toMatchObject({ online: true, payRef: 'X1', startAt: 123 });
  });

  it('কলের জানালা: স্লটের ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত', () => {
    const a = appt();
    const st = apptStartMs(a);
    expect(st).toBe(dhaka(19, 30));
    expect(canJoinCall(a, st - CALL_EARLY_MS - 1)).toBe(false);
    expect(canJoinCall(a, st - CALL_EARLY_MS)).toBe(true);
    expect(canJoinCall(a, st + CALL_LATE_MS)).toBe(true);
    expect(canJoinCall(a, st + CALL_LATE_MS + 1)).toBe(false);
    // নিশ্চিত নয় বা চেম্বারের অ্যাপয়েন্টমেন্ট: কখনো নয়
    expect(canJoinCall({ ...a, status: 'pending' }, st)).toBe(false);
    expect(canJoinCall({ ...a, online: false }, st)).toBe(false);
    // সার্ভার সংখ্যা দিলে সেটাই
    expect(apptStartMs({ date: D0, start: '', startAt: dhaka(8) })).toBe(dhaka(8));
    expect(apptStartMs({ date: D0 + DAY, start: '' })).toBe(dhaka(0) + DAY);
  });

  it('কলের লিংক: রোগী, ডাক্তার ?role=provider, নাম', () => {
    expect(callPath('a 1')).toBe('/appointments/a%201/call');
    expect(callPath('a1', true)).toBe('/appointments/a1/call?role=provider');
    expect(callPath('a1', true, 'রহিম')).toBe('/appointments/a1/call?role=provider&name=%E0%A6%B0%E0%A6%B9%E0%A6%BF%E0%A6%AE');
  });

  it('চেম্বার: mode পড়া, অনলাইনের নাম, সেভে হাসপাতাল/ঠিকানা নেই আর bKash ঠিক করা', () => {
    expect(parseChamber({ hospitalId: 'h1', hospitalName: 'X' }).mode).toBe('in_person');
    const c = parseChamber({ mode: 'online', bkashNumber: '01712345678', note: 'n', fee: '৫০০', days: [1], start: '20:00', end: '22:00', appointments: true });
    expect(c.mode).toBe('online');
    expect(chamberLabel(c)).toBe('অনলাইন (ভিডিও)');
    const j = chamberJson({ ...blankOnline('+8801712345678'), fee: '৫০০', days: [1], start: '20:00', end: '22:00', hospitalId: 'old', address: 'x' });
    expect(j).toMatchObject({ mode: 'online', bkashNumber: '01712345678', note: '', fee: '৫০০', appointments: true });
    expect(j).not.toHaveProperty('hospitalId');
    expect(j).not.toHaveProperty('address');
    expect(j).not.toHaveProperty('room');
    expect(chamberJson(parseChamber({ hospitalId: 'h1', hospitalName: 'X' }))).toMatchObject({ mode: 'in_person', hospitalId: 'h1' });
  });
});

describe('রোগী: ফি পাঠানো', () => {
  it('পাঠানো হয়নি: নম্বর, কপি, ধাপ; TrxID দিয়ে "টাকা পাঠিয়েছি" → appt_pay_claim', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const w = mount(OnlinePayCard, { props: { a: appt() }, global: { stubs } });
    expect(w.get('[data-testid="pay-state"]').text()).toBe('পাঠানো হয়নি');
    expect(w.get('[data-testid="bkash-shown"]').text()).toBe('০১৭১২৩৪৫৬৭৮');
    expect(w.findAll('.appt-steps li')).toHaveLength(4);
    expect(w.text()).toContain('নেট চালু রাখবেন');
    await w.get('[data-testid="bkash-copy"]').trigger('click');
    expect(writeText).toHaveBeenCalledWith('01712345678');
    await w.get('input[data-testid="trx-input"]').setValue('9gh7 k2lm1p');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('appt_pay_claim', { appointmentId: 'a9', payRef: '9GH7K2LM1P' });
    expect(w.emitted('changed')).toHaveLength(1);
  });

  it('ছোট TrxID: সার্ভারে না পাঠিয়ে বাংলায় বলা', async () => {
    const w = mount(OnlinePayCard, { props: { a: appt() }, global: { stubs } });
    await w.get('input[data-testid="trx-input"]').setValue('AB12');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(call).not.toHaveBeenCalled();
    expect(w.text()).toContain('TrxID ১০ অক্ষরের');
  });

  it('TrxID ছাড়াও পাঠানো যায় (payRef যায় না)', async () => {
    const w = mount(OnlinePayCard, { props: { a: appt() }, global: { stubs } });
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('appt_pay_claim', { appointmentId: 'a9' });
  });

  it('যাচাই হচ্ছে / ফি পরিশোধিত / ডাক্তার পাননি / নিশ্চিতের আগে', () => {
    const claimed = mount(OnlinePayCard, { props: { a: appt({ payState: 'claimed', payRef: 'ABC123' }) }, global: { stubs } });
    expect(claimed.get('[data-testid="pay-state"]').text()).toBe('যাচাই হচ্ছে');
    expect(claimed.text()).toContain('ABC123');
    expect(claimed.find('[data-testid="pay-claim"]').exists()).toBe(false);

    const paid = mount(OnlinePayCard, { props: { a: appt({ payState: 'paid' }) }, global: { stubs } });
    expect(paid.get('[data-testid="pay-state"]').text()).toBe('ফি পরিশোধিত');
    expect(paid.find('[data-testid="bkash-shown"]').exists()).toBe(false);

    const rej = mount(OnlinePayCard, { props: { a: appt({ payState: 'unpaid', payNote: 'টাকা আসেনি' }) }, global: { stubs } });
    expect(rej.get('[data-testid="pay-state"]').text()).toBe('ডাক্তার পাননি');
    expect(rej.text()).toContain('কারণ: টাকা আসেনি');
    expect(rej.find('[data-testid="pay-claim"]').exists()).toBe(true);

    const pending = mount(OnlinePayCard, { props: { a: appt({ status: 'pending' }) }, global: { stubs } });
    expect(pending.text()).toContain('ডাক্তার নিশ্চিত করলে ফি পাঠাবেন');
    expect(pending.find('[data-testid="pay-claim"]').exists()).toBe(false);

    const free = mount(OnlinePayCard, { props: { a: appt({ noFee: true }) }, global: { stubs } });
    expect(free.find('[data-testid="pay-state"]').exists()).toBe(false);
    expect(free.text()).toContain('ফি নেই');
  });
});

describe('ডাক্তার/সহকারী', () => {
  it('"ফি পেয়েছি": নিশ্চিত ধাপে TrxID, তারপর appt_pay_confirm (সহকারীর doctorUid সাথে)', async () => {
    const w = mount(OnlinePayPro, { props: { a: appt({ payState: 'claimed', payRef: 'TRX42' }), extra: { doctorUid: 'd1' } }, global: { stubs } });
    expect(w.get('[data-testid="pay-state"]').text()).toBe('রোগী ফি পাঠিয়েছেন');
    expect(w.get('[data-testid="pay-ref"]').text()).toContain('TRX42');
    await w.get('[data-testid="pay-got"]').trigger('click');
    expect(call).not.toHaveBeenCalled();
    expect(w.get('[data-test="confirm"]').text()).toContain('TRX42');
    await w.get('[data-test="yes"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('appt_pay_confirm', { appointmentId: 'a9', doctorUid: 'd1' });
    expect(w.emitted('changed')).toHaveLength(1);
  });

  it('"পাইনি": কারণসহ appt_pay_reject; পরিশোধিত হলে বোতাম নেই', async () => {
    const w = mount(OnlinePayPro, { props: { a: appt({ payState: 'claimed' }) }, global: { stubs } });
    await w.get('[data-testid="pay-not"]').trigger('click');
    await w.get('input[data-testid="reject-reason"]').setValue('আসেনি');
    await w.get('[data-testid="reject-confirm"]').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('appt_pay_reject', { appointmentId: 'a9', reason: 'আসেনি' });

    const paid = mount(OnlinePayPro, { props: { a: appt({ payState: 'paid' }) }, global: { stubs } });
    expect(paid.find('[data-testid="pay-got"]').exists()).toBe(false);
    expect(paid.find('[data-testid="pay-not"]').exists()).toBe(false);
    // পাঠানো হয়নি: "ফি পেয়েছি" আছে (ডাক্তার নিজে মিলিয়ে নিতে পারেন), "পাইনি" নেই
    const unpaid = mount(OnlinePayPro, { props: { a: appt() }, global: { stubs } });
    expect(unpaid.find('[data-testid="pay-got"]').exists()).toBe(true);
    expect(unpaid.find('[data-testid="pay-not"]').exists()).toBe(false);
  });
});

describe('ভিডিও কলে যুক্ত হোন', () => {
  it('শুধু জানালার ভেতরে; আগে "১৫ মিনিট আগে" লেখা; ডাক্তারের লিংকে role=provider', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(dhaka(19, 0));
      let w = mount(JoinCall, { props: { a: appt(), hint: true }, global: { stubs } });
      expect(w.find('[data-testid="join-call"]').exists()).toBe(false);
      expect(w.get('[data-testid="join-later"]').text()).toContain('১৫ মিনিট আগে');
      vi.setSystemTime(dhaka(19, 20));
      w = mount(JoinCall, { props: { a: appt(), hint: true, name: 'রহিম' }, global: { stubs } });
      expect(w.getComponent(RouterLinkStub).props('to')).toBe('/appointments/a9/call?name=%E0%A6%B0%E0%A6%B9%E0%A6%BF%E0%A6%AE');
      expect(w.text()).toContain('ভিডিও কলে যুক্ত হোন');
      w = mount(JoinCall, { props: { a: appt(), provider: true }, global: { stubs } });
      expect(w.getComponent(RouterLinkStub).props('to')).toBe('/appointments/a9/call?role=provider');
      vi.setSystemTime(dhaka(22, 31));
      w = mount(JoinCall, { props: { a: appt(), hint: true }, global: { stubs } });
      expect(w.find('[data-testid="join-call"]').exists()).toBe(false);
      expect(w.find('[data-testid="join-later"]').exists()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('নিরাপত্তার নিয়ম (নোটে ফোন নয়, নতুন bKash ২৪ ঘণ্টা পর, TrxID আবার নয়, ডাক্তার না এলে রিপোর্ট)', () => {
  it('নোটে ফোন নম্বর ধরা', () => {
    expect(hasPhone('সময়মতো নেট চালু রাখবেন')).toBe(false);
    expect(hasPhone('ফোন 01712-345 678')).toBe(true);
    expect(hasPhone('০১৭১২৩৪৫৬৭৮-এ কল দিন')).toBe(true);
    expect(hasPhone('রাত ৯টা থেকে ১১টা, ২০২৬')).toBe(false);
    expect(hasPhone('রোল 12345678')).toBe(false);
    expect(hasPhone('অ্যাকাউন্ট 1234 5678 90')).toBe(true);
  });

  it('সম্পাদক: নোটে ফোন হলে ভুল; সেভ করা bKash বদলালে "২৪ ঘণ্টা পর"', () => {
    const c = { ...blankOnline('01712345678'), note: 'কল 01812345678' };
    let w = mount(ChamberEditor, { props: { c, canOnline: true, origBkash: '01712345678' }, global: { stubs } });
    expect(w.text()).toContain('নোটে ফোন বা বিকাশ নম্বর লেখা যাবে না');
    expect(w.text()).not.toContain('নতুন নম্বর ২৪ ঘণ্টা পর চালু হবে');
    w = mount(ChamberEditor, { props: { c: { ...c, note: '', bkashNumber: '01912345678' }, canOnline: true, origBkash: '01712345678' }, global: { stubs } });
    expect(w.text()).toContain('নতুন নম্বর ২৪ ঘণ্টা পর চালু হবে');
    // নতুন চেম্বার (আগে সেভ হয়নি): সাধারণ লেখা
    w = mount(ChamberEditor, { props: { c: { ...c, note: '' }, canOnline: true, origBkash: null }, global: { stubs } });
    expect(w.text()).not.toContain('২৪ ঘণ্টা');
  });

  it('ঘণ্টায় অনেকবার (429 pay_claim_rate): বাংলায় বলা', async () => {
    call.mockRejectedValueOnce(new ServerError('x', 429, 'pay_claim_rate'));
    const w = mount(OnlinePayCard, { props: { a: appt() }, global: { stubs } });
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('এক ঘণ্টা পর আবার চেষ্টা করুন');
  });

  it('একই TrxID আবার (409 pay_ref_used): বাংলায় বলা', async () => {
    call.mockRejectedValueOnce(new ServerError('x', 409, 'pay_ref_used'));
    const w = mount(OnlinePayCard, { props: { a: appt() }, global: { stubs } });
    await w.get('input[data-testid="trx-input"]').setValue('USED120000');
    await w.get('[data-testid="pay-claim"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('এই TrxID আগেই ব্যবহার হয়েছে');
    expect(w.emitted('changed')).toBeUndefined();
  });

  it('ফি দিয়েছেন, ডাক্তার আসেননি: সময়ের ১৫ মিনিট পর "রিপোর্ট করুন" → appt_report', async () => {
    const a = appt({ payState: 'paid' });
    const st = apptStartMs(a);
    expect(canReportNoShow(a, st + 14 * 60_000)).toBe(false);
    expect(canReportNoShow(a, st + 15 * 60_000)).toBe(true);
    expect(canReportNoShow({ ...a, doctorJoined: true }, st + H)).toBe(false);
    expect(canReportNoShow({ ...a, payState: 'claimed' }, st + H)).toBe(false);
    expect(canReportNoShow({ ...a, status: 'completed' }, st + H)).toBe(false);

    vi.useFakeTimers();
    try {
      vi.setSystemTime(st + H);
      const ReportStub = defineComponent({
        props: ['onSubmit'],
        setup: (p) => () => h('button', { 'data-test': 'send-report', onClick: () => p.onSubmit('ডাক্তার আসেননি') }),
      });
      const w = mount(OnlinePayCard, { props: { a }, global: { stubs: { ...stubs, ReasonReportDialog: ReportStub } } });
      await w.get('[data-testid="appt-report"]').trigger('click');
      await w.get('[data-test="send-report"]').trigger('click');
      await flushPromises();
      expect(call).toHaveBeenCalledWith('appt_report', { appointmentId: 'a9', reason: 'ডাক্তার আসেননি' });
      expect(w.find('[data-testid="appt-report"]').exists()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('ফিচার সুইচ', () => {
  it('appt_video বন্ধ: কলের লিংক বা লেখা কিছুই নয়; online_chamber বন্ধ হলেও নয়', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(dhaka(19, 30));
      setFeaturesForTest({ online_chamber: true, appt_video: false });
      let w = mount(JoinCall, { props: { a: appt(), hint: true }, global: { stubs } });
      expect(w.html()).not.toContain('join');
      setFeaturesForTest({ online_chamber: false, appt_video: true });
      w = mount(JoinCall, { props: { a: appt(), hint: true }, global: { stubs } });
      expect(w.html()).not.toContain('join');
      setFeaturesForTest({ online_chamber: true, appt_video: true });
      w = mount(JoinCall, { props: { a: appt(), hint: true }, global: { stubs } });
      expect(w.find('[data-testid="join-call"]').exists()).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('online_chamber বন্ধ: সেটআপে চেম্বারের ধরন বাছাই নেই', () => {
    setFeaturesForTest({});
    const w = mount(ChamberEditor, { props: { c: parseChamber({ hospitalId: 'h1', hospitalName: 'X' }), canOnline: false }, global: { stubs } });
    expect(w.text()).not.toContain('চেম্বারের ধরন');
    expect(w.text()).not.toContain('অনলাইন');
  });
});
