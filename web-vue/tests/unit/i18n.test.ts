import { afterEach, describe, expect, it } from 'vitest';
import { nextTick } from 'vue';
import { getLang, num, setLang, toAsciiDigits, tr, trPlain, useLang } from '@/lib/i18n';

describe('i18n', () => {
  afterEach(() => setLang('bn'));

  it('বাংলায় বাংলা লেখা আর অঙ্ক', () => {
    setLang('bn');
    expect(getLang()).toBe('bn');
    expect(tr('Log in', 'লগইন')).toBe('লগইন');
    expect(num(2026)).toBe('২০২৬');
    expect(num(null)).toBe('');
  });

  it('ইংরেজিতে tr Title Case, trPlain যেমন আছে', () => {
    setLang('en');
    expect(tr('log in', 'লগইন')).toBe('Log In');
    expect(trPlain('could not save', 'রাখা যায়নি')).toBe('could not save');
    expect(num(12)).toBe('12');
  });

  it('ভাষা বদলালে ref-ও বদলায় (টেমপ্লেট নিজে থেকে নতুন করে আঁকে)', async () => {
    const l = useLang();
    setLang('en');
    await nextTick();
    expect(l.value).toBe('en');
    setLang('bn');
    expect(l.value).toBe('bn');
  });

  it('বাংলা অঙ্ক → ইংরেজি', () => {
    expect(toAsciiDigits('০১৭১১-২৩৪৫৬৭')).toBe('01711-234567');
  });
});
