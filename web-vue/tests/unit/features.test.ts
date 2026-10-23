// ফিচার সুইচ (lib/features.ts): featureOn reactive, refreshFeatures (মিনিটে একবার, force), রাখা অবস্থা, গার্ড,
// feature_off চেনা, SSE ব্লকের নাম পড়া
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed } from 'vue';

const { call, ServerError } = vi.hoisted(() => {
  class ServerError extends Error {
    constructor(message: string, public status: number, public code?: string) {
      super(message);
    }
  }
  return { call: vi.fn(), ServerError };
});
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  onFeatureOff: () => () => {},
  ServerError,
}));
vi.mock('@/lib/firebase', () => ({ auth: { currentUser: null } }));

import { FEATURE_KEYS, featureGuard, featureSoonText, featureOn, features, isFeatureOff, parseEventName, refreshFeatures } from '@/lib/features';
import { toastMessage } from '@/ui/toast';

const allOff = () => Object.fromEntries(FEATURE_KEYS.map((k) => [k, false]));

describe('features', () => {
  beforeEach(() => {
    call.mockReset();
  });

  it('starts off, follows the server, and is reactive', async () => {
    expect(featureOn('chat')).toBe(false);
    const c = computed(() => featureOn('chat'));
    call.mockResolvedValueOnce({ keys: { ...allOff(), chat: true }, v: 3 });
    await refreshFeatures({ force: true });
    expect(call).toHaveBeenCalledWith('features');
    expect(c.value).toBe(true);
    expect(featureOn('calls_audio')).toBe(false);
    expect(featureOn('nope')).toBe(false);
    expect(features.v).toBe(3);
    // রাখা হয়েছে (অফলাইনে চলে)
    expect(JSON.parse(localStorage.getItem('lf.features') || '{}').keys.chat).toBe(true);
  });

  it('asks at most once a minute unless forced', async () => {
    call.mockResolvedValue({ keys: allOff(), v: 4 });
    await refreshFeatures({ force: true });
    await refreshFeatures(false);
    await refreshFeatures({ force: false });
    expect(call).toHaveBeenCalledTimes(1);
    await refreshFeatures({ force: true });
    expect(call).toHaveBeenCalledTimes(2);
  });

  it('keeps the last known keys when the server fails', async () => {
    call.mockResolvedValueOnce({ keys: { ...allOff(), chat: true }, v: 5 });
    await refreshFeatures({ force: true });
    call.mockRejectedValueOnce(new Error('offline'));
    await refreshFeatures({ force: true });
    expect(featureOn('chat')).toBe(true);
  });

  it('guard: on → enter; off → toast "শিগগির আসছে" and the fallback', async () => {
    call.mockResolvedValueOnce({ keys: { ...allOff(), chat: true }, v: 6 });
    await refreshFeatures({ force: true });
    const g = featureGuard('chat') as () => Promise<unknown>;
    expect(await g()).toBe(true);
    const off = featureGuard('calls_video', '/profile') as () => Promise<unknown>;
    expect(await off()).toBe('/profile');
    expect(toastMessage.value).toBe(featureSoonText());
    expect(['এই সুবিধা শিগগির আসছে', 'This feature is coming soon']).toContain(toastMessage.value);
  });

  it('knows the server feature_off error', () => {
    expect(isFeatureOff(new ServerError('এই সুবিধা এখনো চালু হয়নি', 404, 'feature_off'))).toBe(true);
    expect(isFeatureOff(new ServerError('x', 404, 'other'))).toBe(false);
    expect(isFeatureOff(new Error('x'))).toBe(false);
  });

  it('reads the SSE event name', () => {
    expect(parseEventName('event:features_changed\ndata:{"v":7}')).toBe('features_changed');
    expect(parseEventName(': ping')).toBe(null);
  });
});
