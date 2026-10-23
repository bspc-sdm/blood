import { describe, expect, it } from 'vitest';
import { inboxTarget } from '@/sections/account/util';
import type { WorkSummary } from '@/lib/session';

const work: WorkSummary = { ambulances: [], pharmacy: null, bloodBanks: [], lab: null, professional: null, assisting: [] };

describe('inboxTarget (নোটিফিকেশন → পাতা, অ্যাপের openFromData)', () => {
  it('amb_req_mine: সেই অ্যাম্বুলেন্স ডাকার অপেক্ষার পাতা (হোম নয়)', () => {
    expect(inboxTarget({ type: 'amb_req_mine', reqId: 'UacV8cCPpbtMdamaUPbG' }, null, work)).toBe('/services/ambulance/call/UacV8cCPpbtMdamaUPbG');
    expect(inboxTarget({ type: 'amb_req_mine' }, null, work)).toBe('/services/ambulance');
  });
  it('অন্য ধরন আগের মতোই', () => {
    expect(inboxTarget({ type: 'response', requestId: 'r1' }, null, work)).toBe('/my-requests/r1');
    expect(inboxTarget({ type: 'services', kind: 'ambulance' }, null, work)).toBe('/work/ambulance');
    expect(inboxTarget({ type: 'unknown' }, null, work)).toBe('/');
  });
  it('আমার কাজ: ডাক্তারের নতুন বুকিং → বুকিং ট্যাব; আজকের খবর → আজকের রোগী; রিপোর্ট, ল্যাব, অ্যাম্বুলেন্স ঠিক জায়গায়', () => {
    const doc: WorkSummary = { ...work, professional: 'doctor' };
    // নতুন অনুরোধ / "অনুরোধ অপেক্ষায়" (cat business) → /work/pro/bookings (আজকের রোগী নয়)
    expect(inboxTarget({ type: 'appointment', role: 'provider', cat: 'business' }, null, doc)).toBe('/work/pro/bookings');
    // অনলাইনের ফি পাঠিয়েছেন — যাচাই বুকিংয়ে
    expect(inboxTarget({ type: 'appointment', role: 'provider', cat: 'appointments', id: 'a1', payRequestId: 'p1' }, null, doc)).toBe('/work/pro/bookings');
    // আজকের অ্যাপয়েন্টমেন্ট, দেরি, বাতিল → আজকের রোগী
    expect(inboxTarget({ type: 'appointment', role: 'provider', cat: 'appointments' }, null, doc)).toBe('/work/pro');
    // শুধু সহকারী → ডাক্তারের ডেস্ক
    expect(inboxTarget({ type: 'appointment', role: 'provider', cat: 'business' }, null, { ...work, assisting: [{ doctorUid: 'd1', name: 'ডা. ক', degrees: '' }] })).toBe('/work/rx');
    // রোগী নিজে
    expect(inboxTarget({ type: 'appointment', role: 'patient', id: 'a1' }, null, doc)).toBe('/my-appointments');
    // ল্যাবের রিপোর্ট দেখার অপেক্ষায় → প্রেসক্রিপশনের রিপোর্ট পাতা
    expect(inboxTarget({ type: 'rx_review', rxId: 'r1', labOrderId: 'l1' }, null, doc)).toBe('/work/rx/reviews');
    // প্যাথলজি: সেই অনুরোধ; ফার্মেসি: অর্ডার (নতুন ভাগে /work/pharmacy → /work/pharmacy/orders); ওষুধ খোঁজা নিজের ভাগে
    expect(inboxTarget({ type: 'rx_lab', id: 'lo1', rxId: 'r1' }, null, work)).toBe('/work/lab/orders/lo1');
    expect(inboxTarget({ type: 'rx_lab' }, null, work)).toBe('/work/lab');
    expect(inboxTarget({ type: 'rx_pharmacy', id: 'o1' }, null, work)).toBe('/work/pharmacy');
    expect(inboxTarget({ type: 'med_find', findId: 'f1' }, null, work)).toBe('/work/pharmacy/finds');
    // অ্যাম্বুলেন্স: "এখনো খালি আছেন?" → আমার কাজ (খালি/ট্রিপে এক জায়গায়); রোগীর ডাক → অ্যাম্বুলেন্সের পাতা (ডাক)
    expect(inboxTarget({ type: 'services', kind: 'ambulance', ambulanceId: 'amb1' }, null, work)).toBe('/work');
    expect(inboxTarget({ type: 'services', kind: 'ambulance', sub: 'amb_req', reqId: 'q1', cat: 'business' }, null, work)).toBe('/work/ambulance');
  });
});
