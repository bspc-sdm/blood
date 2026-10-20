<script setup lang="ts">
import { cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { highOf, lowOf, type Row } from '../labdetail';

defineProps<{ r: Row; set: (id: number, p: Partial<Row>) => void }>();
const val = (e: Event) => (e.target as HTMLInputElement).value;
</script>

<template>
  <div v-if="r.numeric" class="rxp-range">
    <input :class="cx('input rxp-cell num', Number.isNaN(lowOf(r)) && 'bad')" inputmode="decimal" :value="r.lowText" maxlength="10" :aria-label="`${r.test}: ${tr('Low', 'নিচে')}`" :placeholder="tr('Low', 'নিচে')" @input="set(r.id, { lowText: val($event) })" />
    <span class="muted">–</span>
    <input :class="cx('input rxp-cell num', Number.isNaN(highOf(r)) && 'bad')" inputmode="decimal" :value="r.highText" maxlength="10" :aria-label="`${r.test}: ${tr('High', 'ওপরে')}`" :placeholder="tr('High', 'ওপরে')" @input="set(r.id, { highText: val($event) })" />
  </div>
  <input v-else-if="!r.long" class="input rxp-cell" :value="r.range" maxlength="60" :aria-label="`${r.test}: ${tr('Normal range', 'স্বাভাবিক মাত্রা')}`" :placeholder="tr('Normal range', 'স্বাভাবিক মাত্রা')" @input="set(r.id, { range: val($event) })" />
</template>
