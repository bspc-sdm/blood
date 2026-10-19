<script setup lang="ts">
// ফোন নম্বর যাচাই (অ্যাপের Updates.kt): SMS-এ ৬ সংখ্যার কোড
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';

const emit = defineEmits<{ close: []; verified: [] }>();
const sentTo = ref<string | null>(null);
const code = ref('');
const busy = ref(false);
const error = ref<string | null>(null);

async function send() {
  busy.value = true;
  error.value = null;
  try {
    const r = await call<{ to?: string }>('phone_send_code');
    sentTo.value = r.to ?? '';
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function verify() {
  busy.value = true;
  error.value = null;
  try {
    await call('phone_verify_code', { code: toAsciiDigits(code.value) });
    emit('verified');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr('Verify your phone', 'ফোন নম্বর যাচাই')" @close="() => !busy && emit('close')">
    <p class="t-body-m">
      {{ tr('A nurse will come to a home, so we check that the phone number in your profile is really yours.', 'নার্স বাসায় যাবেন, তাই প্রোফাইলের নম্বরটা সত্যিই আপনার কি না যাচাই করা হয়।') }}
    </p>
    <template v-if="sentTo != null">
      <p class="t-body-s">{{ tr('Code sent to ', 'কোড পাঠানো হয়েছে: ') + num(sentTo) }}</p>
      <TextInput
        :label="tr('6-digit code', '৬ সংখ্যার কোড')"
        inputmode="numeric"
        autocomplete="one-time-code"
        autofocus
        :model-value="code"
        @update:model-value="(v) => (code = v.replace(/[^0-9০-৯]/g, '').slice(0, 6))"
      />
      <div><Button variant="text" small :disabled="busy" @click="send">{{ tr('Send again', 'আবার পাঠান') }}</Button></div>
    </template>
    <ErrorText :text="error" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button v-if="sentTo == null" :busy="busy" @click="send">{{ tr('Send code', 'কোড পাঠান') }}</Button>
      <Button v-else :busy="busy" :disabled="toAsciiDigits(code).length !== 6" @click="verify">{{ tr('Verify', 'যাচাই করুন') }}</Button>
    </template>
  </Dialog>
</template>
