// অ্যাপে কল (src/calls): SSE পড়া, সার্ভারের উত্তর পড়া, "না"-র কারণ, অ্যাপয়েন্টমেন্টের কলের সময়, সময়ের লেখা,
// কল আসা → রিং → ওপাশ রেখে দিলে বন্ধ / ৪৫ সেকেন্ডে মিস, আগে চাপ না পড়লে শব্দ নয়, একই কল দুবার বাজে না।
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { call, ServerError } = vi.hoisted(() => {
  class ServerError extends Error {
    constructor(message: string, public status: number, public code?: string) {
      super(message);
    }
  }
  return { call: vi.fn(), ServerError };
});
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  errorText: (e: unknown) => (e instanceof Error ? e.message : 'x'),
  ServerError,
}));
vi.mock('@/lib/firebase', () => ({ auth: { currentUser: null } }));

import { parseHistory, parseIncoming, refusalOf } from '@/calls/api';
import { handleEvent, sseParser } from '@/calls/events';
import { apptCallOpen } from '@/calls/window';
import { clock, refusalText } from '@/calls/text';
import { startRing, stopRing, userActivated } from '@/calls/ring';
import { callUi, decline, dismiss, onIncoming, onRemoteState, resetCalls, timing } from '@/calls/state';

beforeEach(() => {
  localStorage.setItem('lf.lang', 'bn');
  call.mockReset();
  resetCalls();
});
afterEach(() => {
  vi.useRealTimers();
  resetCalls();
});

describe('SSE পড়া', () => {
  it('ঘটনার নাম আর data, ping বাদ, টুকরো টুকরো এলেও', () => {
    const got: [string, string][] = [];
    const feed = sseParser((n, d) => got.push([n, d]));
    feed(': ping\n\nevent: call_incoming\nda');
    feed('ta: {"callId":"c1"}\n\r\n');
    feed('data: x\ndata: y\n\n');
    expect(got).toEqual([
      ['call_incoming', '{"callId":"c1"}'],
      ['message', 'x\ny'],
    ]);
  });
  it('call_incoming → কল আসছে; call_state cancelled → বন্ধ', () => {
    call.mockResolvedValue({ state: 'ringing' });
    handleEvent('call_incoming', JSON.stringify({ callId: 'c9', video: 'true', ctx: 'blood', refId: 'r1', caller: { uid: 'u2', name: 'সালমা' } }));
    expect(callUi.phase).toBe('incoming');
    expect(callUi.video).toBe(true);
    expect(callUi.peer?.name).toBe('সালমা');
    // jsdom-এ পাতায় চাপ পড়েনি: শব্দ বাজেনি ("শব্দ শুনতে চাপুন" দেখায়)
    expect(callUi.ringAudible).toBe(false);
    onRemoteState('c9', 'cancelled');
    expect(callUi.phase).toBe('idle');
    // একই কল আবার এলে (SSE আবার যুক্ত হলে) আর বাজে না
    handleEvent('call_incoming', JSON.stringify({ callId: 'c9', caller: 'সালমা' }));
    expect(callUi.phase).toBe('idle');
  });
  it('appt_call_ready → ব্যানার', () => {
    handleEvent('appt_call_ready', '{"appointmentId":"a1","callId":"x"}');
    expect(callUi.apptReady).toEqual({ appointmentId: 'a1' });
  });
});

describe('কল আসা', () => {
  it('৪৫ সেকেন্ডে কেউ না ধরলে বন্ধ (মিস)', async () => {
    vi.useFakeTimers();
    call.mockResolvedValue({ state: 'ringing' });
    onIncoming({ callId: 'm1', video: false, ctx: 'ambulance', refId: 'a', caller: { uid: 'd', name: 'চালক' } });
    expect(callUi.phase).toBe('incoming');
    expect(document.title).toContain('অ্যাপে কল আসছে');
    await vi.advanceTimersByTimeAsync(timing.ringMs + 10);
    expect(callUi.phase).toBe('idle');
    expect(document.title).not.toContain('কল আসছে');
  });
  it('ওপাশ রেখে দিলে call_status দেখে বন্ধ', async () => {
    vi.useFakeTimers();
    call.mockResolvedValue({ state: 'ringing' });
    onIncoming({ callId: 'm2', video: false, ctx: null, refId: '', caller: { uid: '', name: 'ক' } });
    call.mockResolvedValue({ state: 'cancelled' });
    await vi.advanceTimersByTimeAsync(timing.pollMs + 10);
    expect(callUi.phase).toBe('idle');
    expect(call).toHaveBeenCalledWith('call_status', { callId: 'm2' });
  });
  it('কেটে দিন → call_decline', async () => {
    call.mockResolvedValue({ ok: true });
    onIncoming({ callId: 'm3', video: false, ctx: null, refId: '', caller: { uid: '', name: 'ক' } });
    await decline();
    expect(callUi.phase).toBe('idle');
    expect(call).toHaveBeenCalledWith('call_decline', { callId: 'm3' });
    dismiss();
  });
});

describe('সার্ভারের উত্তর', () => {
  it('কলের তালিকার দিক, পুরোনো order', () => {
    expect(parseHistory({ callId: 'a', ctx: 'rx_order', dir: 'in', peer: { uid: 'p', name: 'ন' }, state: 'missed', durationSec: 3.4 })).toMatchObject({ ctx: 'rx_order', dir: 'in', state: 'missed', durationSec: 3 });
    expect(parseHistory({ callId: 'b', ctx: 'order', incoming: false }).dir).toBe('out');
    expect(parseHistory({ callId: 'c', ctx: 'zzz' }).dir).toBeNull();
  });
  it('পুশের মতো caller শুধু নাম হলেও', () => {
    expect(parseIncoming({ callId: 'x', caller: 'রহিম', video: 'false' })).toMatchObject({ caller: { name: 'রহিম' }, video: false });
    expect(parseIncoming({})).toBeNull();
  });
  it('"না"-র কারণ আর বাংলা লেখা', () => {
    expect(refusalOf(new ServerError('m', 409, 'call_off'))).toBe('off');
    expect(refusalOf(new ServerError('m', 409, 'call_busy'))).toBe('busy');
    expect(refusalOf(new ServerError('m', 403, 'call_blocked'))).toBe('blocked');
    expect(refusalOf(new ServerError('m', 403, 'call_not_allowed'))).toBe('not_allowed');
    expect(refusalOf(new ServerError('m', 429, 'call_rate'))).toBe('rate');
    expect(refusalOf(new ServerError('m', 503, 'calls_unavailable'))).toBe('unavailable');
    expect(refusalOf(new Error('x'))).toBe('other');
    expect(refusalText('off')).toBe('উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন');
    expect(refusalText('rate', 'Too many')).toBe('অনেকবার কল করেছেন। কয়েক মিনিট পরে আবার চেষ্টা করুন।');
  });
});

describe('অ্যাপয়েন্টমেন্টের কলের সময়', () => {
  const DAY = Date.UTC(2026, 9, 23); // ঢাকার ২৩ অক্টোবর
  const at = (h: number, m: number) => DAY - 6 * 3600_000 + (h * 60 + m) * 60_000; // ঢাকার সময় → epoch
  const a = { date: DAY, start: '17:00', status: 'confirmed' };
  it('৩০ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত, শুধু নিশ্চিত', () => {
    expect(apptCallOpen(a, at(16, 29))).toBe(false);
    expect(apptCallOpen(a, at(16, 30))).toBe(true);
    expect(apptCallOpen(a, at(20, 0))).toBe(true);
    expect(apptCallOpen(a, at(20, 1))).toBe(false);
    expect(apptCallOpen({ ...a, status: 'pending' }, at(17, 0))).toBe(false);
  });
  it('সময় লেখা না থাকলে সেদিন সারাদিন', () => {
    expect(apptCallOpen({ ...a, start: '' }, at(0, 1))).toBe(true);
    expect(apptCallOpen({ ...a, start: '' }, at(23, 59))).toBe(true);
    expect(apptCallOpen({ ...a, start: '' }, at(24, 1))).toBe(false);
  });
});

describe('ছোট জিনিস', () => {
  it('কলের সময় বাংলা অঙ্কে', () => {
    expect(clock(0)).toBe('০০:০০');
    expect(clock(185)).toBe('০৩:০৫');
    expect(clock(3725)).toBe('১:০২:০৫');
  });
  it('পাতায় চাপ না পড়লে রিং বাজে না', () => {
    expect(userActivated()).toBe(false);
    expect(startRing('incoming')).toBe(false);
    stopRing();
  });
});
