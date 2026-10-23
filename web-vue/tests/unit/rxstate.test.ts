// প্রেসক্রিপশনের অবস্থা (rxstate.ts, backend/docs/RX-STATE.md): চাবি → লেখা, চিপের এক লাইন, ছোট লাইন, ট্যাব, টাইমলাইন
import { afterEach, describe, expect, it } from 'vitest';
import { setLang } from '@/lib/i18n';
import type { Rx } from '@/sections/health/rx';
import { canMarkDone, canReopen, inTab, labText, phaseOf, primaryText, secondaryLines, stepDate, stepText, tabLabel, type RxState } from '@/sections/health/rxstate';

const D = 24 * 3600 * 1000;
const FU = Date.UTC(2026, 10, 5); // ৫ নভেম্বর ২০২৬ (UTC মধ্যরাত)

const st = (o: Partial<RxState> = {}): RxState => ({
  phase: 'active', primary: 'not_bought', tone: 'neutral', cancelled: false, cancelledAt: null,
  medicine: { state: 'not_bought', bought: 0, total: 5 }, lab: null, followUpDate: null, lastActivityAt: 0, ...o,
});
const rx = (state: RxState | null, status = 'active'): Rx => ({ id: 'r', status, createdAt: 1, state } as unknown as Rx);
const med = (state: string, bought = 0, total = 5) => st({ primary: state, medicine: { state, bought, total } });

afterEach(() => setLang('bn'));

describe('কার্ডের এক লাইন (চিপ)', () => {
  it('ওষুধের প্রতিটা অবস্থা, বাংলায়', () => {
    setLang('bn');
    const cases: [string, string][] = [
      ['not_bought', 'ওষুধ কেনা হয়নি'],
      ['sent', 'ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা'],
      ['accepted', 'ফার্মেসি গ্রহণ করেছে'],
      ['ready', 'ওষুধ তৈরি — দোকান থেকে নিন'],
      ['out', 'ডেলিভারির পথে'],
      ['all_bought', 'সব ওষুধ কেনা হয়েছে'],
      ['running_out', 'ওষুধ শেষ হয়ে আসছে'],
      ['declined', 'ফার্মেসি দিতে পারেনি — অন্য ফার্মেসিতে পাঠান'],
    ];
    for (const [k, text] of cases) expect(primaryText(rx(med(k)))?.text, k).toBe(text);
    // আংশিক: বাংলা অঙ্কে কয়টার মধ্যে কয়টা
    expect(primaryText(rx(med('partial', 3, 5)))?.text).toBe('আংশিক কেনা (৩/৫)');
  });
  it('ইংরেজিতে', () => {
    setLang('en');
    expect(primaryText(rx(med('partial', 3, 5)))?.text).toBe('Partly Bought (3/5)');
    expect(primaryText(rx(med('out')))?.text).toBe('On the Way');
  });
  it('রং সার্ভারের tone; বাতিল ধূসর', () => {
    setLang('bn');
    expect(primaryText(rx(st({ primary: 'running_out', tone: 'error', medicine: { state: 'running_out', bought: 2, total: 2 } })))).toEqual({ text: 'ওষুধ শেষ হয়ে আসছে', tone: 'error' });
    expect(primaryText(rx(st({ primary: 'cancelled', tone: 'muted', cancelled: true, phase: 'past' }), 'cancelled'))).toEqual({ text: 'বাতিল করেছেন ডাক্তার', tone: 'muted' });
  });
  it('ওষুধ না থাকলে পরীক্ষা, তারপর ফলো-আপ, তারপর "লেখা হয়েছে"', () => {
    setLang('bn');
    expect(primaryText(rx(st({ primary: 'lab_received', tone: 'primary', medicine: null, lab: { state: 'received', orders: 1, reported: 0, reviewed: false } })))?.text).toBe('নমুনা নেওয়া হয়েছে');
    expect(primaryText(rx(st({ primary: 'follow_up', tone: 'info', medicine: null, followUpDate: FU })))?.text).toBe('ফলো-আপ: ৫ নভেম্বর ২০২৬');
    expect(primaryText(rx(st({ primary: 'written', medicine: null })))?.text).toBe('লেখা হয়েছে');
  });
  it('পুরোনো সার্ভার (state নেই): বাতিল হলে শুধু বাতিল, নইলে কিছু না', () => {
    setLang('bn');
    expect(primaryText(rx(null))).toBeNull();
    expect(primaryText(rx(null, 'cancelled'))?.text).toBe('বাতিল করেছেন ডাক্তার');
    expect(secondaryLines(rx(null))).toEqual([]);
  });
});

describe('ছোট লাইন: পরীক্ষা আর ফলো-আপ', () => {
  it('ওষুধের নিচে পরীক্ষার লাইন আর ফলো-আপ', () => {
    setLang('bn');
    const s = st({ lab: { state: 'pending', orders: 0, reported: 0, reviewed: false }, followUpDate: FU });
    expect(secondaryLines(rx(s))).toEqual([['list_alt', 'পরীক্ষা বাকি'], ['calendar_month', 'ফলো-আপ: ৫ নভেম্বর ২০২৬']]);
  });
  it('পরীক্ষার প্রতিটা অবস্থা; কয়টা রিপোর্ট এল; ডাক্তার দেখেছেন', () => {
    setLang('bn');
    expect(labText({ state: 'sent', orders: 1, reported: 0, reviewed: false })).toBe('ল্যাবে পাঠানো');
    expect(labText({ state: 'sent', orders: 3, reported: 1, reviewed: false })).toBe('ল্যাবে পাঠানো · ১/৩টা রিপোর্ট এসেছে');
    expect(labText({ state: 'received', orders: 1, reported: 0, reviewed: false })).toBe('নমুনা নেওয়া হয়েছে');
    expect(labText({ state: 'reported', orders: 1, reported: 1, reviewed: true })).toBe('রিপোর্ট এসেছে · ডাক্তার দেখেছেন');
  });
  it('বাতিল হলে ছোট লাইন নেই', () => {
    expect(secondaryLines(rx(st({ cancelled: true, primary: 'cancelled', followUpDate: FU })))).toEqual([]);
  });
});

describe('ট্যাব: চলমান / পূর্বের / সব', () => {
  it('সার্ভারের phase; পুরোনো সার্ভারে বাতিল = পূর্বের', () => {
    expect(phaseOf(rx(st({ phase: 'past' })))).toBe('past');
    expect(phaseOf(rx(null, 'cancelled'))).toBe('past');
    expect(phaseOf(rx(null))).toBe('active');
    expect(inTab(rx(st({ phase: 'past' })), 'all')).toBe(true);
    expect(inTab(rx(st({ phase: 'past' })), 'active')).toBe(false);
  });
  it('লেবেলে গুনতি — বাংলায় বাংলা অঙ্ক', () => {
    setLang('bn');
    expect(tabLabel('active', 3)).toBe('চলমান (৩)');
    expect(tabLabel('past', 12)).toBe('পূর্বের (১২)');
    expect(tabLabel('all', 0)).toBe('সব (০)');
    setLang('en');
    expect(tabLabel('active', 3)).toBe('Ongoing (3)');
    expect(tabLabel('past', 1)).toBe('Previous (1)');
  });
});

describe('টাইমলাইন', () => {
  it('ধাপের লেখা আর তারিখ', () => {
    setLang('bn');
    expect(stepText({ kind: 'pharmacy_sent', at: 0, name: 'লাইফ ফার্মেসি', mode: 'shop' })).toBe('ফার্মেসিতে পাঠানো — লাইফ ফার্মেসি');
    expect(stepText({ kind: 'pharmacy_sent', at: 0, mode: 'online' })).toBe('অনলাইনে অর্ডার পাঠানো');
    expect(stepText({ kind: 'bought', at: 0, count: 2 })).toBe('২টা ওষুধ কেনা হয়েছে');
    expect(stepText({ kind: 'cancelled', at: 0 })).toBe('বাতিল করেছেন ডাক্তার');
    expect(stepText({ kind: 'follow_up', at: FU, ahead: true })).toBe('ফলো-আপে দেখানোর দিন');
    expect(stepDate({ kind: 'follow_up', at: FU })).toBe('৫ নভেম্বর ২০২৬');
    // আসল সময়: ঢাকার দিন (রাত ১১টা UTC = পরদিন ঢাকায়)
    expect(stepDate({ kind: 'written', at: FU - D + 23 * 3600 * 1000 })).toBe('৫ নভেম্বর ২০২৬');
  });
});

describe('কেন পূর্বের: নিজে শেষ করা, নতুন প্রেসক্রিপশন; বোতাম', () => {
  it('ছোট লাইন', () => {
    setLang('bn');
    expect(secondaryLines(rx(st({ phase: 'past', pastReason: 'done', doneAt: FU })))[0]).toEqual(['check_circle', 'আপনি শেষ করেছেন']);
    const sup = secondaryLines(rx(st({ phase: 'past', pastReason: 'superseded', supersededBy: { id: 'rx2', at: FU } })));
    expect(sup[0]).toEqual(['history', 'নতুন প্রেসক্রিপশনে বদলেছে · ৫ নভেম্বর ২০২৬']);
    // শুধু কারণটা ঠিক হলে (supersededBy থাকলেও চলমান হলে নয়)
    expect(secondaryLines(rx(st({ pastReason: null, supersededBy: { id: 'rx2', at: FU } })))).toEqual([]);
    setLang('en');
    expect(secondaryLines(rx(st({ phase: 'past', pastReason: 'done' })))[0][1]).toBe('You Marked It Finished');
  });
  it('"শেষ হয়েছে" চলমানে, "আবার চলমান করুন" পূর্বেরতে; বাতিলে কোনোটাই নয়; পুরোনো সার্ভারে নয়', () => {
    expect(canMarkDone(rx(st({})))).toBe(true);
    expect(canReopen(rx(st({})))).toBe(false);
    expect(canReopen(rx(st({ phase: 'past', pastReason: 'course_end' })))).toBe(true);
    expect(canMarkDone(rx(st({ phase: 'past', pastReason: 'cancelled', cancelled: true }), 'cancelled'))).toBe(false);
    expect(canReopen(rx(st({ phase: 'past', pastReason: 'cancelled', cancelled: true }), 'cancelled'))).toBe(false);
    expect(canMarkDone(rx(null))).toBe(false);
  });
  it('টাইমলাইনের নতুন ধাপ', () => {
    setLang('bn');
    expect(stepText({ kind: 'superseded', at: 0 })).toBe('নতুন প্রেসক্রিপশনে বদলেছে');
    expect(stepText({ kind: 'marked_done', at: 0 })).toBe('আপনি শেষ করেছেন');
    expect(stepText({ kind: 'reopened', at: 0 })).toBe('আবার চলমান করেছেন');
  });
});
