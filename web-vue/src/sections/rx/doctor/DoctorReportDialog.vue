<script setup lang="ts">
// পুরো রিপোর্ট: ফল, আগের ফল পাশে, নোট, ছবি (রিপোর্ট দেখার পাতার একই ছক)
import { Button, Dialog, ErrorText, Loading } from '@/ui/kit';
import { useCall } from '@/lib/query';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { SELF, type DoctorLab, type LabHistory, type LabOrder } from './model';
import LabReportView from './LabReportView.vue';

const props = defineProps<{ o: DoctorLab }>();
const emit = defineEmits<{ close: [] }>();
const q = useCall<{ item: LabOrder; history?: LabHistory }>('lab_order_get', { id: props.o.id, ...SELF });
</script>

<template>
  <Dialog size="wide" :title="tr('Test report', 'পরীক্ষার রিপোর্ট')" @close="emit('close')">
    <Loading v-if="q.isPending" />
    <ErrorText v-else-if="q.isError" :text="errorText(q.error)" />
    <LabReportView v-else-if="q.data" :o="q.data.item" :history="q.data.history ?? {}" />
    <template #actions><Button variant="text" @click="emit('close')">{{ tr('Close', 'বন্ধ') }}</Button></template>
  </Dialog>
</template>
