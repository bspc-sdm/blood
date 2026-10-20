<script setup lang="ts">
import { computed } from 'vue';
import { cx } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { money, sourceText, type Report } from './model';

const props = defineProps<{ r: Report }>();
const T = computed(() => props.r.totals);
const tiles = computed(() => {
  const t = T.value;
  return [
    { l: tr('Sales', 'বিক্রি'), v: money(t.net), tone: undefined as string | undefined, sub: tr(`${t.sales} sales`, `${num(t.sales)}টা বিক্রি`) },
    { l: tr('Gross profit', 'মোট লাভ'), v: money(t.profit), tone: t.profit >= 0 ? 'good' : 'bad', sub: t.margin != null ? tr(`margin ${t.margin}%`, `মার্জিন ${num(t.margin)}%`) : undefined },
    { l: tr('Net profit', 'নিট লাভ'), v: money(t.netProfit), tone: t.netProfit >= 0 ? 'good' : 'bad', sub: tr('after losses', 'ক্ষতি বাদে') },
  ];
});
</script>

<template>
  <div class="col gap-12">
    <div class="stats">
      <div v-for="(t, i) in tiles" :key="i" :class="cx('stat', t.tone && `bk-${t.tone}`)">
        <span class="l">{{ t.l }}</span>
        <span class="v">{{ t.v }}</span>
        <span v-if="t.sub" class="t-body-s muted">{{ t.sub }}</span>
      </div>
    </div>
    <div class="rxp-two">
      <dl class="kv num bk-kv">
        <dt>{{ tr('Discount given', 'ছাড় দেওয়া') }}</dt><dd>{{ money(T.discount) }}</dd>
        <dt>{{ tr('Cost of goods sold', 'বিক্রি হওয়া মালের কেনা দাম') }}</dt><dd>{{ money(T.cost) }}</dd>
        <dt>{{ tr('Damaged / expired (loss)', 'নষ্ট / মেয়াদ শেষে ক্ষতি') }}</dt><dd>{{ money(T.writeOff) }}</dd>
        <dt>{{ tr('Stock bought', 'মাল কেনা') }}</dt><dd>{{ money(T.bought) }}</dd>
      </dl>
      <dl class="kv num bk-kv">
        <template v-for="s in (['counter', 'rx', 'online'] as const)" :key="s">
          <dt>{{ sourceText(s) }}</dt><dd>{{ `${money(r.bySource[s].net)} · ${num(r.bySource[s].n)}` }}</dd>
        </template>
        <template v-if="T.uncosted > 0">
          <dt>{{ tr('Sales without cost price', 'কেনা দাম নেই এমন বিক্রি') }}</dt><dd>{{ money(T.uncosted) }}</dd>
        </template>
      </dl>
    </div>
  </div>
</template>
