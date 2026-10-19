<script setup lang="ts">
// সময় নিশ্চিত: দিন (আজ/কাল/পরশু), সময়ের ভাগ, ঠিক সময় (আধা ঘণ্টা পরপর), নোট — রোগী সাথে সাথে জানেন
import { ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { todayUtcMidnight } from '@/lib/format';
import { SLOT_KEYS, dayWord, hhmmText, slotText, slotTimes, wantedText, type LabHome, type Slot } from '@/lib/labprice';
import { DAY } from './labdetail';

const props = defineProps<{ id: string; home: LabHome }>();
const emit = defineEmits<{ close: []; done: [] }>();
const today = todayUtcMidnight();
const days = [0, 1, 2].map((k) => today + k * DAY);
const h = props.home;
const startDay = h.status === 'confirmed' && h.visitDate >= today ? h.visitDate : days.includes(h.date) ? h.date : today + DAY;
const date = ref(startDay);
const startSlot = (SLOT_KEYS.find((s) => h.time && slotTimes(s).includes(h.time)) ?? (h.slot || 'morning')) as Slot;
const slot = ref<Slot>(startSlot);
const time = ref(h.time && slotTimes(startSlot).includes(h.time) ? h.time : slotTimes(startSlot)[0]);
const note = ref(h.confirmNote || '');
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await call('lab_home_confirm', { id: props.id, date: date.value, time: time.value, note: note.value.trim() });
    emit('done');
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
function pickSlot(s: Slot) {
  slot.value = s;
  time.value = slotTimes(s)[0];
}
</script>

<template>
  <Dialog :title="tr('Confirm the time', 'সময় নিশ্চিত করুন')" @close="() => !busy && emit('close')">
    <span class="t-body-s muted">{{ tr('Wants: ', 'চেয়েছেন: ') + wantedText(home) }}</span>
    <span class="t-label-l">{{ tr('Day', 'দিন') }}</span>
    <ChoiceChips v-model="date" :options="days.map((d) => ({ value: d, label: dayWord(d) }))" />
    <span class="t-label-l">{{ tr('Time', 'সময়') }}</span>
    <ChoiceChips :model-value="slot" :options="SLOT_KEYS.map((s) => ({ value: s, label: slotText(s) }))" @update:model-value="pickSlot" />
    <ChoiceChips v-model="time" :options="slotTimes(slot).map((t) => ({ value: t, label: hhmmText(t) }))" />
    <TextInput v-model="note" :label="tr('Note for the patient (optional)', 'রোগীর জন্য নোট (ঐচ্ছিক)')" maxlength="120" :placeholder="tr('e.g. Come with an empty stomach', 'যেমন: খালি পেটে থাকবেন')" />
    <span class="t-body-m semi c-primary">{{ `${dayWord(date)} ${hhmmText(time)}` }}</span>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" icon="send" @click="send">{{ tr('Tell the patient', 'রোগীকে জানান') }}</Button>
    </template>
  </Dialog>
</template>
