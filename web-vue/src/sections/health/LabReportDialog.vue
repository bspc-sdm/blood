<script setup lang="ts">
// রিপোর্ট: ফলের তালিকা (বেশি/কম চিহ্নসহ), আগের ফল, নোট, ছবি
import { Dialog, ErrorText, Loading } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { useCareMode } from '@/lib/care';
import type { LabOrder } from './rxactivity';
import LabReportView from './LabReportView.vue';

const props = defineProps<{ id: string }>();
const emit = defineEmits<{ close: [] }>();
const care = useCareMode();
const lang = useLang();
const q = useQ<{ item: LabOrder; history?: Record<string, { result: string; flag: string; at: number }[]> }>(() => ({
  queryKey: ['lab_order_get', props.id, care.value?.uid ?? null, lang.value],
  queryFn: () => call('lab_order_get', { id: props.id }),
}));
</script>

<template>
  <Dialog size="wide" :title="tr('Test report', 'পরীক্ষার রিপোর্ট')" @close="emit('close')">
    <Loading v-if="q.isPending" />
    <ErrorText v-else-if="q.isError" :text="errorText(q.error)" />
    <LabReportView v-else :o="q.data!.item" :history="q.data!.history ?? {}" />
  </Dialog>
</template>
