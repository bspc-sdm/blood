<script setup lang="ts">
// নিজের খাতার সিরিয়াল (ফোনে/সরাসরি নেওয়া রোগীসহ); খালি রাখলে অ্যাপ নিজে পরের নম্বর দেয়
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { formatDate } from '@/lib/format';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { apptChamber, type Appointment } from '../model';

const props = defineProps<{ a: Appointment; next: number; onConfirm: (serial: number | null, note: string) => Promise<boolean>; busy: boolean; error: string | null }>();
const emit = defineEmits<{ close: [] }>();
const serial = ref('');
const note = ref('');
</script>

<template>
  <Dialog size="small" :title="tr('Confirm appointment', 'অ্যাপয়েন্টমেন্ট নিশ্চিত')" @close="() => !busy && emit('close')">
    <div class="col gap-2">
      <span class="t-title-m">{{ a.otherName }}</span>
      <span class="t-body-s muted">{{ formatDate(a.date) }}{{ a.chamber ? ' · ' + apptChamber(a) : '' }}</span>
    </div>
    <TextInput
      :label="tr('Serial no. (optional)', 'সিরিয়াল নম্বর (ঐচ্ছিক)')"
      :hint="tr('Leave empty and the app gives the next number', 'খালি রাখলে অ্যাপ পরের নম্বর দেবে')"
      inputmode="numeric"
      :placeholder="num(next)"
      :model-value="serial"
      @update:model-value="(v) => (serial = v.replace(/[^0-9০-৯]/g, '').slice(0, 3))"
    />
    <TextInput
      :label="tr('Note for patient (optional)', 'রোগীর জন্য নোট (ঐচ্ছিক)')"
      :placeholder="tr('e.g. Come at 6 PM', 'যেমন: সন্ধ্যা ৬টায় আসবেন')"
      :model-value="note"
      @update:model-value="(v) => (note = v.slice(0, 200))"
    />
    <ErrorText :text="error" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="props.onConfirm(parseInt(toAsciiDigits(serial), 10) || null, note.trim())">{{ tr('Confirm', 'নিশ্চিত করুন') }}</Button>
    </template>
  </Dialog>
</template>
