// রসিদের চেহারা আর থার্মাল প্রিন্টের বাইট
import { describe, expect, test } from 'vitest';
import { escpos, PAPER_DOTS, toMono, trimBottom } from '@/offline/print/escpos';
import { layout, wrap } from '@/offline/print/raster';
import { receiptModel } from '@/offline/print/receipt';
import { receiptElement } from '@/offline/print/html';
import { formatTaka, makeLine, totals } from '@/offline/money';
import type { Invoice } from '@/offline/types';

const inv = (over: Partial<Invoice> = {}): Invoice => ({
  id: '',
  no: 'D1-000012',
  kind: 'pharmacy',
  bizId: 'b1',
  bizName: 'শেফা ফার্মেসি',
  bizPhone: '01700000000',
  bizAddress: 'চরপাড়া, ময়মনসিংহ',
  customer: { name: 'করিম', phone: '01711111111' },
  lines: [makeLine({ name: 'Napa 500', qty: 3, price: 2 }), makeLine({ name: 'Seclo 20', qty: 1, price: 7, disc: 1 })],
  subtotal: 12,
  disc: 0,
  fees: [],
  total: 12,
  paid: 10,
  due: 2,
  payments: [{ at: 0, amount: 10, method: 'cash' }],
  status: 'issued',
  issuedAt: Date.UTC(2026, 9, 23, 9, 5),
  issuedBy: 'u1',
  offline: true,
  verifyCode: 'ABCDEFGH23',
  verifyUrl: 'https://lifolo.com/v/ABCDEFGH23',
  ...over,
});

describe('টাকা', () => {
  test('পয়সায় হিসাব, ছাড় মোটের বেশি নয়, বাকি ০-এর নিচে নয়', () => {
    const lines = [makeLine({ name: 'a', qty: 3, price: 0.1 }), makeLine({ name: 'b', qty: 1, price: 0.2 })];
    expect(lines.map((l) => l.amount)).toEqual([0.3, 0.2]);
    expect(totals(lines, 1, [{ label: 'x', amount: 5 }], [{ at: 0, amount: 10, method: 'cash' }])).toEqual({ subtotal: 0.5, disc: 0.5, total: 5, paid: 10, due: 0 });
    expect(formatTaka(1234567.5)).toBe('1,234,567.50');
  });
});

describe('রসিদের মডেল', () => {
  test('বাংলা অঙ্ক, ফোন কখনো নয়, মূসক নোট, অফলাইনে যাচাইয়ের লাইন', () => {
    const m = receiptModel(inv());
    expect(m.meta).toEqual([
      { label: 'রসিদ নং', value: 'D১-০০০০১২' },
      { label: 'তারিখ', value: '২৩/১০/২০২৬, ৩:০৫ PM' },
      { label: 'নাম', value: 'করিম' },
    ]);
    expect(JSON.stringify(m)).not.toContain('01711111111');
    expect(JSON.stringify(m)).not.toContain('০১৭১১১১১১১১');
    expect(m.verifyText).toBe('lifolo.com/v/ABCDEFGH23 — রসিদ যাচাই করুন');
    expect(m.offlineVerify).toBe('ইন্টারনেট এলে যাচাই হবে');
    expect(m.notVat).toBe('টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়');
    expect(m.payments.at(-1)).toEqual({ label: 'বাকি', value: '২.০০', strong: true });
    expect(receiptModel(inv({ id: 'x' })).offlineVerify).toBe('');
    expect(receiptModel(inv(), true).meta[0].value).toBe('D1-000012');
  });

  test('HTML: বাতিল ছাপ, ডেমো জলছাপ, ব্যবহারকারীর লেখা কখনো HTML নয়', () => {
    const e = receiptElement(receiptModel(inv({ status: 'void', voidReason: 'ভুল', demo: true, bizName: '<img src=x onerror=alert(1)>' })), '80');
    expect(e.querySelector('.lfr-void')?.textContent).toBe('বাতিল');
    expect(e.querySelector('.lfr-demo')?.textContent).toBe('ডেমো');
    expect(e.querySelector('img')).toBeNull();
    expect(e.querySelector('.lfr-biz')?.textContent).toBe('<img src=x onerror=alert(1)>');
    expect(e.querySelector('svg path')?.getAttribute('d')).toMatch(/^M\d+ \d+h1v1h-1z/);
    expect(e.textContent).toContain('ইন্টারনেট এলে যাচাই হবে');
  });
});

describe('থার্মাল', () => {
  const measure = (t: string, s: number) => [...t].length * s * 0.5;

  test('লেখা ভাঙা চওড়ার ভেতরে থাকে', () => {
    const lines = wrap('এক দুই তিন চার পাঁচ ছয় সাত আট নয় দশ', 100, 20, measure);
    expect(lines.length).toBeGreaterThan(1);
    for (const l of lines) expect(measure(l, 20)).toBeLessThanOrEqual(100);
    expect(wrap('অনেকলম্বাশব্দযেটাভাঙতেইহবে', 60, 20, measure).every((l) => measure(l, 20) <= 60)).toBe(true);
  });

  test('layout: QR, মোট, ডেমো আর বাতিলের ছাপ, সব আঁকা কাগজের ভেতরে', () => {
    for (const w of [PAPER_DOTS['58'], PAPER_DOTS['80']]) {
      const { ops, height } = layout(receiptModel(inv({ status: 'void', demo: true })), w, measure);
      expect(ops.some((o) => o.k === 'qr')).toBe(true);
      expect(ops.filter((o) => o.k === 'stamp').map((o) => (o as any).text)).toEqual(['ডেমো', 'বাতিল']);
      for (const o of ops) if (o.k === 'text') expect(o.y).toBeLessThan(height);
      const qr = ops.find((o) => o.k === 'qr') as any;
      expect(qr.x + qr.size).toBeLessThanOrEqual(w);
    }
  });

  test('ESC/POS: শুরু, GS v 0 টুকরো, কাটা, ড্রয়ার', () => {
    const w = 16;
    const h = 300;
    const rgba = new Uint8ClampedArray(w * h * 4).fill(255);
    for (let x = 0; x < 8; x++) rgba.set([0, 0, 0, 255], x * 4); // প্রথম সারির বাঁ ৮ পিক্সেল কালো
    const m = toMono(rgba, w, h);
    expect(m.bits[0]).toBe(0xff);
    expect(m.bits[1]).toBe(0);
    const t = trimBottom(m, 2);
    expect(t.height).toBe(3);
    const b = escpos(m, { drawer: true, band: 128 });
    expect([...b.slice(0, 2)]).toEqual([0x1b, 0x40]);
    expect([...b.slice(2, 7)]).toEqual([0x1b, 0x70, 0, 0x19, 0xfa]);
    // ৩০০ সারি → ১২৮ + ১২৮ + ৪৪
    const heads: number[] = [];
    for (let i = 0; i < b.length - 3; i++) if (b[i] === 0x1d && b[i + 1] === 0x76 && b[i + 2] === 0x30) heads.push(b[i + 6] | (b[i + 7] << 8));
    expect(heads).toEqual([128, 128, 44]);
    expect([...b.slice(-4)]).toEqual([0x1d, 0x56, 0x42, 0x00]);
    expect([...escpos(m, { cut: false }).slice(-3)]).toEqual([0x1b, 0x64, 4]);
  });
});
