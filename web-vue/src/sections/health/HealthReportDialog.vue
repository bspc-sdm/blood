<script setup lang="ts">
// রিপোর্ট করবেন? (অ্যাপের ReportDialog) — নিশ্চিত হলে কাজটা চালায়, ভুল এলে দেখায়
import { ref } from 'vue';
import { Button, Dialog, ErrorText } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';

const props = defineProps<{ onConfirm: () => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const busy = ref(false);
const err = ref<string | null>(null);
async function confirm() {
  busy.value = true;
  err.value = null;
  try {
    await props.onConfirm();
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr('Report this?', 'রিপোর্ট করবেন?')" @close="emit('close')">
    <p class="t-body-m muted">{{ tr("Report if it's wrong, harmful or abusive. An admin will review it.", 'ভুল, ক্ষতিকর বা আপত্তিকর মনে হলে রিপোর্ট করুন। অ্যাডমিন দেখবেন।') }}</p>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button variant="danger" :busy="busy" @click="confirm">{{ tr('Report', 'রিপোর্ট করুন') }}</Button>
    </template>
  </Dialog>
</template>
