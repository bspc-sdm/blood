// অ্যাকাউন্ট মোছা আর সাময়িক বন্ধ: account.ts (profile_get-এর account পড়া, ৩০ দিনের তারিখ), মোছার টিক-গেট
// (DeleteAccountConfirm: টিক ছাড়া বোতাম বন্ধ, ack:true, আগে জানিয়ে তারপর লগআউট), আটকানো পর্দা (AccountPaused: আবার চালু, ভুল, লগআউট),
// আর api.ts-এর 423 `account_deactivated` শোনা।
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const call = vi.fn();
vi.mock('@/lib/api', async (orig) => {
  const real = await orig<typeof import('@/lib/api')>();
  return { ...real, call: (...a: unknown[]) => call(...a) };
});
vi.mock('@/lib/firebase', () => ({ auth: { currentUser: null } }));

const session = {
  email: 'arif@example.com',
  account: { state: 'deactivated', deactivatedAt: Date.UTC(2026, 9, 1, 6) } as Record<string, unknown> | null,
  signOut: vi.fn(async () => {}),
  reactivate: vi.fn(async () => {}),
};
vi.mock('@/lib/session', () => ({ useSession: () => session }));

import { DELETE_GRACE_DAYS, deleteDateFrom, parseAccount } from '@/lib/account';
import { onAccountDeactivated, onAccountDisabled, postPublic } from '@/lib/api';
import DeleteAccountConfirm from '@/sections/account/DeleteAccountConfirm.vue';
import AccountPaused from '@/shell/AccountPaused.vue';
import { toastMessage } from '@/ui/toast';

beforeEach(() => {
  localStorage.setItem('lf.lang', 'bn');
  toastMessage.value = null;
  session.signOut.mockClear();
  session.reactivate.mockReset();
  session.reactivate.mockImplementation(async () => {});
});
afterEach(() => call.mockReset());

describe('account.ts', () => {
  it('parseAccount: তিন অবস্থা, তারিখ সংখ্যা বা ISO; অচেনা হলে null', () => {
    expect(parseAccount({ state: 'active' })).toEqual({ state: 'active' });
    expect(parseAccount({ state: 'deactivated', deactivatedAt: 1790000000000 })).toEqual({ state: 'deactivated', deactivatedAt: 1790000000000 });
    expect(parseAccount({ state: 'deleting', deletionRequestedAt: '2026-10-01T00:00:00Z', deletesAt: '1792540800000' })).toEqual({
      state: 'deleting',
      deletionRequestedAt: Date.parse('2026-10-01T00:00:00Z'),
      deletesAt: 1792540800000,
    });
    expect(parseAccount({ state: 'deactivated', deactivatedAt: 'কাল' })).toEqual({ state: 'deactivated' });
    expect(parseAccount({ state: 'gone' })).toBeNull();
    expect(parseAccount(null)).toBeNull();
    expect(parseAccount('active')).toBeNull();
  });
  it('deleteDateFrom: আজ + ৩০ দিন', () => {
    expect(DELETE_GRACE_DAYS).toBe(30);
    expect(deleteDateFrom(Date.UTC(2026, 9, 23)) - Date.UTC(2026, 9, 23)).toBe(30 * 86400_000);
  });
});

describe('DeleteAccountConfirm', () => {
  it('টিক না দিলে লাল বোতাম বন্ধ, কিছু যায় না; টিক দিলে delete_account {ack:true}, আগে deleted তারপর লগআউট', async () => {
    const order: string[] = [];
    call.mockImplementation(async (action: string) => {
      order.push(action);
      return { ok: true, deleteAt: 1792540800000 };
    });
    session.signOut.mockImplementation(async () => {
      order.push('signOut');
    });
    const w = mount(DeleteAccountConfirm, { attrs: { onDeleted: () => order.push('deleted') } });
    expect(w.text()).toContain('আমি বুঝেছি, ৩০ দিন পর সব তথ্য চিরতরে মুছে যাবে।');
    const btn = w.get('button');
    expect(btn.text()).toBe('অ্যাকাউন্ট মুছুন');
    expect(btn.classes()).toContain('danger');
    expect(btn.attributes('disabled')).toBeDefined();
    await btn.trigger('click');
    expect(call).not.toHaveBeenCalled();

    await w.get('input[type="checkbox"]').setValue(true);
    expect(w.get('button').attributes('disabled')).toBeUndefined();
    await w.get('button').trigger('click');
    await flushPromises();
    expect(call).toHaveBeenCalledWith('delete_account', { ack: true });
    expect(w.emitted('deleted')?.[0]).toEqual([1792540800000, 'arif@example.com']);
    expect(order).toEqual(['delete_account', 'deleted', 'signOut']);
  });

  it('সার্ভার না পারলে ভুল দেখায়, লগআউট নয়', async () => {
    call.mockRejectedValue(new Error('সার্ভারে সমস্যা'));
    const w = mount(DeleteAccountConfirm);
    await w.get('input[type="checkbox"]').setValue(true);
    await w.get('button').trigger('click');
    await flushPromises();
    expect(w.get('[role="alert"]').text()).toBe('সার্ভারে সমস্যা');
    expect(w.emitted('deleted')).toBeUndefined();
    expect(session.signOut).not.toHaveBeenCalled();
  });
});

describe('AccountPaused', () => {
  it('শিরোনাম, কবে থেকে বন্ধ; "আবার চালু করুন" → reactivate আর টোস্ট', async () => {
    const w = mount(AccountPaused);
    expect(w.get('h1').text()).toBe('আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে');
    expect(w.text()).toContain('১ অক্টোবর ২০২৬ থেকে বন্ধ।');
    const [on, out] = w.findAll('button');
    expect(on.text()).toBe('আবার চালু করুন');
    expect(out.text()).toBe('লগআউট');
    await on.trigger('click');
    await flushPromises();
    expect(session.reactivate).toHaveBeenCalledTimes(1);
    expect(toastMessage.value).toBe('অ্যাকাউন্ট আবার চালু হয়েছে।');
  });

  it('আবার চালু না হলে ভুল; "লগআউট" signOut', async () => {
    session.reactivate.mockRejectedValue(new Error('আবার চেষ্টা করুন'));
    const w = mount(AccountPaused);
    await w.findAll('button')[0].trigger('click');
    await flushPromises();
    expect(w.get('[role="alert"]').text()).toBe('আবার চেষ্টা করুন');
    await w.findAll('button')[1].trigger('click');
    await flushPromises();
    expect(session.signOut).toHaveBeenCalledTimes(1);
  });
});

describe('api.ts: 423 account_deactivated', () => {
  const reply = (status: number, json: unknown) =>
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(json), { status, headers: { 'content-type': 'application/json' } }));

  it('423 + code → শ্রোতা ডাকে (অনুরোধ কখন গিয়েছিল সহ), ভুলও ছোড়ে', async () => {
    const seen: number[] = [];
    const off = onAccountDeactivated((t) => seen.push(t));
    const before = Date.now();
    reply(423, { error: 'paused', code: 'account_deactivated' });
    await expect(postPublic('/api/community', { action: 'me' })).rejects.toMatchObject({ status: 423, code: 'account_deactivated' });
    expect(seen).toHaveLength(1);
    expect(seen[0]).toBeGreaterThanOrEqual(before);
    off();
  });

  it('অন্য ভুল (500, account_disabled) এই শ্রোতা ডাকে না', async () => {
    const seen: number[] = [];
    const disabled: string[] = [];
    const off = onAccountDeactivated((t) => seen.push(t));
    const off2 = onAccountDisabled((m) => disabled.push(m));
    reply(500, { error: 'x' });
    await expect(postPublic('/api/community', { action: 'me' })).rejects.toBeTruthy();
    reply(403, { error: 'বন্ধ', code: 'account_disabled' });
    await expect(postPublic('/api/community', { action: 'me' })).rejects.toBeTruthy();
    expect(seen).toEqual([]);
    expect(disabled).toEqual(['বন্ধ']);
    off();
    off2();
  });
});
