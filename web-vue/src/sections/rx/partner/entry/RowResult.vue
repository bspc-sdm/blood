<script setup lang="ts">
import { cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { focusNext } from '../common';
import type { Row } from '../labdetail';

defineProps<{ r: Row; set: (id: number, p: Partial<Row>) => void }>();
function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault();
    focusNext(e.currentTarget as HTMLElement, 'result');
  }
}
</script>

<template>
  <textarea
    v-if="r.long"
    class="textarea rxp-long"
    :value="r.result"
    maxlength="2000"
    rows="3"
    :aria-label="`${r.test}: ${tr('Findings', 'ফলাফল')}`"
    :placeholder="tr('Findings', 'ফলাফল')"
    @input="set(r.id, { result: ($event.target as HTMLTextAreaElement).value })"
  />
  <template v-else>
    <input
      :class="cx('input rxp-cell', r.numeric && 'num')"
      :value="r.result"
      maxlength="60"
      :inputmode="r.numeric ? 'decimal' : undefined"
      :list="r.options.length ? `rxp-opt-${r.id}` : undefined"
      data-rxp-nav="result"
      :aria-label="`${r.test || tr('Test', 'পরীক্ষা')}: ${tr('Result', 'ফল')}`"
      :placeholder="r.options.length ? r.range || tr('Choose', 'বাছুন') : tr('Result', 'ফল')"
      @input="set(r.id, { result: ($event.target as HTMLInputElement).value })"
      @keydown="onKey"
    />
    <datalist v-if="r.options.length" :id="`rxp-opt-${r.id}`"><option v-for="x in r.options" :key="x" :value="x" /></datalist>
  </template>
</template>
