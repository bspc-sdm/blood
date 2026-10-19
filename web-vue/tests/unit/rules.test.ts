import { describe, expect, it } from 'vitest';
import { BLOOD_GROUPS, DAY_MS, ELIGIBILITY_DAYS, canDonate, isEligible, nextEligibleMillis, patientsFor } from '@/lib/rules';
import { formatDate, normalizePhone } from '@/lib/format';
import { setLang } from '@/lib/i18n';

describe('রক্তের নিয়ম', () => {
  it('O- সবাইকে দিতে পারে, AB+ শুধু AB+-কে', () => {
    expect(patientsFor('O-')).toEqual(BLOOD_GROUPS);
    expect(patientsFor('AB+')).toEqual(['AB+']);
    expect(canDonate('A+', 'B+')).toBe(false);
  });
  it('১২০ দিন পর আবার দেওয়া যায়', () => {
    const now = Date.UTC(2026, 9, 20);
    expect(isEligible(null, now)).toBe(true);
    expect(isEligible(now - (ELIGIBILITY_DAYS - 1) * DAY_MS, now)).toBe(false);
    expect(isEligible(now - ELIGIBILITY_DAYS * DAY_MS, now)).toBe(true);
    expect(nextEligibleMillis(now)).toBe(now + ELIGIBILITY_DAYS * DAY_MS);
  });
});

describe('তারিখ আর ফোন', () => {
  it('তারিখ বাংলায় আর ইংরেজিতে', () => {
    setLang('bn');
    expect(formatDate(Date.UTC(2026, 9, 17))).toBe('১৭ অক্টোবর ২০২৬');
    setLang('en');
    expect(formatDate(Date.UTC(2026, 9, 17))).toBe('17 Oct 2026');
    setLang('bn');
  });
  it('বাংলাদেশের মোবাইল নম্বর', () => {
    expect(normalizePhone('+8801711234567')).toBe('01711234567');
    expect(normalizePhone('০১৭১১২৩৪৫৬৭')).toBe('01711234567');
    expect(normalizePhone('12345')).toBeNull();
  });
});
