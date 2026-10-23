// @vitest-environment node
// অফলাইন ইঞ্জিন: আসল SQLite (node:sqlite) + নকল সার্ভার — নম্বর, স্টক, সিঙ্ক, ভুল, বিরোধ মেটানো, পিনের ভূমিকা, এনক্রিপশন
import { describe, expect, test } from 'vitest';
import { OfflineError, type OfflineOp, type SaleData } from '@/offline/types';
import { receiptModel } from '@/offline/print/receipt';
import { harness, FakeServer } from './harness';

const sale = (over: Partial<SaleData> = {}): OfflineOp => ({
  type: 'sale',
  data: { lines: [{ itemId: 'i1', name: 'Napa 500', qty: 3, price: 2 }], customer: { name: 'Karim', phone: '01711111111' }, ...over },
});

async function code(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    return e instanceof OfflineError ? e.code : `other:${String(e)}`;
  }
  return 'ok';
}

describe('ready', () => {
  test('প্রথমবার: কম্পিউটার নিবন্ধন, গোপন চাবি রাখা, ক্যাশ নেওয়া', async () => {
    const h = await harness();
    const r = await h.engine.ready('pharmacy', 'b1');
    expect(r).toMatchObject({ deviceId: 'dev1', series: 'D1', pending: 0, asOf: 1_000_000 });
    expect([...h.secrets.values()][0]).toContain('s3cr3t');
    const items = await h.engine.items('napa');
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ id: 'i1', price: 2, stock: 100 });
    expect((await h.engine.items('8901'))[0].id).toBe('i1');
    // আবার ডাকলে আবার নিবন্ধন নয়
    await h.engine.ready('pharmacy', 'b1');
    expect(h.server.devices).toBe(1);
  });

  test('ইন্টারনেট ছাড়া প্রথমবার: need_online', async () => {
    const h = await harness();
    h.state.online = false;
    expect(await code(h.engine.ready('pharmacy', 'b1'))).toBe('need_online');
  });

  test('একবার চালু হলে পরে ইন্টারনেট ছাড়াও খোলে', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const h2 = await h.reopen();
    h2.state.online = false;
    h2.server.down = true;
    const r = await h2.engine.ready('pharmacy', 'b1');
    expect(r.series).toBe('D1');
    expect((await h2.engine.items('seclo'))[0].stock).toBe(10);
  });
});

describe('record + sync', () => {
  test('বিক্রি: নম্বর D1-000001, পুরো টাকা ক্যাশে, স্টক কমে, অপেক্ষায় ১টা', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const r = await h.engine.record(sale());
    expect(r.no).toBe('D1-000001');
    expect(r.invoice).toMatchObject({ no: 'D1-000001', kind: 'pharmacy', total: 6, paid: 6, due: 0, offline: true, id: '', bizName: 'Shefa Pharmacy', sync: 'pending' });
    expect(r.invoice!.payments).toEqual([{ at: h.state.now, amount: 6, method: 'cash' }]);
    expect(r.invoice!.verifyCode).toMatch(/^[2-9A-HJ-NP-Z]{10}$/);
    expect(r.invoice!.verifyUrl).toBe(`https://lifolo.com/v/${r.invoice!.verifyCode}`);
    expect(h.engine.pending()).toBe(1);
    expect((await h.engine.items('napa'))[0].stock).toBe(97);
    const r2 = await h.engine.record(sale());
    expect(r2.no).toBe('D1-000002');
  });

  test('বন্ধ করে খুললেও অপেক্ষার কাজ আর নম্বরের ক্রম থাকে', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    h.server.down = true;
    await h.engine.record(sale());
    const h2 = await h.reopen();
    h2.server.down = true;
    await h2.engine.ready('pharmacy', 'b1');
    expect(h2.engine.pending()).toBe(1);
    expect((await h2.engine.items('napa'))[0].stock).toBe(97);
    expect((await h2.engine.record(sale())).no).toBe('D1-000002');
  });

  test('সিঙ্ক: সার্ভার নিলে id বসে, স্টক সার্ভারের, ফোন নম্বর আর রাখা হয় না', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const r = await h.engine.record(sale());
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(0);
    expect(h.server.items.get('i1')!.stock).toBe(97);
    expect((await h.engine.items('napa'))[0].stock).toBe(97);
    const [inv] = await h.engine.localInvoices();
    expect(inv).toMatchObject({ id: 'inv-D1-000001', no: 'D1-000001', sync: 'synced' });
    expect(inv.customer).toEqual({ name: 'Karim' });
    expect(h.server.applied[0].data).toMatchObject({ localId: r.localId, no: 'D1-000001', verifyCode: r.invoice!.verifyCode, issuedAt: h.state.now });
    expect(h.server.applied[0].data).not.toHaveProperty('_invoice');
    expect(h.server.lastSig?.secret).toBe('s3cr3t');
  });

  test('delta না এলে: স্থানীয় প্রভাব স্থায়ী স্টকে মেশে (দুবার গোনা নয়)', async () => {
    const h = await harness();
    h.server.sendDelta = false;
    await h.engine.ready('pharmacy', 'b1');
    await h.engine.record(sale());
    await h.engine.syncNow();
    expect((await h.engine.items('napa'))[0].stock).toBe(97);
  });

  test('উত্তর হারালে আবার পাঠানো একই opId — সার্ভারে একবারই, স্টক ঠিক', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    await h.engine.record(sale());
    h.server.loseNextResponse = true;
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(1);
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(0);
    expect(h.server.applied).toHaveLength(1);
    expect(h.server.items.get('i1')!.stock).toBe(97);
    expect((await h.engine.items('napa'))[0].stock).toBe(97);
  });

  test('নেট নেই: কাজ থাকে, অবস্থা অফলাইন, শেষ ভুল লেখা', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const seen: boolean[] = [];
    h.engine.onChange((s) => seen.push(s.online));
    await h.engine.record(sale());
    h.server.down = true;
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(1);
    expect(seen.at(-1)).toBe(false);
    h.server.down = false;
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(0);
  });

  test('সার্ভার না নিলে: প্রভাব বাদ (স্টক ফেরে), রসিদে rejected, গোনা', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    h.server.rejectTypes.add('sale');
    await h.engine.record(sale());
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(0);
    expect((await h.engine.items('napa'))[0].stock).toBe(100);
    const [inv] = await h.engine.localInvoices();
    expect(inv).toMatchObject({ sync: 'rejected', syncError: 'too_old' });
    expect(await h.engine.rejectedOps()).toHaveLength(1);
  });

  test('verify_collision: নতুন কোড আর নতুন opId, "আবার ছাপুন", তারপর গৃহীত', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const r = await h.engine.record(sale());
    h.server.collideOnce.add(r.invoice!.verifyCode);
    await h.engine.syncNow();
    expect(h.engine.pending()).toBe(0);
    const [inv] = await h.engine.localInvoices();
    expect(inv.verifyCode).not.toBe(r.invoice!.verifyCode);
    expect(inv.reprint).toBe(true);
    expect(inv.sync).toBe('synced');
    expect(h.server.applied).toHaveLength(1);
    expect((h.server.applied[0].data as any).verifyCode).toBe(inv.verifyCode);
  });

  test('সার্ভার নম্বর বদলালে: নতুন no, ছাপা নম্বর deviceNo, রসিদে দুটোই', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    h.server.renumber['D1-000001'] = 'D1-000001-b';
    await h.engine.record(sale());
    await h.engine.syncNow();
    const [inv] = await h.engine.localInvoices();
    expect(inv).toMatchObject({ no: 'D1-000001-b', deviceNo: 'D1-000001', warn: 'renumbered' });
    const m = receiptModel(inv, true);
    expect(m.meta.map((x) => x.value)).toEqual(expect.arrayContaining(['D1-000001-b', 'D1-000001']));
  });

  test('device_revoked: অফলাইন তথ্য আর চাবি মুছে ফেলা, আর কাজ নয়', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    await h.engine.record(sale());
    h.server.revoked = true;
    let revoked = false;
    h.engine.onChange((s) => (revoked = s.revoked));
    await h.engine.syncNow();
    expect(revoked).toBe(true);
    expect(h.dropped).toHaveLength(1);
    expect(h.secrets.size).toBe(0);
    expect(await code(h.engine.record(sale()))).toBe('device_revoked');
  });
});

describe('pay / void', () => {
  test('বাকি আদায়, বাকির বেশি নয়; বাতিলে স্টক ফেরত; বাতিলের পর আর নয়', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const r = await h.engine.record(sale({ payments: [{ amount: 2, method: 'cash' }] }));
    expect(r.invoice).toMatchObject({ total: 6, paid: 2, due: 4 });
    expect(await code(h.engine.record({ type: 'pay', data: { invoice: { localId: r.localId }, amount: 5, method: 'bkash' } }))).toBe('invalid');
    const p = await h.engine.record({ type: 'pay', data: { invoice: { localId: r.localId }, amount: 4, method: 'bkash', ref: 'TX1' } });
    expect(p.invoice).toMatchObject({ paid: 6, due: 0, no: 'D1-000001' });
    expect(p.no).toBe('D1-000001');
    const v = await h.engine.record({ type: 'void', data: { invoice: { localId: r.localId }, reason: 'ভুল পণ্য' } });
    expect(v.invoice).toMatchObject({ status: 'void', voidReason: 'ভুল পণ্য' });
    expect((await h.engine.items('napa'))[0].stock).toBe(100);
    expect(await code(h.engine.record({ type: 'void', data: { invoice: { localId: r.localId }, reason: 'x' } }))).toBe('invalid');
    await h.engine.syncNow();
    const voidOp = h.server.applied.find((o) => o.type === 'void')!;
    expect(voidOp.data).toMatchObject({ invoice: { localId: r.localId, no: 'D1-000001' } });
    expect((voidOp.data as any).invoice.opId).toBeTruthy();
  });

  test('অনলাইনে বানানো রসিদ (snapshot দিয়ে) — বাকি আদায়; অন্য ব্যবসার রসিদ নয়', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const online = { ...(await h.engine.record(sale({ payments: [] }))).invoice!, id: 'srv9', localId: undefined, bizId: 'b1' };
    const p = await h.engine.record({ type: 'pay', data: { invoice: { id: 'srv9', invoice: online }, amount: 6, method: 'cash' } });
    expect(p.invoice).toMatchObject({ due: 0 });
    const other = { ...online, id: 'srv10', bizId: 'b2' };
    expect(await code(h.engine.record({ type: 'pay', data: { invoice: { id: 'srv10', invoice: other }, amount: 1, method: 'cash' } }))).toBe('invalid');
    await h.engine.syncNow();
    const pay = h.server.applied.find((o) => o.type === 'pay')!;
    expect(pay.data).toEqual(expect.objectContaining({ invoice: { id: 'srv9', no: online.no } }));
  });
});

describe('পিনের ভূমিকা (I7), লক আর ৭ দিন', () => {
  test('কাউন্টার: বাতিল, স্টক ঠিক করা, দাম বদল, ১০%-এর বেশি ছাড় নয়; মালিকের পিন দিলে হয়', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    const r = await h.engine.record(sale());
    h.state.role = 'counter';
    expect(await code(h.engine.record({ type: 'void', data: { invoice: { localId: r.localId }, reason: 'x' } }))).toBe('owner_only');
    expect(await code(h.engine.record({ type: 'adjust', data: { itemId: 'i1', delta: -1 } }))).toBe('owner_only');
    expect(await code(h.engine.record(sale({ lines: [{ itemId: 'i1', name: 'Napa', qty: 1, price: 1.5 }] })))).toBe('owner_only');
    expect(await code(h.engine.record(sale({ lines: [{ itemId: 'i1', name: 'Napa', qty: 10, price: 2 }], disc: 3 })))).toBe('owner_only');
    expect(await code(h.engine.record(sale({ lines: [{ itemId: 'i1', name: 'Napa', qty: 10, price: 2 }], disc: 2 })))).toBe('ok');
    expect(await h.engine.requireOwner()).toBe(true);
    expect(await code(h.engine.record({ type: 'void', data: { invoice: { localId: r.localId }, reason: 'x' } }))).toBe('ok');
  });

  test('লক, ৭ দিন পার, ঘড়ি পিছানো — নতুন কাজ নয় (পড়া চলে)', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    for (const g of ['locked', 'login_expired', 'clock'] as const) {
      h.state.gate = g;
      expect(await code(h.engine.record(sale()))).toBe(g);
    }
    expect(await h.engine.items('')).toHaveLength(2);
  });

  test('ভুল লেখা ধরা: খালি লাইন, শূন্য পরিমাণ, অন্য ধরনের কাজ', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    expect(await code(h.engine.record({ type: 'sale', data: { lines: [] } }))).toBe('invalid');
    expect(await code(h.engine.record(sale({ lines: [{ name: 'x', qty: 0, price: 1 }] })))).toBe('invalid');
    expect(await code(h.engine.record({ type: 'fee', data: { visitType: 'new', amount: 500, method: 'cash', appointmentId: 'a1' } }))).toBe('invalid');
  });
});

describe('চেম্বার আর ল্যাব', () => {
  test('ওয়াক-ইন, চেক-ইন, ফি — দিনের তালিকায় সাথে সাথে; কম ফি কাউন্টারে নয়', async () => {
    const server = new FakeServer();
    server.bootstrapExtra = {
      appointments: [{ id: 'a1', date: '2026-10-23', chamberKey: 'c0', serial: 1, name: 'Rahim', phone: '01799999999', status: 'booked' }],
      fees: { new: 500, followup: 300 },
    };
    const h = await harness({ server });
    await h.engine.ready('chamber', 'doc1');
    const w = await h.engine.record({ type: 'walkin', data: { name: 'Salma', chamberKey: 'c0', date: '2026-10-23' } });
    await h.engine.record({ type: 'checkin', data: { appointmentId: 'a1' } });
    const f = await h.engine.record({ type: 'fee', data: { appointmentId: 'a1', visitType: 'new', amount: 500, method: 'cash' } });
    expect(f.invoice).toMatchObject({ kind: 'chamber', total: 500, customer: { name: 'Rahim' }, refType: 'appointment', refId: 'a1' });
    const day = await h.engine.chamberDay('2026-10-23');
    expect(day.fees).toEqual({ new: 500, followup: 300 });
    expect(day.appointments.map((a) => [a.name, a.serial, a.status, a.feeNo ?? ''])).toEqual([
      ['Rahim', 1, 'checked_in', f.no],
      ['Salma', 2, 'waiting', ''],
    ]);
    expect(day.appointments[0]).not.toHaveProperty('phone');
    expect(w.invoice).toBeNull();
    h.state.role = 'counter';
    expect(await code(h.engine.record({ type: 'fee', data: { appointmentId: 'a1', visitType: 'new', amount: 400, method: 'cash' } }))).toBe('owner_only');
    await h.engine.syncNow();
    expect((await h.engine.chamberDay('2026-10-23')).appointments.map((a) => a.status)).toEqual(['checked_in', 'waiting']);
  });

  test('ল্যাব বিল: তালিকার দাম, ফি, বিলের ছাড়', async () => {
    const server = new FakeServer();
    server.bootstrapExtra = { items: undefined, prices: [{ id: 't1', name: 'CBC', price: 400 }, { id: 't2', name: 'RBS', price: 150 }], fees: [{ label: 'Sample', amount: 50 }] };
    const h = await harness({ server });
    await h.engine.ready('lab', 'lab1');
    const lp = await h.engine.labPrices();
    expect(lp.items.map((x) => x.name)).toEqual(['CBC', 'RBS']);
    expect(lp.fees).toEqual([{ label: 'Sample', amount: 50 }]);
    const r = await h.engine.record({ type: 'lab_bill', data: { lines: [{ testId: 't1', name: 'CBC', price: 400 }], fees: lp.fees, disc: 20, customer: { name: 'Nila' } } });
    expect(r.invoice).toMatchObject({ kind: 'lab', subtotal: 400, disc: 20, total: 430, paid: 430, due: 0 });
  });
});

describe('গোপনীয়তা', () => {
  test('ডিস্কে রসিদ আর কাজ এনক্রিপ্ট করা (নাম/ফোন খোলা লেখায় নেই)', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    await h.engine.record(sale());
    const dump = JSON.stringify([...h.db.raw.prepare('SELECT data, effects FROM ops').all(), ...h.db.raw.prepare('SELECT json FROM invoices').all()]);
    expect(dump).not.toContain('Karim');
    expect(dump).not.toContain('01711111111');
    expect(dump).toContain('g1:');
  });

  test('৩০ দিনের পুরোনো সিঙ্ক হওয়া রসিদ খোলার সময় মোছা', async () => {
    const h = await harness();
    await h.engine.ready('pharmacy', 'b1');
    await h.engine.record(sale());
    await h.engine.syncNow();
    const h2 = await h.reopen();
    h2.state.now += 31 * 86_400_000;
    await h2.engine.ready('pharmacy', 'b1');
    expect(await h2.engine.localInvoices()).toHaveLength(0);
  });

  test('ডেমো ব্যবসা: কোড DEMO…, রসিদে ডেমো জলছাপ', async () => {
    const server = new FakeServer();
    server.bootstrapExtra = { demo: true };
    const h = await harness({ server });
    await h.engine.ready('pharmacy', 'b1');
    const r = await h.engine.record(sale());
    expect(r.invoice!.verifyCode).toMatch(/^DEMO[2-9A-HJ-NP-Z]{6}$/);
    expect(r.invoice!.demo).toBe(true);
    expect(receiptModel(r.invoice!).demo).toBe(true);
  });
});
