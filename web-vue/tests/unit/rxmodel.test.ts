import { describe, expect, it } from 'vitest';
import { LAB_SEGS, type LabSeg, autoFlag, cleanCode, defRangeText, isNumericParam, labSegOf, parseNum, rangeText, segCounts, validCode, type LabOrder } from '@/sections/rx/partner/model';
import { setLang } from '@/lib/i18n';

describe('ল্যাবের মাত্রা', () => {
  it('বেশি/কম নিজে থেকে', () => {
    expect(autoFlag('12', 4, 10)).toBe('high');
    expect(autoFlag('3.5', 4, 10)).toBe('low');
    expect(autoFlag('5', 4, 10)).toBe('');
    expect(autoFlag('Negative', 4, 10)).toBeNull();
    expect(autoFlag('5', null, null)).toBeNull();
  });
  it('মাত্রার লেখা', () => {
    expect(rangeText(4, 10)).toBe('4 – 10');
    expect(rangeText(null, 200)).toBe('< 200');
    expect(rangeText(1, null)).toBe('> 1');
    setLang('bn');
    expect(defRangeText({ key: 'hb', name: 'Hb', m: [13, 17], f: [12, 15] } as never)).toBe('পু: 13 – 17, না: 12 – 15');
  });
  it('সংখ্যার ঘর চেনা', () => {
    expect(isNumericParam({ key: 'a', name: 'A', r: [1, 2] } as never)).toBe(true);
    expect(isNumericParam({ key: 'a', name: 'A', r: [1, 2], options: ['x'] } as never)).toBe(false);
    expect(isNumericParam({ key: 'a', name: 'A', text: 'Negative' } as never)).toBe(false);
  });
  it('লেখা সংখ্যা পড়া (বাংলা অঙ্ক, কমা)', () => {
    expect(parseNum('১২.৫')).toBe(12.5);
    expect(parseNum('1,200')).toBe(1200);
    expect(parseNum('  ')).toBeNull();
    expect(parseNum('abc')).toBeNaN();
  });
});

describe('অনুরোধের ভাগ', () => {
  const o = (status: string, extra: Partial<LabOrder> = {}) => ({ id: status, status, ...extra }) as LabOrder;
  it('সার্ভারের seg না থাকলে অবস্থা থেকে', () => {
    expect(labSegOf(o('sent'))).toBe('new');
    expect(labSegOf(o('sent', { home: { status: 'confirmed' } } as never))).toBe('active');
    expect(labSegOf(o('received'))).toBe('active');
    expect(labSegOf(o('reported'))).toBe('reported');
    expect(labSegOf(o('cancelled'))).toBe('closed');
  });
  it('গণনা: সার্ভারের সংখ্যা আগে, না থাকলে তালিকা গুনে', () => {
    const items = [o('sent'), o('sent'), o('reported')];
    expect(segCounts(LAB_SEGS, { new: 9 }, labSegOf as (x: never) => LabSeg, items)).toEqual({ new: 9, active: 0, reported: 1, closed: 0 });
  });
});

describe('প্রেসক্রিপশনের কোড', () => {
  it('লিংক বা ফাঁকা থেকে কোড', () => {
    // কোড ১০ অক্ষরের, I/O/0/1 নেই
    expect(cleanCode('abcd-efgh 23')).toBe('ABCDEFGH23');
    expect(cleanCode('https://lifolo.app/rx/KM7PQRSTW2')).toBe('KM7PQRSTW2');
    expect(validCode('KM7PQRSTW2')).toBe(true);
    expect(validCode('KM7PQRSTW1')).toBe(false);
  });
});
