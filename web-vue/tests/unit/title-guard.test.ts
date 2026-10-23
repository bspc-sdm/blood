// "ডা." পদবির নিয়ম (lib/titleGuard.ts, backend/docs/PRACTICE.md §৬): কারা লিখতে পারেন (শুধু এলোপ্যাথি ডাক্তার), প্রতিটা
// পদবির ধরন, পদবি বাদ দেওয়া, নামের ঘরের ভুল আর ছোট লেখা (সুইচ বন্ধে কিছু নয়), পেশার নাম (সার্ভারের typeLabel আগে),
// প্রেসক্রিপশনের মাথা।
import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { setFeaturesForTest } from '@/lib/features';
import { TG, TITLE_CODE, hasTitle, practitionerLabel, practitionerName, stripTitle, titleEligible, titleError, titleHint } from '@/lib/titleGuard';
import { parseAppointment, parseProvider } from '@/sections/appointment/model';
import RxPaper from '@/sections/health/RxPaper.vue';
import type { Rx } from '@/sections/health/rx';

const ERR = 'MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না';
const HINT = 'নামের আগে ‘ডা.’ লিখবেন না — পেশা নিজে থেকেই দেখানো হবে';
const ON = { title_guard: true, practice_systems: true };

describe('নিয়ম (সার্ভারের TitleRules-এর মতো)', () => {
  it('শুধু এলোপ্যাথি ডাক্তার (MBBS/BDS) "ডা." লিখতে পারেন', () => {
    expect(titleEligible('doctor', 'allopathy')).toBe(true);
    expect(titleEligible('doctor', null)).toBe(true);
    for (const [t, s] of [['doctor', 'homeopathy'], ['doctor', 'unani'], ['doctor', 'ayurveda'], ['nurse', null], ['nutritionist', null], ['trainer', null], ['physio', null], ['psychologist', null], ['expert', null]]) {
      expect(titleEligible(t, s), `${t} ${s}`).toBe(false);
    }
  });

  it('প্রতিটা ধরন ধরা পড়ে আর বাদ যায়; নাম নয় এমন কিছু নয়', () => {
    for (const v of ['ডা. করিম', 'ডাঃ করিম', 'ডা করিম', 'ডাক্তার করিম', 'ডা: করিম', 'ডা.করিম', 'Dr. Karim', 'Dr Karim', 'Dr: Karim', 'DR. Karim', 'Doctor Karim', 'dr.Karim', '  Dr .  Karim', 'DOCTOR Karim']) {
      expect(hasTitle(v), v).toBe(true);
      expect(['করিম', 'Karim']).toContain(stripTitle(v));
    }
    for (const n of ['ডালিয়া আক্তার', 'Drabir Hasan', 'Doctorate Ali', 'করিম ডা.', 'Dr.', 'ডা.', '', 'Andrew']) expect(hasTitle(n), n).toBe(false);
    expect(stripTitle('ডা. ডা. করিম')).toBe('করিম');
    expect(TITLE_CODE).toBe('title_not_allowed');
  });
});

describe('নামের ঘর', () => {
  beforeEach(() => setFeaturesForTest(ON));

  it('পেশাজীবী (এলোপ্যাথি ডাক্তার ছাড়া) "ডা." লিখলে ভুল, নিচে ছোট লেখা', () => {
    expect(titleError('ডা. করিম', 'doctor', 'homeopathy')).toBe(ERR);
    expect(titleError('Dr. Rahima', 'nurse', null)).toBe(ERR);
    expect(titleError('Doctor Rahima', 'physio', null)).toBe(ERR);
    expect(titleError('করিম', 'doctor', 'homeopathy')).toBeNull();
    expect(titleError('ডা. করিম', 'doctor', 'allopathy')).toBeNull();
    expect(titleError('ডা. করিম', null, null)).toBeNull();
    expect(titleHint('doctor', 'unani')).toBe(HINT);
    expect(titleHint('psychologist', null)).toBe(HINT);
    expect(titleHint('doctor', 'allopathy')).toBeNull();
    expect(titleHint(null, null)).toBeNull();
    expect(TG.error()).toBe(ERR);
  });

  it('সুইচ বন্ধ: কিছু নয়', () => {
    setFeaturesForTest({ practice_systems: true });
    expect(titleError('ডা. করিম', 'doctor', 'homeopathy')).toBeNull();
    expect(titleHint('nurse', null)).toBeNull();
  });
});

describe('পেশার নাম', () => {
  beforeEach(() => setFeaturesForTest(ON));

  it('অ-এলোপ্যাথিক চিকিৎসক "ডাক্তার" নন; এলোপ্যাথি, ফিজিও, সাইকোলজিস্টে null (আগের নাম)', () => {
    expect(practitionerName('homeopathy')).toBe('হোমিওপ্যাথিক চিকিৎসক');
    expect(practitionerName('unani')).toBe('ইউনানি চিকিৎসক');
    expect(practitionerName('ayurveda')).toBe('আয়ুর্বেদিক চিকিৎসক');
    expect(practitionerLabel({ type: 'doctor', system: 'homeopathy' })).toBe('হোমিওপ্যাথিক চিকিৎসক');
    expect(practitionerLabel({ type: 'doctor', system: 'allopathy' })).toBeNull();
    expect(practitionerLabel({ type: 'physio', system: null })).toBeNull();
    expect(practitionerLabel({ type: 'psychologist' })).toBeNull();
  });

  it('সার্ভারের typeLabel আগে (সুইচ বন্ধ বা পদ্ধতি না জানলেও); সুইচ বন্ধে নিজের হিসাব নয়', () => {
    setFeaturesForTest({});
    expect(practitionerLabel({ type: 'doctor', system: 'homeopathy' })).toBeNull();
    expect(practitionerLabel({ type: 'doctor', typeLabel: 'ইউনানি চিকিৎসক', typeLabelEn: 'Unani practitioner' })).toBe('ইউনানি চিকিৎসক');
    expect(practitionerLabel({ type: 'doctor', typeLabel: 'ডাক্তার' })).toBeNull();
  });

  it('তালিকা আর অ্যাপয়েন্টমেন্টে সার্ভারের typeLabel থাকে', () => {
    expect(parseProvider({ uid: 'p', type: 'doctor', system: 'unani', typeLabel: 'ইউনানি চিকিৎসক', typeLabelEn: 'Unani practitioner' }).typeLabel).toBe('ইউনানি চিকিৎসক');
    expect(parseAppointment({ id: 'a', type: 'doctor', typeLabel: 'আয়ুর্বেদিক চিকিৎসক' }).typeLabel).toBe('আয়ুর্বেদিক চিকিৎসক');
    expect('typeLabel' in parseProvider({ uid: 'p', type: 'doctor' })).toBe(false);
  });

  it('প্রেসক্রিপশনের মাথা (কাগজ আর প্রিন্ট/PDF): "হোমিওপ্যাথিক চিকিৎসক"; সুইচ বন্ধে আগের মতো পদ্ধতি', () => {
    const rx: Rx = {
      id: 'r1', code: 'ABCD2345EF', status: 'active', createdAt: Date.UTC(2026, 9, 23), doctorUid: 'd1',
      doctor: { name: 'আনোয়ার হোসেন', degrees: 'DHMS', regNo: 'H-1', system: 'homeopathy', typeLabel: 'হোমিওপ্যাথিক চিকিৎসক' },
      doctorType: 'doctor', patient: { name: 'রহিম', age: 40, gender: 'male' },
      complaints: '', findings: {}, diagnosis: '', medicines: [], tests: [], testPlace: '', advice: '', followUpDate: null, followUpBookDate: null, followUpNote: '', chamberIndex: null,
    };
    expect(mount(RxPaper, { props: { rx } }).get('[data-testid=paper-system]').text()).toBe('হোমিওপ্যাথিক চিকিৎসক');
    setFeaturesForTest({ practice_systems: true });
    const old = { ...rx, doctor: { ...rx.doctor, typeLabel: 'ডাক্তার' } };
    expect(mount(RxPaper, { props: { rx: old } }).get('[data-testid=paper-system]').text()).toBe('হোমিওপ্যাথি');
  });
});
