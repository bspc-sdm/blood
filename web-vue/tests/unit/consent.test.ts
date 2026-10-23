// স্বাস্থ্য তথ্যের সম্মতি (lib/consent.ts, backend/docs/CONSENT.md): দেওয়া (দুটো সম্মতি, ভার্সন, উৎস), তুলে নেওয়া,
// স্বাস্থ্যের পাতায় একবার জিজ্ঞেস ("এখন না"-র পর এই ভার্সনে আর নয়; কেয়ারগিভার মোডে নয়; সুইচ বন্ধে নয়), 428-এর হ্যান্ডলার
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { call, ServerError, handler } = vi.hoisted(() => {
  class ServerError extends Error {
    constructor(message: string, public status: number, public code?: string) {
      super(message);
    }
  }
  return { call: vi.fn(), ServerError, handler: { f: null as any, declined: null as any } };
});
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  ServerError,
  setConsentHandler: (f: unknown, d: unknown) => {
    handler.f = f;
    handler.declined = d;
  },
}));
vi.mock('@/lib/firebase', () => ({ auth: { currentUser: { uid: 'u1' } } }));
const feat = vi.hoisted(() => ({ on: true }));
vi.mock('@/lib/features', () => ({ featureOn: (k: string) => k === 'health_consent' && feat.on }));
const care = vi.hoisted(() => ({ uid: null as string | null }));
vi.mock('@/lib/care', () => ({ careUid: () => care.uid }));

import { answerConsent, askOnHealthSection, consent, consentSource, grantConsent, initConsent, withdrawConsent } from '@/lib/consent';

const status = (o: Record<string, unknown> = {}) => ({
  on: true, version: 'v1', current: false, ask: true, needed: ['health_data', 'data_abroad'], grantedAt: null,
  scopes: { health_data: { state: 'none' }, data_abroad: { state: 'none' } },
  text: { title: 'স্বাস্থ্য তথ্য রাখার আগে আপনার সম্মতি', agree: 'রাজি আছি', later: 'এখন না', sections: [], scopes: {} },
  ...o,
});
const tick = () => new Promise((r) => setTimeout(r, 0));

describe('consent', () => {
  beforeEach(() => {
    call.mockReset();
    feat.on = true;
    care.uid = null;
    localStorage.clear();
    if (consent.prompt) answerConsent(false);
  });

  it('grant: both scopes in one go, with the version shown and the source', async () => {
    call.mockResolvedValueOnce(status()).mockResolvedValueOnce(status({ current: true, ask: false, needed: [] }));
    await grantConsent();
    expect(call).toHaveBeenNthCalledWith(1, 'consent_status');
    expect(call).toHaveBeenNthCalledWith(2, 'consent_grant', { scopes: ['health_data', 'data_abroad'], version: 'v1', source: 'web' });
    expect(consent.status?.current).toBe(true);
    expect(consentSource()).toBe('web');
  });

  it('grant on an outdated screen: the new text is loaded', async () => {
    // the screen still shows v1 (from the test above)
    call.mockRejectedValueOnce(new ServerError('changed', 409, 'consent_outdated')).mockResolvedValueOnce(status({ version: 'v2' }));
    await expect(grantConsent()).rejects.toThrow('changed');
    expect(consent.status?.version).toBe('v2');
  });

  it('withdraw: all scopes by default, never deletes anything (only the call)', async () => {
    call.mockResolvedValueOnce(status({ scopes: { health_data: { state: 'withdrawn' }, data_abroad: { state: 'withdrawn' } } }));
    await withdrawConsent();
    expect(call).toHaveBeenCalledWith('consent_withdraw', { scope: 'all', source: 'web' });
  });

  it('health section: asks once; after "not now" not again for this text version', async () => {
    call.mockResolvedValue(status());
    const first = askOnHealthSection();
    await tick();
    expect(consent.prompt?.reason).toBe('section');
    answerConsent(false);
    await first;
    await askOnHealthSection();
    expect(consent.prompt).toBeNull();
    // a new text version: asked again
    call.mockResolvedValue(status({ version: 'v2' }));
    const again = askOnHealthSection();
    await tick();
    expect(consent.prompt?.reason).toBe('section');
    answerConsent(true);
    await again;
  });

  it('health section: not when the switch is off, not in caregiver mode, not when consent is current', async () => {
    feat.on = false;
    await askOnHealthSection();
    expect(call).not.toHaveBeenCalled();
    feat.on = true;
    care.uid = 'patient1';
    await askOnHealthSection();
    expect(call).not.toHaveBeenCalled();
    care.uid = null;
    call.mockResolvedValue(status({ ask: false, current: true, needed: [] }));
    await askOnHealthSection();
    expect(consent.prompt).toBeNull();
  });

  it('428 handler: loads the text, shows the screen, answers with the choice; caregivers never see it', async () => {
    initConsent();
    expect(handler.declined()).toMatch(/সম্মতি|consent/);
    call.mockResolvedValue(status());
    const p = handler.f({ scopes: ['health_data'], version: 'v1', caregiver: false, message: '' });
    await tick();
    expect(consent.prompt?.reason).toBe('gate');
    answerConsent(true);
    expect(await p).toBe(true);
    expect(await handler.f({ scopes: [], version: 'v1', caregiver: true, message: '' })).toBe(false);
  });
});
