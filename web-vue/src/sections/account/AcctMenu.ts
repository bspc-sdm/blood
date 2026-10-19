// মেনুর বাক্স: প্রতিটা সারি আলাদা <div>-এ, মাঝে দাগ (React-এর Menu — না দেখানো সারি বাদ)
import { Comment, Fragment, defineComponent, h, type VNode } from 'vue';

function flatten(nodes: VNode[]): VNode[] {
  const out: VNode[] = [];
  for (const n of nodes) {
    if (n.type === Comment) continue;
    if (n.type === Fragment && Array.isArray(n.children)) out.push(...flatten(n.children as VNode[]));
    else out.push(n);
  }
  return out;
}

export default defineComponent({
  name: 'AcctMenu',
  props: { label: { type: String, default: undefined } },
  setup(props, { slots }) {
    return () => {
      const items = flatten(slots.default?.() ?? []);
      return h(
        'nav',
        { class: 'acct-menu', 'aria-label': props.label },
        items.map((c, i) => h('div', { key: i }, [i > 0 ? h('hr', { class: 'divider acct-div' }) : null, c])),
      );
    };
  },
});
