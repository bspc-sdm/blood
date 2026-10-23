<script setup lang="ts">
// বিক্রি/বিল/ফি-র পর বা তালিকা থেকে: রসিদ ডায়ালগে, সাথে সাথে ছাপা (autoPrint)
import { onMounted } from 'vue';
import { Dialog } from '@/ui/kit';
import { T } from './text';
import ReceiptView from './ReceiptView.vue';
import { paperPref, printInvoice } from './print';
import type { Invoice } from './model';

const props = defineProps<{ inv: Invoice; autoPrint?: boolean }>();
const emit = defineEmits<{ close: [] }>();
onMounted(() => {
  // ছাপার কপি পাতায় বসার পর
  if (props.autoPrint) setTimeout(() => printInvoice(props.inv, paperPref.value).catch(() => {}), 80);
});
</script>

<template>
  <Dialog size="wide" :title="T.receipt()" @close="emit('close')">
    <ReceiptView :inv="inv">
      <template #actions><slot /></template>
    </ReceiptView>
  </Dialog>
</template>
