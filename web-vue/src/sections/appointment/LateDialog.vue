<script setup lang="ts">
// "দেরি হবে": কত মিনিট আর নোট
import { ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import type { Appointment } from './model';
import { LATE_MINUTES, type LateInfo } from './queue';

const props = defineProps<{ a: Appointment; current?: LateInfo | null }>();
const emit = defineEmits<{ close: []; sent: [] }>();
const min = ref<number | null>(props.current?.min ?? null);
const note = ref(props.current?.note ?? '');
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  if (!min.value) {
    err.value = tr('Choose how late', 'কত দেরি হবে বেছে নিন');
    return;
  }
  busy.value = true;
  err.value = null;
  try {
    await call('appointment_late', { id: props.a.id, minutes: min.value, note: note.value.trim() });
    toast(tr('Sent to the doctor', 'ডাক্তারকে পাঠানো হয়েছে'));
    emit('sent');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr('I will be late', 'দেরি হবে')" @close="() => !busy && emit('close')">
    <ChoiceChips v-model="min" :options="LATE_MINUTES.map((m) => ({ value: m, label: tr(`${m} min`, `${num(m)} মিনিট`) }))" />
    <TextInput :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')" :placeholder="tr('e.g. Stuck in traffic', 'যেমন: জ্যামে আছি')" :model-value="note" @update:model-value="(v) => (note = v.slice(0, 100))" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
