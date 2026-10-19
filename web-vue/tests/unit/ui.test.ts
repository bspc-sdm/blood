import { describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import Button from '@/ui/Button.vue';
import ErrorText from '@/ui/ErrorText.vue';
import { cx, inputValue, slotHasContent } from '@/ui/composables';

describe('UI কিট', () => {
  it('Button: busy হলে চাপা যায় না, স্পিনার দেখায়', async () => {
    const w = mount(Button, { props: { busy: true, variant: 'tonal' }, slots: { default: 'রাখুন' } });
    const b = w.get('button');
    expect(b.attributes('disabled')).toBeDefined();
    expect(b.classes()).toEqual(expect.arrayContaining(['btn', 'tonal']));
    expect(w.find('.spinner').exists()).toBe(true);
    expect(b.text()).toBe('রাখুন');
  });

  it('ErrorText: খালি হলে কিছুই নয়', () => {
    expect(mount(ErrorText, { props: { text: '' } }).html()).toBe('<!--v-if-->');
    expect(mount(ErrorText, { props: { text: 'ভুল' } }).get('p[role=alert]').text()).toBe('ভুল');
  });

  it('cx: শুধু সত্যি ক্লাস', () => {
    expect(cx('a', false, null, 'b', undefined)).toBe('a b');
  });

  it('inputValue: বদলানো মান ঘরেও বসে', () => {
    const el = document.createElement('input');
    el.value = '01a7';
    const v = inputValue({ target: el } as unknown as Event, (s) => s.replace(/\D/g, ''));
    expect(v).toBe('017');
    expect(el.value).toBe('017');
  });

  it('slotHasContent: v-if মিথ্যা (মন্তব্য) মানে খালি — React-এর null-এর মতো', () => {
    let seen: boolean[] = [];
    const Probe = defineComponent({
      setup(_, { slots }) {
        return () => {
          seen.push(slotHasContent(slots.default));
          return h('div');
        };
      },
    });
    seen = [];
    mount(defineComponent({ render: () => h(Probe, null, { default: () => [null, false && h('b')].filter((x) => x !== false) }) }));
    mount(defineComponent({ render: () => h(Probe, null, { default: () => ['  '] }) }));
    mount(defineComponent({ render: () => h(Probe, null, { default: () => [h('b', 'x')] }) }));
    mount(Probe);
    expect(seen).toEqual([false, false, true, false]);
  });
});
