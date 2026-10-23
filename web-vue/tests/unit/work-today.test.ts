// "আজকের কাজ" (sections/work/today/model.ts): সার্ভারের উত্তর এক আকারে, সংখ্যা যোগ, শুরু করতে যা বাকি, অবস্থা বদলের কাজ,
// বিরতির শেষ সময়, আর /api/work/today না থাকলে আগের কাজ থেকে বানানো
import { describe, expect, it } from 'vitest';
import { ServerError } from '@/lib/api';
import { setLang } from '@/lib/i18n';
import {
  buildFallback, isMissing, nextDhakaMidnight, normToday, pauseUntil, roleSetup, roleTiles, sortTasks, statusCall, type TodayBiz,
} from '@/sections/work/today/model';

setLang('bn');
const biz = (x: Partial<TodayBiz>): TodayBiz => ({ kind: 'pharmacy', id: 'p', name: 'দোকান', status: 'open', pausedUntil: null, counts: {}, tasks: [], setup: [], listing: null, ...x });

describe('normToday', () => {
  it('splits a doctor into chambers sitting today and an ambulance owner into vehicles; numbers/tasks only on the first row', () => {
    const raw = {
      items: [
        {
          kind: 'doctor', id: 'd1', name: 'ডা. ক', role: 'assistant', listingStatus: 'approved', status: 'open', counts: { today: 3, x: null, y: 'a' },
          tasks: [{ kind: 'doctor', title: 'ক', action: 'next' }, { title: '' }], setup: [{ key: 'photo', done: false }],
          chambers: [
            { chamberIndex: 0, chamberKey: 'k0', name: 'A', nameBn: 'এ', sitsToday: true, status: 'paused', pausedUntil: 9 },
            { chamberIndex: 1, name: 'B', sitsToday: false, status: 'closed' },
            { chamberIndex: 2, name: 'C', sitsToday: true, status: 'closed' },
          ],
        },
        { kind: 'ambulance', id: 'u', name: 'জননী', counts: { calls: 1 }, vehicles: [{ id: 'a1', vehicleNo: 'ঢাকা-১', listingStatus: 'approved', available: true }, { id: 'a2', vehicleNo: 'ঢাকা-২', listingStatus: 'pending', available: false }] },
        { kind: 'spaceship' },
      ],
    };
    const out = normToday(raw, { bn: true });
    expect(out.map((b) => [b.kind, b.id, b.name, b.status, b.listing])).toEqual([
      ['chamber', 'd1:0', 'ডা. ক · এ', 'paused', null],
      ['chamber', 'd1:2', 'ডা. ক · C', 'closed', null],
      ['ambulance', 'a1', 'ঢাকা-১', 'open', null],
      ['ambulance', 'a2', 'ঢাকা-২', null, 'pending'],
    ]);
    expect(out[0]).toMatchObject({ pausedUntil: 9, chamberIndex: 0, chamberKey: 'k0', doctorUid: 'd1', counts: { today: 3 } });
    expect(out[0].tasks.map((t) => [t.kind, t.action])).toEqual([['chamber', 'next']]);
    expect(out[1]).toMatchObject({ counts: {}, tasks: [], setup: [] });
    expect(out[3].counts).toEqual({});
    expect(normToday(null)).toEqual([]);
    expect(normToday([{ kind: 'lab', status: 'weird', listingStatus: 'approved' }])[0]).toMatchObject({ status: null, listing: null });
    // নিজের চেম্বার: doctorUid নয়; আজ কোনো চেম্বার নেই → এক সারি "আজ চেম্বার নেই"
    expect(normToday([{ kind: 'doctor', id: 'me', role: 'owner', chambers: [{ chamberIndex: 0, sitsToday: true }] }])[0].doctorUid).toBeUndefined();
    expect(normToday([{ kind: 'doctor', id: 'me', role: 'owner', chambers: [] }])[0]).toMatchObject({ noToday: true, status: null });
    expect(normToday([{ kind: 'bloodbank', id: 'b', stockUpdatedAt: 5, stockStale: true }])[0]).toMatchObject({ stockUpdatedAt: 5, stockStale: true, status: null });
  });
});

describe('roleTiles / roleSetup / sortTasks', () => {
  it('sums the same key over businesses, keeps the order, skips absent keys and listings under review', () => {
    const t = roleTiles([biz({ counts: { new: 2, salesTodayTk: 100 } }), biz({ id: 'q', counts: { new: 1 } }), biz({ id: 'r', counts: { new: 9 }, listing: 'pending' })], null);
    expect(t.map((x) => [x.key, x.value])).toEqual([['new', 3], ['salesTodayTk', 100]]);
    expect(t[1].money).toBe(true);
  });
  it('assistant chamber tiles open the doctor\'s queue', () => {
    const t = roleTiles([biz({ kind: 'chamber', doctorUid: 'd1', counts: { today: 4 } })], 'me');
    expect(t[0].link).toBe('/work/rx?doctor=d1');
    expect(roleTiles([biz({ kind: 'chamber', doctorUid: 'me', counts: { today: 4 } })], 'me')[0].link).toBe('/work/pro?v=active');
  });
  it('setup is merged, and hidden once everything is done', () => {
    expect(roleSetup([biz({ setup: [{ key: 'photo', done: true }, { key: 'hours', done: false }] }), biz({ setup: [{ key: 'photo', done: false }] })]).map((x) => [x.key, x.done])).toEqual([['photo', false], ['hours', false]]);
    expect(roleSetup([biz({ setup: [{ key: 'photo', done: true }] })])).toEqual([]);
    expect(roleSetup([biz({ kind: 'lab', setup: [{ key: 'approved', done: false }, { key: 'prices', done: false }] })]).map((x) => x.link)).toEqual(['', '/work/lab/orders/prices']);
  });
  it('newest task first, tasks without time last', () => {
    const ts = sortTasks([{ at: 0, id: 'a' }, { at: 5, id: 'b' }, { at: 9, id: 'c' }].map((x) => ({ ...x, kind: 'lab' as const, title: 't', sub: '', action: '', link: '' })));
    expect(ts.map((x) => x.id)).toEqual(['c', 'b', 'a']);
  });
});

describe('status changes', () => {
  it('pause ends after 30/60 min or at the next Dhaka midnight', () => {
    const now = Date.UTC(2026, 9, 24, 10, 0); // ঢাকায় বিকাল ৪টা
    expect(pauseUntil(30, now)).toBe(now + 30 * 60_000);
    expect(pauseUntil(60, now)).toBe(now + 60 * 60_000);
    expect(pauseUntil('day', now)).toBe(Date.UTC(2026, 9, 24, 18, 0));
    expect(nextDhakaMidnight(Date.UTC(2026, 9, 24, 19, 0))).toBe(Date.UTC(2026, 9, 25, 18, 0));
  });
  it('maps to the server actions', () => {
    expect(statusCall(biz({}), 'open')).toEqual(['pharmacy_open_override', { open: true }]);
    expect(statusCall(biz({ kind: 'lab' }), 'closed')).toEqual(['lab_open_override', { open: false }]);
    const [a, b] = statusCall(biz({}), 'paused', 60);
    expect(a).toBe('pharmacy_open_override');
    expect(b).toMatchObject({ open: false });
    expect(typeof b.until).toBe('number');
    expect(statusCall(biz({ kind: 'chamber', chamberIndex: 1, doctorUid: 'd1' }), 'closed')).toEqual(['chamber_day_set', { doctorUid: 'd1', chamberIndex: 1, closed: true }]);
    expect(statusCall(biz({ kind: 'ambulance', id: 'a1' }), 'open')).toEqual(['ambulance_available', { id: 'a1', available: true }]);
  });
  it('a missing action is "unknown request" (or the mock server\'s missing fixture), not other errors', () => {
    expect(isMissing(new ServerError('x', 404))).toBe(true);
    expect(isMissing(new ServerError('অজানা অনুরোধ। অ্যাপ আপডেট করুন।', 400))).toBe(true);
    expect(isMissing(new ServerError('Unknown request. Please update the app.', 400))).toBe(true);
    expect(isMissing(new ServerError('fixture missing: /api/work/today', 400))).toBe(true);
    expect(isMissing(new ServerError('bad', 400))).toBe(false);
    expect(isMissing(new ServerError('down', 500))).toBe(false);
    expect(isMissing(new Error('x'))).toBe(false);
  });
});

describe('buildFallback', () => {
  const work = {
    professional: 'doctor',
    assisting: [],
    pharmacy: { id: 'ph', name: 'নিউ লাইফ', status: 'approved', openNow: false },
    lab: { id: 'lb', name: 'ল্যাব', status: 'pending', docsRequested: true },
    bloodBanks: [{ id: 'bb', name: 'সন্ধানী', status: 'approved', stock: null, stockUpdatedAt: null }],
    ambulances: [{ id: 'a1', vehicleNo: 'ঢাকা-১', status: 'approved', available: true }, { id: 'a2', vehicleNo: 'ঢাকা-২', status: 'pending' }],
  };
  const answers: Record<string, any> = {
    chamber_days: { items: [{ chamberIndex: 0, chamberKey: 'k', name: 'Labib', nameBn: 'লাবিব', sitsToday: true, closedToday: true }, { chamberIndex: 1, sitsToday: false }] },
    queue_today: { chambers: [{ chamberKey: 'k', rows: [{ id: 'r1', serial: 1, name: 'ক', state: 'done' }, { id: 'r2', serial: 2, name: 'খ', state: 'waiting' }, { id: 'r3', serial: 3, name: 'গ', state: 'cancelled' }] }] },
    rx_orders_pharmacy: { items: [{ id: 'o1', code: 'ABC', patientName: 'রহিম', status: 'sent', createdAt: 5 }, { id: 'o2', status: 'ready' }, { id: 'o3', status: 'accepted', patientName: 'করিম', code: 'X' }] },
    books_sales: { items: [{ totals: { net: 100.5 } }, { totals: { net: 20 } }] },
    med_find_requests: { items: [{ answer: null }, { answer: 'yes' }], findOff: false },
    ambreq_driver: { items: [{ id: 'c1', status: 'open', pickup: 'মোড়', createdAt: 9 }, { id: 'c2', status: 'accepted' }] },
  };
  const fake = async (a: string) => {
    if (!(a in answers)) throw new Error('no ' + a);
    return answers[a];
  };
  it('builds the same shape from the old calls', async () => {
    const out = await buildFallback(work as any, { uid: 'me', bn: true, call: fake as any });
    const by = (k: string) => out.filter((b) => b.kind === k);
    expect(by('chamber')).toHaveLength(1);
    expect(by('chamber')[0]).toMatchObject({ name: 'লাবিব', status: 'closed', chamberIndex: 0, counts: { today: 2, waiting: 1 } });
    expect(by('chamber')[0].tasks.map((t) => [t.title, t.action])).toEqual([['খ', 'call']]);
    const ph = by('pharmacy')[0];
    expect(ph).toMatchObject({ status: 'closed', counts: { new: 1, active: 2, salesTodayTk: 120.5, finds: 1 } });
    expect(ph.tasks.map((t) => t.action)).toEqual(['accept', 'ready', 'answer']);
    expect(ph.tasks[0].link).toBe('/work/pharmacy/orders/rx/ABC');
    // যাচাই চলছে: অবস্থা নেই, শুরু করতে যা বাকি
    expect(by('lab')[0]).toMatchObject({ status: null, listing: 'pending', setup: [{ key: 'docs', done: false }, { key: 'approved', done: false }] });
    expect(by('bloodbank')[0].tasks[0].action).toBe('stock');
    expect(by('ambulance').map((a) => [a.status, a.listing, a.counts.calls ?? null])).toEqual([['open', null, 1], [null, 'pending', null]]);
  });
  it('a failing call only drops its numbers, never the business', async () => {
    const out = await buildFallback(work as any, { uid: 'me', bn: true, call: (async () => { throw new Error('down'); }) as any });
    const ph = out.find((b) => b.kind === 'pharmacy')!;
    expect(ph.counts).toEqual({});
    expect(out.find((b) => b.kind === 'chamber')).toMatchObject({ noToday: true, status: null });
  });
});
