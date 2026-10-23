<script setup lang="ts">
// অর্ডার, অ্যাপয়েন্টমেন্ট, অ্যাম্বুলেন্সের ভেতরে "রসিদ দেখুন" — রসিদ থাকলেই দেখায়।
// invoiceId জানা থাকলে সরাসরি; নইলে রোগীর রসিদের প্রথম পাতা (my_invoices, ক্যাশে একবার) থেকে refType + refId মিলিয়ে।
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { Icon } from '@/ui/kit';
import { call } from '@/lib/api';
import { useQ } from '@/lib/query';
import { useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { T } from './text';
import { money, parsePage, type RefType } from './model';

const props = defineProps<{ refType: RefType; refId: string; invoiceId?: string | null }>();
const s = useSession();
const lang = useLang();
const care = useCareMode();
// MyInvoicesPage-এর প্রথম পাতার একই চাবি নয় (ওটা infinite) — আলাদা ছোট ক্যাশ
const q = useQ(() => ({
  queryKey: ['my_invoices', 'index', care.value?.uid ?? null, lang.value, s.uid],
  queryFn: () => call('my_invoices', {}).then(parsePage),
  enabled: !s.guest && !props.invoiceId,
  staleTime: 60_000,
}));
const found = computed(() => (props.invoiceId ? null : q.data?.items.find((i) => i.refType === props.refType && i.refId === props.refId) ?? null));
const id = computed(() => props.invoiceId ?? found.value?.id ?? null);
</script>

<template>
  <RouterLink v-if="id" :to="`/invoices/${id}`" class="btn tonal small has-icon inv-link" data-testid="invoice-link">
    <Icon name="list_alt" />{{ T.seeReceipt() }}{{ found ? ' · ' + money(found.total) : '' }}
  </RouterLink>
</template>
