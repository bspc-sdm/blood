// ওষুধ খুঁজুন — কতটা লাগবে (medfind.ts, backend/docs/MEDFIND.md): ধরন → একক, লেখা, সংখ্যা পড়া, ক্রেতার দেখা উত্তর
import { afterEach, describe, expect, it } from 'vitest';
import { setLang } from '@/lib/i18n';
import { needText, parseQty, qtyText, replyText, unitName, unitsFor, unitWord, type FindShop } from '@/sections/services/medfind';

afterEach(() => setLang('bn'));

const shop = (o: Partial<FindShop> = {}): FindShop => ({ id: 's', name: 'দোকান', phone: '', address: '', openNow: true, open24: false, km: 1, ...o });

describe('ধরন অনুযায়ী একক (সার্ভারের MedQty-র মতো)', () => {
  it('ট্যাবলেট/ক্যাপসুল: পাতা (আগে) বা পিস; সিরাপ: বোতল; ইনজেকশন: টি/ভায়াল; বাকি: টি', () => {
    expect(unitsFor('Tablet')).toEqual(['strip', 'pcs']);
    expect(unitsFor('Soft Gelatin Capsule')).toEqual(['strip', 'pcs']);
    expect(unitsFor('Chewable Tablet')).toEqual(['strip', 'pcs']);
    expect(unitsFor('Syrup')).toEqual(['bottle']);
    expect(unitsFor('Powder For Suspension')).toEqual(['bottle']);
    expect(unitsFor('IV/IM Injection')).toEqual(['vial']);
    expect(unitsFor('IV Infusion')).toEqual(['vial']);
    expect(unitsFor('Nebuliser Suspension')).toEqual(['unit']);
    expect(unitsFor('Cream')).toEqual(['unit']);
    expect(unitsFor('')).toEqual(['unit']);
    expect(unitsFor(undefined)).toEqual(['unit']);
  });
  it('এককের নাম আর সংখ্যার পরের শব্দ', () => {
    expect(['strip', 'pcs', 'bottle', 'vial', 'unit'].map((u) => unitName(u as never))).toEqual(['পাতা', 'পিস', 'বোতল', 'টি/ভায়াল', 'টি']);
    expect(unitWord('strip')).toBe('পাতা');
    setLang('en');
    expect(unitName('strip')).toBe('Strip');
    expect(unitWord('pcs')).toBe('pcs');
  });
});

describe('পরিমাণের লেখা', () => {
  it('বাংলা অঙ্কে, "টি" সংখ্যার সাথে লাগানো', () => {
    expect(qtyText(3, 'strip')).toBe('৩ পাতা');
    expect(qtyText(10, 'pcs')).toBe('১০ পিস');
    expect(qtyText(1, 'bottle')).toBe('১ বোতল');
    expect(qtyText(2, 'vial')).toBe('২টি/ভায়াল');
    expect(qtyText(5, 'unit')).toBe('৫টি');
    expect(needText(3, 'strip')).toBe('৩ পাতা লাগবে');
    expect(needText(null, null)).toBe('');
  });
  it('ইংরেজিতে এক/বহু', () => {
    setLang('en');
    expect(qtyText(1, 'strip')).toBe('1 strip');
    expect(qtyText(3, 'strip')).toBe('3 strips');
    expect(qtyText(2, 'vial')).toBe('2 vials');
    expect(qtyText(1, 'unit')).toBe('1 pc');
    expect(needText(2, 'bottle')).toBe('Needs 2 bottles');
  });
});

describe('লেখা সংখ্যা পড়া', () => {
  it('১–৯৯৯ (ক্রেতা), ১–৯৯৯৯ (দোকান); বাংলা অঙ্কও', () => {
    expect(parseQty('12')).toBe(12);
    expect(parseQty('১২')).toBe(12);
    expect(parseQty(' 7 ')).toBe(7);
    for (const bad of ['0', '', 'abc', '1.5', '-3', '1000']) expect(parseQty(bad)).toBeNull();
    expect(parseQty('1000', 9999)).toBe(1000);
    expect(parseQty('৯৯৯৯', 9999)).toBe(9999);
    expect(parseQty('10000', 9999)).toBeNull();
  });
});

describe('ক্রেতা যা দেখেন', () => {
  const f = { qty: 20, unit: 'pcs' as const };
  it('দরকারের সমান বা বেশি: পুরোটা আছে; কম: আপনার দরকার কত', () => {
    expect(replyText(f, shop({ haveQty: 20, haveUnit: 'pcs', full: true }))).toBe('আছে — ২০ পিস · পুরোটা আছে');
    expect(replyText(f, shop({ haveQty: 30, haveUnit: 'pcs', full: true }))).toBe('আছে — ৩০ পিস · পুরোটা আছে');
    expect(replyText(f, shop({ haveQty: 10, haveUnit: 'pcs', full: false }))).toBe('আছে — ১০ পিস (আপনার দরকার ২০)');
    expect(replyText({ qty: null, unit: null }, shop({ haveQty: 3, haveUnit: 'strip' }))).toBe('আছে — ৩ পাতা');
    expect(replyText(f, shop())).toBe('আছে বলেছে');
    setLang('en');
    expect(replyText(f, shop({ haveQty: 10, haveUnit: 'pcs' }))).toBe('Has 10 pcs (you need 20)');
    expect(replyText(f, shop({ haveQty: 20, haveUnit: 'pcs' }))).toBe('Has 20 pcs · all you need');
  });
});
