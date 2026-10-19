<script setup lang="ts">
// কেয়ারগিভার যোগ: ইমেইলে আমন্ত্রণ
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';

const emit = defineEmits<{ close: []; sent: [] }>();
const email = ref('');
const busy = ref(false);
const error = ref<string | null>(null);
async function send() {
  busy.value = true;
  error.value = null;
  try {
    await call('care_invite', { email: email.value.trim() });
    emit('sent');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr('Add a caregiver', 'কেয়ারগিভার যোগ করুন')" @close="() => !busy && emit('close')">
    <p class="t-body-m">{{ tr("Their Lifolo account's email. They need to have logged in to Lifolo once.", 'তাঁর Lifolo অ্যাকাউন্টের ইমেইল। তাঁকে অন্তত একবার Lifolo-তে লগইন করে থাকতে হবে।') }}</p>
    <TextInput
      :label="tr('Email', 'ইমেইল')"
      type="email"
      inputmode="email"
      autofocus
      maxlength="200"
      :model-value="email"
      :hint="tr('They\'ll get a notification and must accept.', 'তিনি নোটিফিকেশন পাবেন, রাজি হলে চালু হবে।')"
      @update:model-value="(v) => (email = v.trim())"
    />
    <ErrorText :text="error" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!email.includes('@')" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
