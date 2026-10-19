<script setup lang="ts">
import { tr } from '@/lib/i18n';
import { qtyText, type RankRow } from './model';

defineProps<{ rows: RankRow[]; v: (x: RankRow) => string; sub?: (x: RankRow) => string }>();
</script>

<template>
  <p v-if="!rows.length" class="t-body-m muted">{{ tr('Nothing yet', 'এখনো কিছু নেই') }}</p>
  <ol v-else class="bk-rank">
    <li v-for="x in rows" :key="x.id">
      <span class="grow ellipsis">{{ x.name }}</span>
      <span class="t-body-s muted num">{{ sub ? sub(x) : tr(`${x.qty}`, `${qtyText(x.qty)}টা`) }}</span>
      <span class="num semi">{{ v(x) }}</span>
    </li>
  </ol>
</template>
