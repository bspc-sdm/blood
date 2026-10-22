// সেটিংস › নোটিফিকেশন (NotifySettings.vue + lib/notifyPrefs): সুইচ, "সব নোটিফিকেশন", জরুরি ধরন বন্ধে "নিশ্চিত?",
// "আমার ব্যবসা" দেখানো/লুকানো, "অ্যাকাউন্ট" সবসময় চালু, সেভ না হলে আগের অবস্থা আর টোস্ট।
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

const call = vi.fn();
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  errorText: (e: unknown) => (e instanceof Error ? e.message : 'x'),
}));

import NotifySettings from '@/sections/account/NotifySettings.vue';
import { NOTIFY_CATS, normalizePrefs, revertChange, withChange } from '@/lib/notifyPrefs';
import { toastMessage } from '@/ui/toast';

// ConfirmDialog-এর বদলে সাধারণ বাক্স (Reka-র পোর্টাল লাগে না)
const ConfirmStub = defineComponent({
  props: ['title', 'text', 'confirm'],
  emits: ['confirm', 'close'],
  setup(p, { emit }) {
    return () =>
      h('div', { role: 'dialog', 'data-test': 'confirm' }, [
        h('p', p.title),
        h('button', { 'data-test': 'yes', onClick: () => emit('confirm') }, p.confirm),
        h('button', { 'data-test': 'no', onClick: () => emit('close') }, 'বাতিল'),
      ]);
  },
});

type Server = { all: boolean; cats: Record<string, boolean>; sets: Record<string, unknown>[]; fail: boolean };
let st: Server;

beforeEach(() => {
  localStorage.setItem('lf.lang', 'bn');
  toastMessage.value = null;
  st = { all: true, cats: Object.fromEntries(NOTIFY_CATS.map((c) => [c, true])), sets: [], fail: false };
  call.mockImplementation(async (action: string, body: Record<string, any> = {}) => {
    if (action === 'notify_prefs_get') return { ok: true, all: st.all, cats: { ...st.cats } };
    if (action === 'notify_prefs_set') {
      st.sets.push(body);
      if (st.fail) throw new Error('net');
      if (typeof body.all === 'boolean') st.all = body.all;
      Object.assign(st.cats, body.cats || {});
      return { ok: true, all: st.all, cats: { ...st.cats } };
    }
    throw new Error(`unexpected ${action}`);
  });
});
afterEach(() => call.mockReset());

async function mountIt(showBusiness = true) {
  const w = mount(NotifySettings, {
    props: { showBusiness },
    slots: { nearby: '<span data-test="extras">দিনে সর্বোচ্চ</span>' },
    global: { stubs: { ConfirmDialog: ConfirmStub } },
  });
  await flushPromises();
  return w;
}
const sw = (w: Awaited<ReturnType<typeof mountIt>>, cat: string) => w.get<HTMLInputElement>(`[data-cat="${cat}"] input[role="switch"]`);
async function flip(w: Awaited<ReturnType<typeof mountIt>>, cat: string) {
  const el = sw(w, cat);
  await el.setValue(!el.element.checked);
  await flushPromises();
}

describe('notifyPrefs (lib)', () => {
  it('না থাকা ঘর মানে চালু', () => {
    const p = normalizePrefs({ all: true, cats: { rx: false } as any });
    expect(p.cats.rx).toBe(false);
    expect(p.cats.community).toBe(true);
    expect(normalizePrefs(null).all).toBe(true);
  });
  it('ফেরানো শুধু নিজের বদল — মাঝের অন্য বদল থাকে', () => {
    const before = normalizePrefs({});
    const mid = withChange(withChange(before, { cats: { rx: false } }), { cats: { community: false } });
    const back = revertChange(mid, before, { cats: { rx: false } });
    expect(back.cats.rx).toBe(true);
    expect(back.cats.community).toBe(false);
  });
});

describe('NotifySettings', () => {
  it('সব ধরন দেখায় ও চালু; অ্যাকাউন্ট চালু কিন্তু বদলানো যায় না; ঘণ্টার নোট', async () => {
    const w = await mountIt();
    for (const c of NOTIFY_CATS) expect(sw(w, c).element.checked).toBe(true);
    expect(sw(w, 'account').element.checked).toBe(true);
    expect(sw(w, 'account').element.disabled).toBe(true);
    expect(w.text()).toContain('অ্যাকাউন্ট আর নিরাপত্তা (সবসময় চালু)');
    expect(w.get('[data-testid="bell-note"]').text()).toContain('ঘণ্টার তালিকায়');
    expect(w.find('[data-test="extras"]').exists()).toBe(true);
  });

  it('একটা সুইচ: সাথে সাথে বদলায়, শুধু সেটাই পাঠায়', async () => {
    const w = await mountIt();
    await flip(w, 'rx');
    expect(sw(w, 'rx').element.checked).toBe(false);
    expect(st.sets).toEqual([{ cats: { rx: false } }]);
    expect(w.find('[data-test="confirm"]').exists()).toBe(false);
  });

  it('আশেপাশে রক্ত বন্ধ: নিচের পছন্দ লুকায়, nearby জানায়', async () => {
    const w = await mountIt();
    await flip(w, 'blood_nearby');
    expect(w.find('[data-test="extras"]').exists()).toBe(false);
    expect(w.emitted('nearby')?.at(-1)).toEqual([false]);
  });

  it('সব নোটিফিকেশন বন্ধ: ধরনগুলো নিষ্ক্রিয়, অ্যাকাউন্ট চালুই', async () => {
    const w = await mountIt();
    await flip(w, 'all');
    expect(st.sets).toEqual([{ all: false }]);
    for (const c of NOTIFY_CATS) expect(sw(w, c).element.disabled).toBe(true);
    expect(sw(w, 'account').element.checked).toBe(true);
    await flip(w, 'all');
    expect(st.all).toBe(true);
    expect(sw(w, 'rx').element.disabled).toBe(false);
  });

  it.each(['blood_emergency', 'ambulance'])('%s বন্ধের আগে "নিশ্চিত?" — বাতিলে চালুই, নিশ্চিতে বন্ধ', async (cat) => {
    const w = await mountIt();
    await flip(w, cat);
    expect(w.get('[data-test="confirm"]').text()).toContain('জরুরি খবর পাবেন না — নিশ্চিত?');
    expect(st.sets).toEqual([]);
    await w.get('[data-test="no"]').trigger('click');
    await flushPromises();
    expect(w.find('[data-test="confirm"]').exists()).toBe(false);
    expect(sw(w, cat).element.checked).toBe(true);
    expect(st.sets).toEqual([]);

    await flip(w, cat);
    await w.get('[data-test="yes"]').trigger('click');
    await flushPromises();
    expect(sw(w, cat).element.checked).toBe(false);
    expect(st.sets).toEqual([{ cats: { [cat]: false } }]);
    // চালু করতে কিছু জিজ্ঞেস করে না
    await flip(w, cat);
    expect(w.find('[data-test="confirm"]').exists()).toBe(false);
    expect(st.cats[cat]).toBe(true);
  });

  it('আমার ব্যবসা: শুধু ব্যবসা/পেশাদার ভূমিকা থাকলে', async () => {
    expect((await mountIt(true)).find('[data-cat="business"]').exists()).toBe(true);
    expect((await mountIt(false)).find('[data-cat="business"]').exists()).toBe(false);
  });

  it('সেভ না হলে আগের অবস্থায় ফেরে আর টোস্ট দেখায়', async () => {
    const w = await mountIt();
    st.fail = true;
    await flip(w, 'community');
    expect(sw(w, 'community').element.checked).toBe(true);
    expect(toastMessage.value).toBe('সেভ হয়নি, ইন্টারনেট দেখুন।');
  });

  it('লোড না হলে আবার চেষ্টা', async () => {
    call.mockRejectedValueOnce(new Error('লোড হয়নি'));
    const w = await mountIt();
    expect(w.text()).toContain('লোড হয়নি');
    await w.get('button').trigger('click');
    await flushPromises();
    expect(sw(w, 'rx').element.checked).toBe(true);
  });
});
