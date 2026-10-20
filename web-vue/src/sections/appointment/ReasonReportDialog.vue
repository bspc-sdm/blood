<script setup lang="ts">
// সমস্যা জানান (অ্যাপের ReasonReportDialog)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextArea } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';

const props = defineProps<{ onSubmit: (reason: string) => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const reason = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function submit() {
  busy.value = true;
  err.value = null;
  try {
    await props.onSubmit(reason.value.trim());
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="tr('Report a problem', 'সমস্যা জানান')" size="small" @close="() => !busy && emit('close')">
    <p class="t-body-m">{{ tr("Wrong number, overcharging, didn't come…", 'ভুল নম্বর, বেশি টাকা চাওয়া, আসেনি…') }}</p>
    <TextArea :label="tr('What happened', 'কী হয়েছে')" :rows="3" :model-value="reason" @update:model-value="(v) => (reason = v.slice(0, 300))" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!reason.trim()" @click="submit">{{ tr('Report', 'রিপোর্ট') }}</Button>
    </template>
  </Dialog>
</template>
