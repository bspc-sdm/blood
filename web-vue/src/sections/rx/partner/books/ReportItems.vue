<script setup lang="ts">
import { cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { ShopItem } from './model';

defineProps<{ list: ShopItem[]; v: (i: ShopItem) => string; sub?: (i: ShopItem) => string; bad?: boolean }>();
</script>

<template>
  <p v-if="!list.length" class="t-body-m muted">{{ tr('None', 'নেই') }}</p>
  <ul v-else class="bk-rank">
    <li v-for="i in list" :key="i.id">
      <span class="grow ellipsis">{{ i.name }}</span>
      <span v-if="sub" class="t-body-s muted num">{{ sub(i) }}</span>
      <span :class="cx('num semi', bad && 'c-error')">{{ v(i) }}</span>
    </li>
  </ul>
</template>
