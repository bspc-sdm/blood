// অ্যানালিটিক্সের চার্ট: ৩০ দিনের বার, আর আনুভূমিক বার
import { defineComponent, ref, type PropType } from 'vue';
import { c, h, s } from '../h';
import { bn, dayFmt, num } from '../util';

/** ৩০ দিনের বার চার্ট — একটাই সিরিজ, তাই লেজেন্ড নেই; ছুঁলে তারিখ আর সংখ্যা */
export const BarCard = defineComponent({
  name: 'BarCard',
  props: {
    title: { type: String, required: true },
    values: { type: Array as PropType<(number | null)[]>, required: true },
    days: { type: Array as PropType<number[]>, required: true },
  },
  setup(p) {
    const hl = ref<number | null>(null);
    return () => {
      const vals = p.values.map((v) => (v == null ? 0 : v));
      const total = vals.reduce((a, b) => a + b, 0);
      const max = Math.max(1, ...vals);
      const W = 300;
      const H = 120;
      const n = vals.length;
      const slot = W / n;
      const bw = Math.max(2, slot - 2); // বারের মাঝে ২px ফাঁক
      const kids = [];
      for (let i = 0; i < n; i++) {
        const v = vals[i];
        const bh = v ? Math.max(3, (v / max) * (H - 6)) : 0;
        const x = i * slot + 1;
        const y = H - bh;
        if (bh) {
          // উপরের দুই কোণা গোল (৪px), নিচ সমান — শূন্য রেখায় বসানো
          const rr = Math.min(4, bw / 2, bh);
          kids.push(s('path', { class: hl.value === i ? 'bar hl' : 'bar', 'data-i': i, d: `M${x},${H}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + bw - rr}Q${x + bw},${y} ${x + bw},${y + rr}V${H}Z` }));
        }
        const show = () => {
          hl.value = i;
        };
        kids.push(s('rect', { class: 'hit', x: i * slot, y: 0, width: slot, height: H, onpointerenter: show, onpointerdown: show }));
      }
      kids.push(s('line', { class: 'base', x1: 0, x2: W, y1: H - 0.5, y2: H - 0.5 }));
      const peak = vals.indexOf(Math.max(...vals));
      const i = hl.value;
      return h('div', { class: 'card chart-card' },
        h('h3', { text: p.title }),
        h('div', { class: 'total' }, bn(total), h('small', { text: total ? `সর্বোচ্চ ${bn(vals[peak])}, ${dayFmt.format(new Date(p.days[peak]))}` : 'এই সময়ে কিছু নেই' })),
        h('div', { class: 'chart' },
          h('div', { class: 'tip', hidden: i == null, style: i == null ? null : { left: `${((i + 0.5) / n) * 100}%` }, text: i == null ? null : `${dayFmt.format(new Date(p.days[i]))}: ${bn(vals[i])}` }),
          s('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', role: 'img', 'aria-label': `${p.title}: ৩০ দিনে মোট ${bn(total)}`, onpointerleave: () => { hl.value = null; } }, kids),
          h('div', { class: 'x' }, h('span', { text: dayFmt.format(new Date(p.days[0])) }), h('span', { text: 'আজ' }))));
    };
  },
});
export const barCard = (title: string, values: (number | null)[], days: number[]) => c(BarCard, { title, values, days });

export function hbars(rows: [string, number | null | undefined][]) {
  const max = Math.max(1, ...rows.map(([, v]) => v || 0));
  return h('div', { class: 'hbars' }, rows.map(([label, v]) => h('div', { class: 'hbar' },
    h('span', { text: label }),
    // প্রস্থ CSSOM দিয়ে (style অ্যাট্রিবিউট নয়) — প্যানেলের কড়া CSP-তে চলে
    h('div', { class: 'track' }, h('div', { class: 'fill', style: { width: `${((v || 0) / max) * 100}%` } })),
    h('span', { class: 'val', text: num(v) }))));
}
