<script setup lang="ts">
// "আমার খরচ" (my_expenses): এক মাসের মোট আর ধরন অনুযায়ী (ফার্মেসি, প্যাথলজি, চেম্বার, অ্যাম্বুলেন্স); আগের/পরের মাস
import { computed, ref } from 'vue';
import { EmptyState, ErrorBox, Icon, IconButton, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { T } from './text';
import { INV_KINDS, kindIcon, kindText, money, monthText, shiftMonth, thisMonth, type Expenses } from './model';

const month = ref(thisMonth());
const q = useCall<Expenses>('my_expenses', () => ({ month: month.value }));
const max = computed(() => Math.max(1, ...INV_KINDS.map((k) => q.data?.byKind?.[k] ?? 0)));
const canNext = computed(() => month.value < thisMonth());
</script>

<template>
  <section class="card col gap-12 inv-exp" data-testid="my-expenses">
    <div class="row gap-8">
      <IconButton icon="arrow_back" :label="T.prevMonth()" @click="month = shiftMonth(month, -1)" />
      <h2 class="t-title-m grow inv-exp-month">{{ monthText(month) }}</h2>
      <IconButton v-if="canNext" icon="chevron_right" :label="T.nextMonth()" @click="month = shiftMonth(month, 1)" />
    </div>
    <SkeletonCards v-if="q.isPending" :n="1" :h="160" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <EmptyState v-else-if="!q.data?.count" icon="history" :title="T.noSpend()" />
    <template v-else>
      <div class="col gap-2">
        <span class="t-label-m muted">{{ T.monthTotal() }}</span>
        <span class="bk-big num" data-testid="exp-total">{{ money(q.data.total) }}</span>
        <span class="t-body-s muted">{{ T.count(q.data.count) }}</span>
      </div>
      <ul class="inv-exp-list">
        <li v-for="k in INV_KINDS" :key="k" :data-kind="k">
          <span class="row gap-8">
            <Icon :name="kindIcon(k)" :size="20" data-testid="row-icon" />
            <span class="grow">{{ kindText(k) }}</span>
            <span class="num semi">{{ money(q.data.byKind?.[k] ?? 0) }}</span>
          </span>
          <span class="inv-bar" aria-hidden="true"><span :style="{ width: `${Math.round(((q.data.byKind?.[k] ?? 0) / max) * 100)}%` }" /></span>
        </li>
      </ul>
    </template>
  </section>
</template>
