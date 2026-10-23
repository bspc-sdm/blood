<script setup lang="ts">
// "দিনের শেষ" (নিরাপত্তা পর্যালোচনা I2): আজকের রসিদ, আদায়, বাকি; বাতিল (কারণসহ); ১০%-এর বেশি ছাড়; ফার্মেসিতে নষ্ট/মেয়াদ শেষে
// ক্ষতি (books_report-এর writeOff); প্রতিটা কম্পিউটার/সিরিজ (W = ওয়েব/ফোন, D1… = ডেস্কটপ) আলাদা।
// এখনকার হিসাব আজকের রসিদের তালিকা থেকে — সার্ভারের সারাংশ (ব্যক্তি অনুযায়ী, দামের নিচে বিক্রি) এলে সেটাই দেখানো হবে।
import { computed } from 'vue';
import { EmptyState, Icon, SkeletonCards, Tag } from '@/ui/kit';
import { call } from '@/lib/api';
import { useCall, useQ } from '@/lib/query';
import { num } from '@/lib/i18n';
import { formatTime } from '@/lib/format';
import { T } from './text';
import { money, parsePage, r2, type InvKind, type InvPage, type Invoice } from './model';

const props = defineProps<{ kind: InvKind; bizId: string }>();
// আজকের সব রসিদ (৫০টা করে পাতা — সব পাতা)
const q = useQ<Invoice[]>(() => ({
  queryKey: ['invoice_list_biz', props.kind, props.bizId, 'today', 'summary'],
  queryFn: async () => {
    const out: Invoice[] = [];
    let cursor: string | null = null;
    for (let i = 0; i < 20; i++) {
      const p: InvPage = parsePage(await call('invoice_list_biz', { kind: props.kind, bizId: props.bizId, period: 'today', ...(cursor ? { cursor } : {}) }));
      out.push(...p.items);
      if (!p.next) break;
      cursor = p.next;
    }
    return out;
  },
  enabled: !!props.bizId,
}));
const books = useCall<{ totals?: { writeOff?: number } }>('books_report', { period: 'today' }, () => ({ enabled: props.kind === 'pharmacy' }));
const all = computed(() => q.data ?? []);
const live = computed(() => all.value.filter((i) => i.status !== 'void'));
const voids = computed(() => all.value.filter((i) => i.status === 'void'));
const gross = (i: Invoice) => i.lines.reduce((a, l) => a + l.qty * l.price, 0);
const discOf = (i: Invoice) => r2(i.lines.reduce((a, l) => a + l.disc, 0) + i.disc);
const bigDisc = computed(() => live.value.filter((i) => gross(i) > 0 && discOf(i) / gross(i) > 0.1));
const sum = (xs: Invoice[], f: (i: Invoice) => number) => r2(xs.reduce((a, i) => a + f(i), 0));
const series = computed(() => {
  const m = new Map<string, { n: number; paid: number; voids: number }>();
  for (const i of all.value) {
    const s = (i.no.split('-')[0] || 'W').toUpperCase();
    const x = m.get(s) ?? { n: 0, paid: 0, voids: 0 };
    if (i.status === 'void') x.voids++;
    else {
      x.n++;
      x.paid = r2(x.paid + i.paid);
    }
    m.set(s, x);
  }
  return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
});
</script>

<template>
  <section class="card col gap-12 inv-day" data-testid="day-summary">
    <div class="row gap-8"><Icon name="history" /><h3 class="t-title-m grow">{{ T.dayEnd() }}</h3></div>
    <SkeletonCards v-if="q.isPending" :n="1" :h="120" />
    <EmptyState v-else-if="!all.length" icon="list_alt" :title="T.noReceipts()" />
    <template v-else>
      <dl class="kv num">
        <dt>{{ T.receipts() }}</dt><dd>{{ num(live.length) }}</dd>
        <dt>{{ T.collectionTotal() }}</dt><dd data-testid="day-paid">{{ money(sum(live, (i) => i.paid)) }}</dd>
        <dt>{{ T.dueTotal() }}</dt><dd>{{ money(sum(live, (i) => i.due)) }}</dd>
        <dt>{{ T.voided() }}</dt><dd data-testid="day-voids">{{ num(voids.length) }}{{ voids.length ? ' · ' + money(sum(voids, (i) => i.total)) : '' }}</dd>
        <dt>{{ T.bigDiscounts() }}</dt><dd>{{ num(bigDisc.length) }}</dd>
        <template v-if="kind === 'pharmacy' && books.data?.totals?.writeOff != null"><dt>{{ T.writeOffs() }}</dt><dd>{{ money(books.data.totals.writeOff) }}</dd></template>
      </dl>
      <div v-if="voids.length" class="col gap-4">
        <span class="t-label-l">{{ T.voided() }}</span>
        <span v-for="i in voids" :key="i.id" class="t-body-s">{{ num(i.no) }} · {{ formatTime(i.issuedAt) }} · {{ money(i.total) }} — {{ i.voidReason || '' }}</span>
      </div>
      <div v-if="bigDisc.length" class="col gap-4">
        <span class="t-label-l">{{ T.bigDiscounts() }}</span>
        <span v-for="i in bigDisc" :key="i.id" class="t-body-s">{{ num(i.no) }} · {{ money(discOf(i)) }} / {{ money(gross(i)) }}</span>
      </div>
      <div v-if="series.length > 1" class="row wrap gap-6">
        <Tag v-for="[s, x] in series" :key="s">{{ s }}: {{ num(x.n) }} · {{ money(x.paid) }}{{ x.voids ? ' · ' + T.voided() + ' ' + num(x.voids) : '' }}</Tag>
      </div>
    </template>
  </section>
</template>
