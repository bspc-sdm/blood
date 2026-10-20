<script setup lang="ts">
// তথ্য সংশোধনের অনুরোধ — শেষ রক্তদানের তারিখ অ্যাডমিন দেখে ঠিক করেন (correction_create / correction_mine)।
// নিজে "তারিখ বদলান"-এ না পারলে (যেমন আজকের সীমা শেষ) বা অ্যাডমিনকে জানাতে চাইলে।
import { computed, ref } from 'vue';
import { Button, Check, Dialog, ErrorText, InfoText, TextArea, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useCall } from '@/lib/query';
import { dateInputValue, formatDate, fromDateInput, todayUtcMidnight } from '@/lib/format';
import type { Profile } from '@/lib/profile';

interface CorrectionItem { id: string; status: 'pending' | 'approved' | 'rejected'; requestedMillis: number | null; createdAtMillis: number }

const props = defineProps<{ profile: Profile }>();
const emit = defineEmits<{ close: []; sent: [] }>();
const latest = useCall<{ item: CorrectionItem | null }>('correction_mine');
const never = ref(false);
const date = ref<number | null>(null);
const reason = ref('');
const busy = ref(false);
const error = ref<string | null>(null);
const item = computed(() => latest.data?.item ?? null);
const waiting = computed(() => item.value?.status === 'pending');
const can = computed(() => !busy.value && !waiting.value && (never.value || date.value != null) && reason.value.trim().length > 0);
const current = props.profile.lastDonationMillis ?? null;

// নতুন লেখা (React-এ ছিল না; পুরোনো অ্যাপের Corrections.kt থেকে)
const T = {
  title: () => tr('Ask to correct the date', 'তথ্য সংশোধনের অনুরোধ'), // bn-ok
  now: () => tr('Last donation date now: ', 'এখন শেষ রক্তদানের তারিখ: '), // bn-ok
  how: () => tr('An admin checks the request and fixes the date. You will get a notification.', 'অ্যাডমিন অনুরোধ দেখে তারিখ ঠিক করে দেবেন। নোটিফিকেশন পাবেন।'), // bn-ok
  never: () => tr('I have actually never donated', 'আসলে কখনো রক্ত দিইনি'), // bn-ok
  date: () => tr('The correct date', 'সঠিক তারিখ'), // bn-ok
  what: () => tr('What was wrong?', 'কী ভুল হয়েছিল?'), // bn-ok
};

function statusLine(c: CorrectionItem): string {
  const asked = c.requestedMillis != null ? formatDate(c.requestedMillis) : tr('Never donated', 'কখনো রক্ত দিইনি');
  if (c.status === 'pending') return tr('Your request is with the admin: ', 'আপনার অনুরোধ অ্যাডমিনের কাছে আছে: ') + asked; // bn-ok
  if (c.status === 'approved') return tr('Your last request was approved.', 'আপনার শেষ অনুরোধটি অনুমোদিত হয়েছে।'); // bn-ok
  return tr('Your last request was not approved.', 'আপনার শেষ অনুরোধটি বাতিল হয়েছে।'); // bn-ok
}

async function send() {
  if (!never.value && (date.value ?? 0) > todayUtcMidnight()) {
    error.value = tr("Date can't be in the future", 'ভবিষ্যতের তারিখ দেওয়া যাবে না');
    return;
  }
  busy.value = true;
  error.value = null;
  try {
    await call('correction_create', { requestedMillis: never.value ? null : date.value, reason: reason.value.trim() });
    emit('sent');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="T.title()" @close="() => !busy && emit('close')">
    <p class="t-body-m muted">
      {{ T.now() + (current != null ? formatDate(current) : tr('Never donated', 'কখনো দেননি')) }}
    </p>
    <InfoText v-if="item" :text="statusLine(item)" />
    <template v-if="!waiting">
      <p class="t-body-s muted">{{ T.how() }}</p>
      <Check v-model="never">{{ T.never() }}</Check>
      <TextInput
        v-if="!never"
        type="date"
        :label="T.date()"
        :max="dateInputValue(todayUtcMidnight())"
        :model-value="dateInputValue(date)"
        @update:model-value="(v: string) => (date = fromDateInput(v))"
      />
      <TextArea v-model="reason" :label="T.what()" maxlength="300" rows="3" />
    </template>
    <ErrorText :text="error" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ waiting ? tr('Close', 'বন্ধ করুন') : tr('Cancel', 'বাতিল') }}</Button>
      <Button v-if="!waiting" :busy="busy" :disabled="!can" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
