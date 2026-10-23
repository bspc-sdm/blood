// প্রেসক্রিপশন বাতিলের কারণ (rxcancel.ts, RxCancelDialog.vue — backend/docs/RX-STATE.md "Cancellation reason"):
// চাবি → লেখা, কার্ডের চিপ "বাতিল করেছেন ডাক্তার — <কারণ>", টাইমলাইন, নোটের নিয়ম, ডাক্তারের ডায়ালগ (কারণ → নিশ্চিত → rx_cancel)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const { call } = vi.hoisted(() => ({ call: vi.fn() }));
vi.mock('@/lib/api', () => ({ call: (...a: unknown[]) => call(...a), errorText: (e: unknown) => String((e as Error)?.message ?? e) }));

import { setLang } from '@/lib/i18n';
import type { Rx } from '@/sections/health/rx';
import { primaryText, stepText, type RxState } from '@/sections/health/rxstate';
import { CANCEL_REASONS, cancelReasonText, cancelledLine, noteOk } from '@/sections/health/rxcancel';
import RxCancelDialog from '@/sections/rx/doctor/RxCancelDialog.vue';

const st = (o: Partial<RxState> = {}): RxState => ({
  phase: 'past', primary: 'cancelled', tone: 'muted', cancelled: true, cancelledAt: 5,
  medicine: null, lab: null, followUpDate: null, lastActivityAt: 0, ...o,
});
const rx = (state: RxState | null): Rx => ({ id: 'r', status: 'cancelled', createdAt: 1, state } as unknown as Rx);

afterEach(() => setLang('bn'));

describe('কারণের লেখা', () => {
  it('পাঁচটা কারণ, এই ক্রমে, বাংলায়', () => {
    setLang('bn');
    expect(CANCEL_REASONS.map((k) => cancelReasonText(k))).toEqual([
      'ভুল ওষুধ/মাত্রা', 'ভুল রোগী', 'নতুন প্রেসক্রিপশন দিয়েছি', 'রোগী জানিয়েছেন সমস্যা', 'অন্য কারণ',
    ]);
    expect(cancelReasonText(null)).toBeNull();
    expect(cancelReasonText('something_new')).toBeNull();
  });
  it('কার্ডের চিপ: "বাতিল করেছেন ডাক্তার — <কারণ>"; কারণ না থাকলে (আগের release) শুধু প্রথম অংশ', () => {
    setLang('bn');
    expect(primaryText(rx(st({ cancelReason: 'wrong_patient' })))?.text).toBe('বাতিল করেছেন ডাক্তার — ভুল রোগী');
    expect(primaryText(rx(st({ cancelReason: 'replaced', cancelNote: 'নতুনটা দেখুন' })))?.text).toBe('বাতিল করেছেন ডাক্তার — নতুন প্রেসক্রিপশন দিয়েছি');
    expect(primaryText(rx(st({ cancelReason: null })))).toEqual({ text: 'বাতিল করেছেন ডাক্তার', tone: 'muted' });
    // পুরোনো সার্ভার (state নেই) — আগের মতো
    expect(primaryText(rx(null))?.text).toBe('বাতিল করেছেন ডাক্তার');
  });
  it('টাইমলাইনের ধাপ, ইংরেজিতেও', () => {
    setLang('bn');
    expect(stepText({ kind: 'cancelled', at: 1, reason: 'patient_reported', note: 'মাথা ঘোরে' })).toBe('বাতিল করেছেন ডাক্তার — রোগী জানিয়েছেন সমস্যা');
    expect(stepText({ kind: 'cancelled', at: 1 })).toBe('বাতিল করেছেন ডাক্তার');
    setLang('en');
    expect(cancelledLine('wrong_medicine').toLowerCase()).toBe('cancelled by the doctor — wrong medicine/dose');
  });
  it('নোট: "অন্য কারণ"-এ ৩–২০০ অক্ষর, বাকিগুলোয় ঐচ্ছিক', () => {
    expect(noteOk(null, 'abc')).toBe(false);
    expect(noteOk('other', '')).toBe(false);
    expect(noteOk('other', '  ab  ')).toBe(false);
    expect(noteOk('other', 'abc')).toBe(true);
    expect(noteOk('other', 'ক'.repeat(200))).toBe(true);
    expect(noteOk('other', 'ক'.repeat(201))).toBe(false);
    expect(noteOk('wrong_patient', '')).toBe(true);
    expect(noteOk('replaced', 'ক'.repeat(201))).toBe(false);
  });
});

describe('ডাক্তারের বাতিলের ডায়ালগ', () => {
  beforeEach(() => {
    call.mockReset();
    document.body.innerHTML = '';
    setLang('bn');
  });
  const q = (s: string) => document.body.querySelector<HTMLElement>(s);
  const btn = (testid: string) => q(`[data-testid="${testid}"]`) as HTMLButtonElement;

  it('কারণ ছাড়া এগোনো যায় না; "অন্য কারণ"-এ নোট লাগে; নিশ্চিত করলে rx_cancel {id, reason, note}', async () => {
    call.mockResolvedValue({ ok: true });
    const w = mount(RxCancelDialog, { props: { rxId: 'rx1' }, attachTo: document.body });
    await flushPromises();
    expect(document.body.textContent).toContain('কেন বাতিল করছেন?');
    expect(document.body.querySelectorAll('input[type=radio]').length).toBe(5);
    expect(btn('rx-cancel-next').disabled).toBe(true);
    (q('input[data-reason="other"]') as HTMLInputElement).click();
    await flushPromises();
    expect(btn('rx-cancel-next').disabled).toBe(true);
    const ta = q('textarea') as HTMLTextAreaElement;
    ta.value = 'ab';
    ta.dispatchEvent(new Event('input'));
    await flushPromises();
    expect(btn('rx-cancel-next').disabled).toBe(true);
    expect(document.body.textContent).toContain('অন্তত ৩ অক্ষর');
    ta.value = '  রোগী অন্য ডাক্তার দেখাচ্ছেন  ';
    ta.dispatchEvent(new Event('input'));
    await flushPromises();
    btn('rx-cancel-next').click();
    await flushPromises();
    // নিশ্চিতের ধাপ: কারণ আর নোট দেখায়, এখনো কিছু পাঠানো হয়নি
    expect(q('[data-testid="rx-cancel-summary"]')?.textContent).toContain('অন্য কারণ');
    expect(q('[data-testid="rx-cancel-summary"]')?.textContent).toContain('রোগী অন্য ডাক্তার দেখাচ্ছেন');
    expect(call).not.toHaveBeenCalled();
    btn('rx-cancel-confirm').click();
    await flushPromises();
    expect(call).toHaveBeenCalledWith('rx_cancel', { id: 'rx1', reason: 'other', note: 'রোগী অন্য ডাক্তার দেখাচ্ছেন' });
    expect(w.emitted('done')).toBeTruthy();
    w.unmount();
  });

  it('অন্য কারণে নোট ঐচ্ছিক — খালি নোট পাঠানো হয় না; সার্ভারের ভুল ডায়ালগেই দেখায়', async () => {
    call.mockRejectedValueOnce(new Error('পাওয়া যায়নি'));
    const w = mount(RxCancelDialog, { props: { rxId: 'rx2' }, attachTo: document.body });
    await flushPromises();
    (q('input[data-reason="wrong_patient"]') as HTMLInputElement).click();
    await flushPromises();
    btn('rx-cancel-next').click();
    await flushPromises();
    btn('rx-cancel-confirm').click();
    await flushPromises();
    expect(call).toHaveBeenCalledWith('rx_cancel', { id: 'rx2', reason: 'wrong_patient' });
    expect(document.body.textContent).toContain('পাওয়া যায়নি');
    expect(w.emitted('done')).toBeFalsy();
    w.unmount();
  });
});
