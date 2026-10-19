<script setup lang="ts">
// ফোন যাচাই (বাসায় সেবার জন্য)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';

const emit = defineEmits<{ close: []; verified: [] }>();
const sentTo = ref<string | null>(null);
const code = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ to?: string }>('phone_send_code');
    sentTo.value = r.to || '';
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function verify() {
  busy.value = true;
  err.value = null;
  try {
    await call('phone_verify_code', { code: code.value });
    emit('verified');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="tr('Verify your phone', 'ফোন নম্বর যাচাই')" size="small" @close="() => !busy && emit('close')">
    <p class="t-body-m">{{ tr('A nurse will come to a home, so we check that the phone number in your profile is really yours.', 'নার্স বাসায় যাবেন, তাই প্রোফাইলের নম্বরটা সত্যিই আপনার কি না যাচাই করা হয়।') }}</p>
    <template v-if="sentTo != null">
      <p class="t-body-s">{{ tr('Code sent to ', 'কোড পাঠানো হয়েছে: ') + num(sentTo) }}</p>
      <TextInput :label="tr('6-digit code', '৬ সংখ্যার কোড')" inputmode="numeric" :model-value="code" @update:model-value="(v) => (code = v.replace(/[^0-9]/g, '').slice(0, 6))" />
      <Button variant="text" :disabled="busy" @click="send">{{ tr('Send again', 'আবার পাঠান') }}</Button>
    </template>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button v-if="sentTo == null" :busy="busy" @click="send">{{ tr('Send code', 'কোড পাঠান') }}</Button>
      <Button v-else :busy="busy" :disabled="code.length !== 6" @click="verify">{{ tr('Verify', 'যাচাই করুন') }}</Button>
    </template>
  </Dialog>
</template>
