<script setup lang="ts">
// কারণ বেছে বাতিল (রোগী ও চালক)
import { ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';

const props = defineProps<{ title: string; reasons: { value: string; label: string }[]; onPick: (r: string) => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const v = ref<string | null>(null);
const busy = ref(false);
const err = ref<string | null>(null);
async function pick() {
  busy.value = true;
  try {
    await props.onPick(v.value!);
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="title" @close="!busy && emit('close')">
    <ChoiceChips v-model="v" :options="reasons" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Back', 'ফিরে যান') }}</Button>
      <Button variant="danger" :busy="busy" :disabled="!v" @click="pick">{{ tr('Cancel request', 'বাতিল করুন') }}</Button>
    </template>
  </Dialog>
</template>
