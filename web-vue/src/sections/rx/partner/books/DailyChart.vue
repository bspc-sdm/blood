<script setup lang="ts">
// দিনের বিক্রি আর লাভ — পাশাপাশি দুটো বার; ছোঁয়া/মাউসে দিনের সংখ্যা
import { computed, ref } from 'vue';
import { cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { dayShort, money, type Report } from './model';

const props = defineProps<{ r: Report }>();
const max = computed(() => Math.max(1, ...props.r.daily.map((d) => Math.max(d.net, d.profit))));
const on = ref<number | null>(null);
const cur = computed(() => (on.value != null ? props.r.daily[on.value] : null));
const labelAt = computed(() => new Set([0, Math.floor((props.r.daily.length - 1) / 2), props.r.daily.length - 1]));
</script>

<template>
  <div class="col gap-8">
    <div class="row wrap gap-16 t-body-s">
      <span class="row gap-6"><span class="bk-sw bk-sales" />{{ tr('Sales', 'বিক্রি') }}</span>
      <span class="row gap-6"><span class="bk-sw bk-profit" />{{ tr('Profit', 'লাভ') }}</span>
      <span class="grow" />
      <span class="muted num">{{ cur ? `${dayShort(cur.day)} · ${tr('sales', 'বিক্রি')} ${money(cur.net)} · ${tr('profit', 'লাভ')} ${money(cur.profit)}` : tr('max ', 'সর্বোচ্চ ') + money(Math.round(max)) }}</span>
    </div>
    <div class="bk-chart" role="img" :aria-label="tr('Daily sales and profit', 'দিনের বিক্রি ও লাভ')" @mouseleave="on = null">
      <div
        v-for="(d, i) in r.daily"
        :key="d.day"
        :class="cx('bk-col', on === i && 'on')"
        :title="`${dayShort(d.day)}: ${money(d.net)} / ${money(d.profit)}`"
        @mouseenter="on = i"
        @click="on = on === i ? null : i"
      >
        <div class="bk-bars">
          <span class="bk-bar bk-sales" :style="{ height: `${(Math.max(0, d.net) / max) * 100}%` }" />
          <span class="bk-bar bk-profit" :style="{ height: `${(Math.max(0, d.profit) / max) * 100}%` }" />
        </div>
        <span class="bk-x num">{{ labelAt.has(i) ? dayShort(d.day) : '' }}</span>
      </div>
    </div>
    <details class="bk-table-view">
      <summary class="t-body-s">{{ tr('See as table', 'টেবিলে দেখুন') }}</summary>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>{{ tr('Day', 'দিন') }}</th><th class="bk-r">{{ tr('Sales', 'বিক্রি') }}</th><th class="bk-r">{{ tr('Profit', 'লাভ') }}</th></tr></thead>
          <tbody><tr v-for="d in r.daily" :key="d.day"><td class="num">{{ dayShort(d.day) }}</td><td class="bk-r num">{{ money(d.net) }}</td><td class="bk-r num">{{ money(d.profit) }}</td></tr></tbody>
        </table>
      </div>
    </details>
  </div>
</template>
