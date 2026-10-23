<script setup lang="ts">
// রসিদ id দিয়ে (অর্ডার/অনুরোধের invoiceId থেকে): invoice_get → রসিদের ডায়ালগ
import { computed } from 'vue';
import { Dialog, ErrorText, Loading } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { T } from './text';
import ReceiptDialog from './ReceiptDialog.vue';
import { pickInvoice } from './model';

const props = defineProps<{ id: string }>();
const emit = defineEmits<{ close: [] }>();
const q = useCall<any>('invoice_get', () => ({ id: props.id }));
const inv = computed(() => (q.data ? pickInvoice(q.data) : null));
</script>

<template>
  <ReceiptDialog v-if="inv" :inv="inv" @close="emit('close')" />
  <Dialog v-else size="small" :title="T.receipt()" @close="emit('close')">
    <ErrorText v-if="q.error" :text="errorText(q.error)" />
    <Loading v-else />
  </Dialog>
</template>
