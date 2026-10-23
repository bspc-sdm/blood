<script setup lang="ts">
// ব্লাড ব্যাংকের মজুদ: A+ আছে · B+ কম · O- নেই … আর "হালনাগাদ: ২ ঘণ্টা আগে" (৪৮ ঘণ্টার বেশি পুরোনো হলে "পুরনো তথ্য")
import { LT, type Stock } from './model';

defineProps<{ stock: Stock; big?: boolean }>();
</script>

<template>
  <div :class="['lst-stock', big && 'big']" data-testid="stock-chips">
    <span v-for="c in stock.cells" :key="c.group" :class="['lst-stock-chip', c.level]">
      <b>{{ c.group }}</b>{{ ' ' + LT.stock(c.level) }}
    </span>
    <span v-if="!stock.cells.length" class="lst-stock-note">{{ LT.noStock() }}</span>
    <span v-else-if="stock.updated || stock.stale" :class="['lst-stock-note', stock.stale && 'stale']">
      {{ stock.stale ? LT.stale() + (stock.updated ? ' · ' + LT.updated(stock.updated) : '') : LT.updated(stock.updated!) }}
    </span>
  </div>
</template>
