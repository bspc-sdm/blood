// রসিদ: হিসাব, সার্ভারের উত্তর পড়া, রসিদের কাগজ (বাতিল ছাপ, অঙ্ক, ফোন না ছাপা, মূসক নয়, অফলাইন/ডেমো),
// বাতিলের কারণ, আর ডেস্কটপের পথ (নকল OfflineStore — record/print/মালিকের পিন)।
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { setLang } from '@/lib/i18n';
import type { OfflineStore } from '@/offline';
import Receipt from '@/sections/invoices/Receipt.vue';
import VoidDialog from '@/sections/invoices/VoidDialog.vue';
import { billOf, changeOf, digitsOf, invNo, isDemo, moneyIn, paymentsOf, parseInvoice, parsePage, parseVerify, pickInvoice, shiftMonth, thisMonth } from '@/sections/invoices/model';
import { issue, payDue, voidInvoice } from '@/sections/invoices/actions';
import { deskRole, isDesktop, ownerGate, setOfflineStore } from '@/sections/invoices/offline';
import { printInvoice } from '@/sections/invoices/print';

vi.mock('@/lib/api', async (orig) => {
  const m = await orig<typeof import('@/lib/api')>();
  return { ...m, call: vi.fn(async (action: string, body: any) => ({ invoice: { ...INV, id: 'srv1', no: 'W-000009', _action: action, _body: body } })) };
});
import { call } from '@/lib/api';

const INV = {
  id: 'i1', no: 'W-000123', kind: 'pharmacy', bizId: 'p1', bizName: 'ডেমো লাইফ ফার্মেসি', bizPhone: '01711000000', bizAddress: 'চরপাড়া, ময়মনসিংহ',
  customer: { name: 'রাকিব', phone: '01812345678' },
  lines: [{ name: 'Napa', qty: 10, price: 1.2, disc: 0, amount: 12 }, { name: 'Seclo 20', qty: 14, price: 7, disc: 9.8, amount: 88.2 }],
  subtotal: 100.2, disc: 0.2, fees: [], total: 100, paid: 80, due: 20,
  payments: [{ at: 1761200000000, amount: 80, method: 'bkash', ref: 'TX1' }],
  status: 'issued', issuedAt: 1761200000000, issuedBy: 'u-work', offline: false, verifyCode: 'AB12CD34EF', verifyUrl: 'https://lifolo.com/v/AB12CD34EF',
} as const;

function fakeStore(over: Partial<OfflineStore> = {}): OfflineStore & { ops: any[]; printed: any[] } {
  const ops: any[] = [];
  const printed: any[] = [];
  return {
    ops, printed,
    isDesktop: () => true,
    ready: async () => ({ deviceId: 'dev1', series: 'D1', asOf: 1, pending: 0 }),
    items: async () => [],
    labPrices: async () => ({ items: [], fees: [] }),
    chamberDay: async (date) => ({ date, chambers: [], appointments: [], fees: {} }),
    invoices: async () => [],
    role: () => null,
    requireOwner: async () => true,
    record: async (op) => {
      ops.push(op);
      const isInv = ['sale', 'lab_bill', 'fee', 'pay', 'void'].includes(op.type);
      return { localId: 'L1', no: isInv ? 'D1-000001' : '', invoice: isInv ? { ...INV, id: '', no: 'D1-000001', offline: true, localId: 'L1', sync: 'pending' } as any : null };
    },
    pending: () => ops.length,
    syncNow: async () => {},
    onChange: () => () => {},
    print: async (inv, opts) => {
      printed.push([inv.no, opts]);
    },
    ...over,
  };
}

afterEach(() => {
  setOfflineStore(null);
  setLang('bn');
  vi.mocked(call).mockClear();
});

describe('রসিদের হিসাব', () => {
  it('billOf: লাইনের ছাড় + বিলের % ছাড় + চার্জ', () => {
    const b = billOf([{ name: 'a', qty: 2, price: 50, disc: 10 }, { name: 'b', qty: 1, price: 30, disc: 0 }], { type: 'pct', v: 10 }, [{ label: 'বাসা', amount: 100 }]);
    expect(b).toEqual({ subtotal: 130, disc: 22, fees: 100, total: 208 });
  });
  it('billOf: টাকায় ছাড় মোটের বেশি হয় না', () => {
    expect(billOf([{ name: 'a', qty: 1, price: 50, disc: 0 }], { type: 'amt', v: 80 }).total).toBe(0);
  });
  it('changeOf / paymentsOf: ফেরত, বাকি, খালি = পুরো টাকা, ০ = সব বাকি', () => {
    expect(changeOf(450, 500)).toEqual({ paid: 450, change: 50, due: 0 });
    expect(changeOf(450, 400)).toEqual({ paid: 400, change: 0, due: 50 });
    expect(paymentsOf(450, 'cash', null)).toEqual([{ amount: 450, method: 'cash' }]);
    expect(paymentsOf(450, 'bkash', 200, ' TX9 ')).toEqual([{ amount: 200, method: 'bkash', ref: 'TX9' }]);
    expect(paymentsOf(450, 'cash', 0)).toEqual([]);
  });
  it('টাকা আর অঙ্ক: বাংলা মূল, ইংরেজি বাছা যায়, সবসময় দুই দশমিক', () => {
    expect(moneyIn(1234.5, 'bn')).toBe('৳১,২৩৪.৫০');
    expect(moneyIn(1234.5, 'en')).toBe('৳1,234.50');
    expect(digitsOf('W-000123', 'bn')).toBe('W-০০০১২৩');
    // রসিদ নং: সবসময় ইংরেজি অঙ্ক (বাংলায় এলেও)
    expect(invNo('W-000301')).toBe('W-000301');
    expect(invNo('D১-০০০০১২')).toBe('D1-000012');
    expect(invNo(null)).toBe('');
  });
  it('মাস: আগের/পরের, এই মাস ঢাকার সময়ে', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(shiftMonth('2026-12', 1)).toBe('2027-01');
    expect(thisMonth(Date.UTC(2026, 9, 31, 19, 0))).toBe('2026-11');
  });
});

describe('সার্ভারের উত্তর', () => {
  it('pickInvoice: {invoice} বা সরাসরি; টাকা সংখ্যা', () => {
    expect(pickInvoice({ invoice: { ...INV, total: '100.00' } })!.total).toBe(100);
    expect(pickInvoice(INV)!.no).toBe('W-000123');
    expect(pickInvoice({ ok: true })).toBeNull();
  });
  it('parsePage: items, cursor, sum', () => {
    const p = parsePage({ items: [INV], cursor: 'c1', sum: { count: 1, total: 100, paid: 80, due: 20 } });
    expect(p.next).toBe('c1');
    expect(p.sum).toEqual({ count: 1, total: 100, paid: 80, due: 20 });
    expect(p.items[0].lines).toHaveLength(2);
  });
  it('parseVerify: date → issuedAt, valid → issued, ডেমো (demo বা DEMO কোড)', () => {
    const v = parseVerify({ invoice: { bizName: 'X', kind: 'lab', no: 'W-1', date: 5, total: 10, paid: 10, due: 0, status: 'valid', lines: 3, sanctioned: true, voidedAt: 9 } })!;
    expect(v).toMatchObject({ issuedAt: 5, status: 'issued', lines: 3, demo: false, suspended: true, voidedAt: 9 });
    expect(parseVerify({ invoice: { no: 'W-1', status: 'void', demo: true } })!.demo).toBe(true);
    expect(isDemo({ verifyCode: 'DEMO123456' })).toBe(true);
    expect(parseVerify({})).toBeNull();
  });
});

describe('রসিদের কাগজ', () => {
  it('A4: প্রতিষ্ঠান, লাইন, মোট, পরিশোধ/বাকি/মাধ্যম, QR আর যাচাইয়ের লেখা, মূসক নয়; ক্রেতার ফোন ছাপা হয় না', () => {
    const w = mount(Receipt, { props: { inv: parseInvoice(INV), paper: 'A4', digits: 'bn' } });
    const t = w.text();
    expect(t).toContain('ডেমো লাইফ ফার্মেসি');
    expect(t).toContain('০১৭১১০০০০০০');
    // রসিদ নং সবসময় ইংরেজি অঙ্কে, বাকি সব বাংলা
    expect(t).toContain('W-000123');
    expect(t).not.toContain('W-০০০১২৩');
    expect(w.findAll('tbody tr')).toHaveLength(2);
    expect(t).toContain('৳১০০.০০');
    expect(t).toContain('বাকি');
    expect(t).toContain('৳২০.০০');
    expect(t).toContain('বিকাশ');
    expect(t).toContain('lifolo.com/v/AB12CD34EF — রসিদ যাচাই করুন');
    expect(t).toContain('টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়');
    expect(t).toContain('রাকিব');
    expect(t).not.toContain('০১৮১২৩৪৫৬৭৮');
    expect(w.find('svg.health-qr').exists()).toBe(true);
    expect(w.find('.rcpt-stamp').exists()).toBe(false);
  });
  it('থার্মাল ৫৮ মিমি: এক কলাম, ইংরেজি অঙ্ক', () => {
    const w = mount(Receipt, { props: { inv: parseInvoice(INV), paper: '58', digits: 'en' } });
    expect(w.classes()).toContain('p58');
    expect(w.find('table').exists()).toBe(false);
    expect(w.findAll('.rcpt-lines li')).toHaveLength(2);
    expect(w.text()).toContain('W-000123');
    expect(w.text()).toContain('৳100.00');
  });
  it('বাতিল রসিদে "বাতিল" ছাপ আর কারণ', () => {
    const w = mount(Receipt, { props: { inv: parseInvoice({ ...INV, status: 'void', voidReason: 'ভুল দাম — আবার লেখা হয়েছে' }) } });
    expect(w.get('.rcpt-stamp').text()).toBe('বাতিল');
    expect(w.text()).toContain('ভুল দাম — আবার লেখা হয়েছে');
  });
  it('অফলাইন রসিদ: "ইন্টারনেট এলে যাচাই হবে"; ডেমো: জলছাপ', () => {
    const off = mount(Receipt, { props: { inv: parseInvoice({ ...INV, id: '', offline: true, sync: 'pending' }) } });
    expect(off.get('[data-testid=receipt-offline]').text()).toContain('ইন্টারনেট এলে যাচাই হবে');
    const demo = mount(Receipt, { props: { inv: parseInvoice({ ...INV, verifyCode: 'DEMO0AB12C' }) } });
    expect(demo.get('[data-testid=receipt-demo]').text()).toBe('ডেমো');
  });
});

describe('বাতিল', () => {
  it('তালিকা থেকে কারণ আর লেখা দুটোই লাগে; সার্ভারে reason (কোড) + note', async () => {
    const qc = new QueryClient();
    const w = mount(VoidDialog, { props: { inv: parseInvoice(INV) }, global: { plugins: [[VueQueryPlugin, { queryClient: qc }]] }, attachTo: document.body });
    await flushPromises();
    const confirm = () => document.querySelector<HTMLButtonElement>('[data-testid=void-confirm]')!;
    expect(confirm().disabled).toBe(true);
    document.querySelector<HTMLButtonElement>('[data-reason=returned]')!.click();
    await flushPromises();
    expect(confirm().disabled).toBe(true);
    const input = document.querySelector<HTMLInputElement>('input')!;
    input.value = 'দুটো পাতা ফেরত';
    input.dispatchEvent(new Event('input'));
    await flushPromises();
    expect(confirm().disabled).toBe(false);
    confirm().click();
    await flushPromises();
    expect(call).toHaveBeenCalledWith('invoice_void', { id: 'i1', reason: 'returned', note: 'দুটো পাতা ফেরত' });
    w.unmount();
  });
});

describe('ডেস্কটপের পথ (নকল OfflineStore)', () => {
  it('ওয়েবে: isDesktop মিথ্যা, issue সার্ভারে যায়', async () => {
    expect(isDesktop()).toBe(false);
    const r = await issue('lab_bill_create', { lines: [] }, () => ({ type: 'lab_bill', data: { lines: [] } }));
    expect(r.offline).toBe(false);
    expect(call).toHaveBeenCalledWith('lab_bill_create', { lines: [] });
    expect(r.invoice!.no).toBe('W-000009');
  });
  it('ডেস্কটপে: record(op), সার্ভার নয়; রসিদ অফলাইন আর ছাপা স্টোরে', async () => {
    const st = fakeStore();
    setOfflineStore(st);
    expect(isDesktop()).toBe(true);
    const r = await issue('books_sale_add', { lines: [{ itemId: 'x', qty: 1 }] }, () => ({ type: 'sale', data: { lines: [{ itemId: 'x', name: 'Napa', qty: 1, price: 1.2 }] } }));
    expect(call).not.toHaveBeenCalled();
    expect(st.ops[0]).toEqual({ type: 'sale', data: { lines: [{ itemId: 'x', name: 'Napa', qty: 1, price: 1.2 }] } });
    expect(r).toMatchObject({ offline: true });
    expect(r.invoice!.no).toBe('D1-000001');
    expect(await printInvoice(r.invoice!, '80', 'en')).toBe('desktop');
    expect(st.printed[0]).toEqual(['D1-000001', { paper: '80', englishDigits: true }]);
  });
  it('বাকি আদায় আর বাতিল: অফলাইনে বানানো রসিদ localId দিয়ে', async () => {
    const st = fakeStore();
    setOfflineStore(st);
    const local = parseInvoice({ ...INV, id: '', localId: 'L7', offline: true });
    await payDue(local, 20, 'nagad');
    await voidInvoice(parseInvoice(INV), 'wrong_price', 'x');
    expect(st.ops).toEqual([
      { type: 'pay', data: { invoice: { localId: 'L7' }, amount: 20, method: 'nagad' } },
      { type: 'void', data: { invoice: { id: 'i1' }, reason: 'wrong_price', note: 'x' } },
    ]);
  });
  it('মালিকের পিন: কাউন্টার হলে requireOwner, না দিলে owner_only; ওয়েবে বাধা নেই', async () => {
    await expect(ownerGate()).resolves.toBeUndefined();
    expect(deskRole()).toBeNull();
    const ask = vi.fn(async () => false);
    setOfflineStore(Object.assign(fakeStore(), { role: () => 'counter', requireOwner: ask }) as OfflineStore);
    expect(deskRole()).toBe('counter');
    await expect(ownerGate()).rejects.toMatchObject({ code: 'owner_only' });
    expect(ask).toHaveBeenCalled();
  });
});
