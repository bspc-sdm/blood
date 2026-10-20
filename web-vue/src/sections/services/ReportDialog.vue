<script setup lang="ts">
// রিপোর্ট (অ্যাপের ReasonReportDialog)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextArea, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { reportKind, type Kind } from './data';

const props = defineProps<{ kind: Kind; id: string }>();
const emit = defineEmits<{ close: [] }>();
const reason = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function submit() {
  busy.value = true;
  err.value = null;
  try {
    await call('report', { kind: reportKind(props.kind), id: props.id, reason: reason.value.trim() });
    toast(tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।'));
    emit('close');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr('Report a problem', 'সমস্যা জানান')" @close="!busy && emit('close')">
    <p class="t-body-m">{{ tr("Wrong number, overcharging, didn't come…", 'ভুল নম্বর, বেশি টাকা চাওয়া, আসেনি…') }}</p>
    <TextArea v-model="reason" :label="tr('What happened', 'কী হয়েছে')" rows="3" maxlength="300" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button variant="text" :busy="busy" :disabled="!reason.trim()" @click="submit">{{ tr('Report', 'রিপোর্ট') }}</Button>
    </template>
  </Dialog>
</template>
