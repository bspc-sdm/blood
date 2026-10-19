import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h as vh, nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import { h, icon } from '../../admin/src/h';
import { ago, bn, matches, num, showVal } from '../../admin/src/util';
import { ActBtn, hold, reviewCell, table } from '../../admin/src/kit';
import { S, parseHash } from '../../admin/src/state';

describe('অ্যাডমিন: ছোট সাহায্যকারী', () => {
  it('বাংলা অঙ্ক আর সংখ্যা', () => {
    expect(bn(2026)).toBe('২০২৬');
    expect(num(1234567)).toBe('১২,৩৪,৫৬৭');
    expect(num(null)).toBe('—');
  });
  it('কত আগে', () => {
    const now = Date.now();
    expect(ago(now)).toBe('এইমাত্র');
    expect(ago(now - 5 * 60000)).toBe('৫ মিনিট আগে');
    expect(ago(now - 3 * 3600000)).toBe('৩ ঘণ্টা আগে');
    expect(ago(now - 2 * 86400000)).toBe('২ দিন আগে');
    expect(ago(0)).toBe('—');
  });
  it('বদলের মান', () => {
    expect(showVal('closedDays', [5, 6])).toBe('শুক্র, শনি');
    expect(showVal('open24', true)).toBe('হ্যাঁ');
    expect(showVal('types', ['ac', 'icu'])).toBe('এসি, আইসিইউ');
    expect(showVal('phone', '017')).toBe('০১৭');
    expect(showVal('x', [])).toBe('—');
  });
  it('খোঁজা', () => {
    expect(matches(' ফার্মে ', 'নতুন ফার্মেসি', null)).toBe(true);
    expect(matches('xyz', 'abc')).toBe(false);
    expect(matches('', 'abc')).toBe(true);
  });
  it('ঠিকানা (#/pharmacies?s=approved, #/user/<id>)', () => {
    expect(parseHash('#/pharmacies?s=approved')).toMatchObject({ name: 'pharmacies', arg: '' });
    expect(parseHash('#/pharmacies?s=approved').params.get('s')).toBe('approved');
    expect(parseHash('#/user/a%2Fb')).toMatchObject({ name: 'user', arg: 'a/b' });
    expect(parseHash('')).toMatchObject({ name: '', arg: '' });
  });
});

describe('অ্যাডমিন: h() আগের প্যানেলের মতো', () => {
  it('null/false বাদ, true খালি অ্যাট্রিবিউট, text লেখা হিসেবে, onclick চলে', async () => {
    const click = vi.fn();
    const w = mount(defineComponent({ render: () => h('button', { class: 'btn', disabled: false, hidden: null, 'data-x': true, onclick: click, text: '<b>না</b>' }, null, 'পরে') }));
    const b = w.get('button');
    expect(b.attributes('disabled')).toBeUndefined();
    expect(b.attributes('data-x')).toBe('');
    // লেখা কখনো HTML নয়
    expect(b.element.innerHTML).toBe('&lt;b&gt;না&lt;/b&gt;পরে');
    await b.trigger('click');
    expect(click).toHaveBeenCalledOnce();
  });
  it('আইকন svg', () => {
    const w = mount(defineComponent({ render: () => icon('home') }));
    expect(w.get('svg').attributes('viewBox')).toBe('0 0 960 960');
    expect(w.get('path').attributes('d')).toMatch(/^M/);
  });
  it('টেবিল: খালি হলে "এখানে কিছু নেই।", মোবাইলের data-label প্রথম ঘর ছাড়া', () => {
    expect(mount(defineComponent({ render: () => table([], [{ label: 'নাম', cell: () => '' }]) })).text()).toBe('এখানে কিছু নেই।');
    const w = mount(defineComponent({ render: () => table([{ n: 'ক', p: '১' }], [{ label: 'নাম', main: true, cell: (x) => x.n }, { label: 'ফোন', cell: (x) => x.p }], () => [h('button', { text: 'ঠিক' })]) }));
    const tds = w.findAll('td');
    expect(tds[0].attributes('data-label')).toBeUndefined();
    expect(tds[0].classes()).toContain('main-cell');
    expect(tds[1].attributes('data-label')).toBe('ফোন');
    expect(tds[2].classes()).toContain('act');
  });
  it('কারণের ঘর: একই সারির মান আবার আঁকলেও থাকে', () => {
    const row = {};
    hold(row).value = 'তথ্য ভুল';
    expect(hold(row).value).toBe('তথ্য ভুল');
    expect(hold({}).value).toBe('');
  });
  it('বদল/নোটের ঘর', () => {
    expect(reviewCell({})).toBe('—');
    const w = mount(defineComponent({ render: () => vh('div', null, reviewCell({ statusReason: 'লাইসেন্স নেই', changes: [{ field: 'phone', before: '017', after: '018' }] }) as never) }));
    expect(w.text()).toContain('কারণ: লাইসেন্স নেই');
    expect(w.text()).toContain('ফোন: আগে ০১৭ → এখন ০১৮');
  });
});

describe('অ্যাডমিন: কাজের বোতাম', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = '<div id="toast" hidden></div>';
    S.loggedIn = true;
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('warn: প্রথম চাপে "নিশ্চিত?", ৪ সেকেন্ড পরে আবার আগের মতো', async () => {
    const run = vi.fn();
    const w = mount(ActBtn, { props: { label: 'বাতিল', kind: 'warn', run }, attachTo: document.body });
    await w.get('button').trigger('click');
    expect(w.get('button').text()).toBe('নিশ্চিত? আবার চাপুন');
    expect(w.get('button').classes()).toContain('armed');
    expect(run).not.toHaveBeenCalled();
    vi.advanceTimersByTime(4000);
    await nextTick();
    expect(w.get('button').text()).toBe('বাতিল');
    w.unmount();
  });

  it('দ্বিতীয় চাপে কাজ, শেষে বার্তা আর after', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const after = vi.fn();
    const w = mount(ActBtn, { props: { label: 'বাতিল', kind: 'warn', run, okMsg: 'বাতিল করা হয়েছে', after }, attachTo: document.body });
    await w.get('button').trigger('click');
    await w.get('button').trigger('click');
    await vi.runAllTimersAsync();
    expect(run).toHaveBeenCalledOnce();
    expect(after).toHaveBeenCalledOnce();
    expect(document.getElementById('toast')!.textContent).toBe('বাতিল করা হয়েছে');
    w.unmount();
  });

  it('ভুল হলে বোতাম আবার চালু আর সারির নিচে ভুলের লেখা', async () => {
    const run = vi.fn().mockRejectedValue(new Error('সার্ভারে সমস্যা'));
    const Host = defineComponent({ render: () => vh('table', null, [vh('tbody', null, [vh('tr', null, [vh('td', null, [vh(ActBtn, { label: 'অনুমোদন', kind: 'primary', run })])])])]) });
    const w = mount(Host, { attachTo: document.body });
    await w.get('button').trigger('click');
    await vi.runAllTimersAsync();
    expect(w.get('button').element.disabled).toBe(false);
    expect(w.get('button').text()).toBe('অনুমোদন');
    expect(w.get('td .row-error').text()).toBe('সার্ভারে সমস্যা');
    w.unmount();
  });
});
