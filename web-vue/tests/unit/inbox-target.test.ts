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
});
