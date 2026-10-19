<script setup lang="ts">
// খোলা প্রেসক্রিপশন
import { ErrorBox, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import type { PharmRx } from './model';
import DispenseForm from './DispenseForm.vue';

const props = defineProps<{ code: string }>();
const emit = defineEmits<{ next: [] }>();
const q = useCall<{ item: PharmRx }>('rx_pharmacy_open', () => ({ code: props.code }), { staleTime: 60_000 });
</script>

<template>
  <SkeletonCards v-if="q.isLoading" :n="1" :h="260" />
  <section v-else-if="q.error || !q.data" class="card"><ErrorBox :error="q.error ? errorText(q.error) : tr('Not found', 'পাওয়া যায়নি')" :on-retry="() => q.refetch()" /></section>
  <DispenseForm v-else :key="q.data.item.id" :start="q.data.item" @next="emit('next')" />
</template>
