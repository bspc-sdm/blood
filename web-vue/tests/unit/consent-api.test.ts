// সম্মতির পর আবার পাঠানো (lib/api.ts, backend/docs/CONSENT.md): 428 consent_required → পর্দা → "রাজি আছি" হলে একই কাজ আবার;
// "এখন না" → কাজ হয় না (consent_declined); কেয়ারগিভার → পর্দা নয়, সার্ভারের "রোগীকে নিজে একবার সম্মতি দিতে হবে"
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/firebase', () => ({ auth: { currentUser: { uid: 'u1', getIdToken: async () => 'tok' } } }));

import { call, ConsentDeclined, ServerError, setConsentHandler } from '@/lib/api';

const NEED = { error: 'এটা করতে আগে স্বাস্থ্য তথ্য রাখার সম্মতি দিন', code: 'consent_required', scopes: ['health_data', 'data_abroad'], version: '2026-10-23.1', caregiver: false };
const res = (status: number, body: unknown) => ({ ok: status < 300, status, json: async () => body }) as unknown as Response;

describe('428 consent_required', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    setConsentHandler(null);
    vi.unstubAllGlobals();
  });

  it('agree → the same request is sent again and its answer returned', async () => {
    fetchMock.mockResolvedValueOnce(res(428, NEED)).mockResolvedValueOnce(res(200, { ok: true, added: 1 }));
    const asked = vi.fn(async () => true);
    setConsentHandler(asked, () => 'declined');
    const r = await call('rx_claim', { code: 'K7M2Q9XA4T', phone: '01712345678' });
    expect(r).toEqual({ ok: true, added: 1 });
    expect(asked).toHaveBeenCalledWith({ scopes: ['health_data', 'data_abroad'], version: '2026-10-23.1', caregiver: false, message: NEED.error });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const bodies = fetchMock.mock.calls.map((c) => JSON.parse(c[1].body));
    expect(bodies[0]).toEqual(bodies[1]);
    expect(bodies[1].action).toBe('rx_claim');
  });

  it('"not now" → nothing is sent again; the page gets a plain message', async () => {
    fetchMock.mockResolvedValueOnce(res(428, NEED));
    setConsentHandler(async () => false, () => 'সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।');
    const e = await call('rx_claim', {}).catch((x) => x);
    expect(e).toBeInstanceOf(ConsentDeclined);
    expect(e.code).toBe('consent_declined');
    expect(e.message).toBe('সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a caregiver is never shown the consent screen', async () => {
    fetchMock.mockResolvedValueOnce(res(428, { ...NEED, error: 'রোগীকে নিজে একবার সম্মতি দিতে হবে', caregiver: true }));
    const asked = vi.fn(async () => true);
    setConsentHandler(asked, () => 'x');
    const e = await call('rx_send_pharmacy', { asUid: 'patient1' }).catch((x) => x);
    expect(asked).not.toHaveBeenCalled();
    expect(e).toBeInstanceOf(ServerError);
    expect(e.message).toBe('রোগীকে নিজে একবার সম্মতি দিতে হবে');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('asks only once per request (a second 428 is passed on)', async () => {
    fetchMock.mockResolvedValue(res(428, NEED));
    const asked = vi.fn(async () => true);
    setConsentHandler(asked, () => 'x');
    const e = await call('rx_claim', {}).catch((x) => x);
    expect(asked).toHaveBeenCalledTimes(1);
    expect(e.code).toBe('consent_required');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('other errors are untouched', async () => {
    fetchMock.mockResolvedValueOnce(res(400, { error: 'কোড আর ফোন নম্বর মেলেনি' }));
    const asked = vi.fn(async () => true);
    setConsentHandler(asked, () => 'x');
    const e = await call('rx_claim', {}).catch((x) => x);
    expect(asked).not.toHaveBeenCalled();
    expect([e.status, e.message]).toEqual([400, 'কোড আর ফোন নম্বর মেলেনি']);
  });
});
