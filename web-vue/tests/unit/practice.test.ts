// চিকিৎসা পদ্ধতি আর নতুন পেশাজীবী (lib/practice.ts, backend/docs/PRACTICE.md): সুইচ বন্ধ = আগের মতো (সবাই এলোপ্যাথি,
// নতুন ধরন নেই), লেবেল, বিশেষজ্ঞতার তালিকা, ব্যাজের কাগজ, প্রেসক্রিপশনের নিয়ম (সার্ভারের rxRules আগে), কাগজের মাথা,
// নিজে লেখা ওষুধের সারি, কেয়ারগিভারকে দেখানোর সুইচ।
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils';

vi.mock('@/lib/api', async (orig) => {
  const m = await orig<typeof import('@/lib/api')>();
  return { ...m, call: vi.fn(async () => ({ ok: true })) };
});
import { call } from '@/lib/api';
import { setFeaturesForTest } from '@/lib/features';
import {
  ALT_SPEC, PHYSIO_HOME, PHYSIO_SPEC, PSYCH_SPEC, newTypesOn, practiceDocs, regBody, rxKind, rxKindOf, rxPharmacyOk, systemOf, systemTag, writesRx,
} from '@/lib/practice';
import { badgeIcon, badgeLabel, chamberTypes, homeServicesOf, isHomeType, parseAppointment, parseProvider, specialtiesOf, specialtyLabel } from '@/sections/appointment/model';
import { badgeDocs } from '@/sections/health/badge';
import { badgeChoices } from '@/sections/health/common';
import RxPaper from '@/sections/health/RxPaper.vue';
import MedRowView from '@/sections/rx/doctor/MedRowView.vue';
import SystemChips from '@/sections/practice/SystemChips.vue';
import PsychShareSwitch from '@/sections/practice/PsychShareSwitch.vue';
import type { Rx } from '@/sections/health/rx';

const ALL = { practice_systems: true, pro_physio: true, pro_psychologist: true };
const OFF = {};

const rx = (doctor: Record<string, unknown>, o: Partial<Rx> = {}): Rx => ({
  id: 'r1', code: 'ABCD2345EF', status: 'active', createdAt: Date.UTC(2026, 9, 23), doctorUid: 'd1',
  doctor: { name: 'মো. করিম উদ্দিন', degrees: 'DHMS', regNo: 'H-1234', ...doctor },
  patient: { name: 'রহিম', age: 40, gender: 'male' },
  complaints: '', findings: {}, diagnosis: '', medicines: [{ name: 'Nux Vomica 30', dose: '৪ ফোঁটা দিনে ৩ বার', days: 7 }], tests: [], testPlace: '',
  advice: 'ঠান্ডা পানি কম', followUpDate: null, followUpBookDate: null, followUpNote: '', chamberIndex: null, ...o,
});

describe('সুইচ বন্ধ: আগের মতো', () => {
  beforeEach(() => setFeaturesForTest(OFF));

  it('সবাই এলোপ্যাথি, কোনো লেবেল নেই, নতুন ধরন নেই', () => {
    expect(systemOf('homeopathy')).toBe('allopathy');
    expect(systemTag('doctor', 'homeopathy')).toBeNull();
    expect(newTypesOn()).toEqual([]);
    expect(chamberTypes()).toEqual(['doctor', 'nutritionist', 'trainer']);
    expect(badgeChoices()).toEqual(['doctor', 'nurse', 'nutritionist', 'trainer', 'expert']);
    expect(writesRx('physio')).toBe(false);
    expect(writesRx('doctor')).toBe(true);
  });

  it('বিশেষজ্ঞতা আর কাগজ আগের তালিকা', () => {
    expect(specialtiesOf('doctor', 'homeopathy')).toEqual(specialtiesOf('doctor'));
    expect(specialtiesOf('doctor')).toContain('medicine');
    expect(badgeDocs('doctor', 'homeopathy').map((d) => [d.type, d.required])).toEqual([['cert', false], ['idcard', false]]);
    expect(rxKind('doctor', 'homeopathy')).toBe('full');
  });

  it('প্রেসক্রিপশনের কাগজ: "BMDC", পদ্ধতির লেবেল নেই', () => {
    const w = mount(RxPaper, { props: { rx: rx({ system: 'homeopathy', regNo: 'A-1' }, { medicines: [] }) } });
    expect(w.text()).toContain('BMDC A-1');
    expect(w.find('[data-testid=paper-system]').exists()).toBe(false);
  });

  it('সার্ভারের rxRules থাকলে সুইচ বন্ধেও মানা (ফার্মেসি নেই)', () => {
    expect(rxPharmacyOk('doctor', 'homeopathy')).toBe(true);
    expect(rxPharmacyOk('doctor', null, { medicines: true, freeText: true, pharmacy: false })).toBe(false);
    expect(rxKindOf('doctor', null, { medicines: true, freeText: true })).toBe('free');
  });
});

describe('সুইচ চালু', () => {
  beforeEach(() => setFeaturesForTest(ALL));
  afterEach(() => setFeaturesForTest(OFF));

  it('পদ্ধতির লেবেল (শুধু ডাক্তার), নিবন্ধনের প্রতিষ্ঠান', () => {
    expect(systemTag('doctor', 'homeopathy')).toBe('হোমিওপ্যাথি');
    expect(systemTag('doctor', null)).toBe('এলোপ্যাথি');
    expect(systemTag(undefined, 'ayurveda')).toBe('আয়ুর্বেদ');
    expect(systemTag('physio', 'homeopathy')).toBeNull();
    expect(regBody('unani')).toBe('বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড');
    expect(regBody('allopathy')).toBe('BMDC');
  });

  it('নতুন ধরন: ট্যাব, ব্যাজ, নাম, বাসায় সেবা', () => {
    expect(chamberTypes()).toEqual(['doctor', 'nutritionist', 'trainer', 'physio', 'psychologist']);
    expect(badgeChoices()).toEqual(['doctor', 'nurse', 'nutritionist', 'trainer', 'physio', 'psychologist', 'expert']);
    expect(badgeLabel('physio')).toBe('ফিজিওথেরাপিস্ট');
    expect(badgeLabel('psychologist')).toBe('ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর');
    expect(badgeIcon('psychologist')).toBe('forum_outline');
    expect(isHomeType('physio')).toBe(true);
    expect(isHomeType('psychologist')).toBe(false);
    expect(homeServicesOf('physio')).toEqual(PHYSIO_HOME);
  });

  it('বিশেষজ্ঞতা: পদ্ধতি আর পেশাপ্রতি (সার্ভারের catalog-defaults কোড)', () => {
    expect(specialtiesOf('doctor', 'homeopathy')).toEqual(ALT_SPEC);
    expect(specialtiesOf('doctor', 'allopathy')).toContain('medicine');
    expect(specialtiesOf('physio')).toEqual(PHYSIO_SPEC);
    expect(specialtiesOf('psychologist')).toEqual(PSYCH_SPEC);
    expect(specialtyLabel('alt_general')).toBe('সাধারণ');
    expect(specialtyLabel('psy_trauma')).toBe('ট্রমা');
  });

  it('ব্যাজের কাগজ: অ-এলোপ্যাথির সনদ লাগবেই; ফিজিও/সাইকোলজিস্টের ডিগ্রি + সদস্যপত্র (থাকলে)', () => {
    expect(badgeDocs('doctor', 'homeopathy')[0]).toMatchObject({ type: 'cert', required: true, label: 'বাংলাদেশ হোমিওপ্যাথিক বোর্ড-এর নিবন্ধন সনদ' });
    expect(badgeDocs('doctor', 'allopathy')[0]).toMatchObject({ type: 'cert', required: false });
    expect(practiceDocs('physio', null)!.map((d) => [d.type, d.required])).toEqual([['cert', true], ['member', false], ['idcard', false]]);
    expect(practiceDocs('psychologist', null)![1].label).toBe('বাংলাদেশ ক্লিনিক্যাল সাইকোলজি সোসাইটির সদস্যপত্র');
  });

  it('প্রেসক্রিপশনের ধরন', () => {
    expect(rxKind('doctor', 'allopathy')).toBe('full');
    expect(rxKind('doctor', 'unani')).toBe('free');
    expect(rxKind('physio', null)).toBe('advice');
    expect(rxKind('psychologist', null)).toBe('notes');
    expect(rxPharmacyOk('doctor', 'unani')).toBe(false);
    expect(rxKindOf('psychologist', null, { medicines: false, notice: 'x' })).toBe('notes');
    expect(rxKindOf('physio', null, { medicines: false })).toBe('advice');
    expect(rxKindOf('doctor', 'homeopathy', { medicines: true, freeText: false })).toBe('full');
  });

  it('সার্ভারের উত্তর থেকে পদ্ধতি (পুরোনো সার্ভারে নেই)', () => {
    expect(parseProvider({ uid: 'p', type: 'doctor', system: 'unani' }).system).toBe('unani');
    expect('system' in parseProvider({ uid: 'p', type: 'doctor' })).toBe(false);
    expect(parseAppointment({ id: 'a', type: 'psychologist', system: null }).system).toBeUndefined();
  });

  it('কাগজ: নামের নিচে পদ্ধতি, প্রতিষ্ঠানসহ নিবন্ধন, "ডা." নিজে বসায় না', () => {
    const w = mount(RxPaper, { props: { rx: rx({ system: 'homeopathy' }) } });
    expect(w.get('[data-testid=paper-system]').text()).toBe('হোমিওপ্যাথি');
    expect(w.text()).toContain('বাংলাদেশ হোমিওপ্যাথিক বোর্ড H-1234');
    expect(w.get('h3').text()).toBe('মো. করিম উদ্দিন');
    expect(w.text()).not.toContain('ডা.');
  });

  it('কাগজ: সাইকোলজিস্ট — সেশন নোট, সাইকিয়াট্রিস্টের কথা; ফিজিও — পরামর্শ ও ব্যায়াম', () => {
    const p = mount(RxPaper, { props: { rx: rx({ type: 'psychologist', regNo: 'P-9' }, { doctorType: 'psychologist', medicines: [], advice: 'ঘুমের রুটিন' }) } });
    expect(p.get('[data-testid=paper-system]').text()).toBe('ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর');
    expect(p.text()).toContain('সেশন নোট ও পরামর্শ');
    expect(p.get('[data-testid=paper-psychiatrist]').text()).toBe('ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান');
    expect(p.text()).not.toContain('BMDC');
    const f = mount(RxPaper, { props: { rx: rx({}, { doctorType: 'physio', medicines: [], advice: 'হাঁটা' }) } });
    expect(f.text()).toContain('পরামর্শ ও ব্যায়াম');
    expect(f.find('[data-testid=paper-psychiatrist]').exists()).toBe(false);
  });
});

describe('উপাদান', () => {
  beforeEach(() => setFeaturesForTest(ALL));
  afterEach(() => setFeaturesForTest(OFF));

  it('SystemChips: সব + চার পদ্ধতি, বাছলে জানায়', async () => {
    const w = mount(SystemChips, { props: { value: null } });
    const b = w.findAll('button');
    expect(b.map((x) => x.text())).toEqual(['সব', 'এলোপ্যাথি', 'হোমিওপ্যাথি', 'ইউনানি', 'আয়ুর্বেদ']);
    expect(b[0].attributes('aria-pressed')).toBe('true');
    await b[2].trigger('click');
    await b[0].trigger('click');
    expect(w.emitted('change')).toEqual([['homeopathy'], [null]]);
    const c = mount(SystemChips, { props: { value: 'unani', counts: [{ code: 'allopathy', n: 12 }, { code: 'unani', n: 2 }] } });
    expect(c.findAll('button').map((x) => x.text())).toEqual(['সব', 'এলোপ্যাথি (১২)', 'হোমিওপ্যাথি', 'ইউনানি (২)', 'আয়ুর্বেদ']);
  });

  it('MedRowView free: ধরন/মাত্রা/খাবার নেই, ওষুধের তালিকার খোঁজা নেই, নাম ২০০ অক্ষর', async () => {
    const m = { key: 1, name: '', form: '', strength: '', dose: '', timing: '', days: '৭', note: '', picked: false };
    const w = mount(MedRowView, { props: { m, n: 1, last: true, free: true } });
    expect(w.find('[data-f=form]').exists()).toBe(false);
    expect(w.find('[data-f=strength]').exists()).toBe(false);
    expect(w.find('[data-f=timing]').exists()).toBe(false);
    expect(w.find('[role=combobox]').exists()).toBe(false);
    expect(w.classes()).toContain('rxd-free');
    await w.get('[data-f=name]').setValue('x'.repeat(250));
    expect((w.emitted('change')![0][0] as { name: string }).name).toHaveLength(200);
    const full = mount(MedRowView, { props: { m, n: 1, last: true } });
    expect(full.find('[role=combobox]').exists()).toBe(true);
    expect(full.find('[data-f=form]').exists()).toBe(true);
  });

  it('PsychShareSwitch: care_share_psych {caretakerUid, share}', async () => {
    const w = mount(PsychShareSwitch, { props: { caretakerUid: 'c1', shared: false }, global: { stubs: { RouterLink: RouterLinkStub } } });
    const sw = w.get('input[role=switch]');
    expect((sw.element as HTMLInputElement).checked).toBe(false);
    await sw.setValue(true);
    await flushPromises();
    expect(call).toHaveBeenCalledWith('care_share_psych', { caretakerUid: 'c1', share: true });
    expect(w.emitted('changed')).toHaveLength(1);
  });
});
