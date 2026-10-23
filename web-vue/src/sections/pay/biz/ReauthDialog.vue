<script setup lang="ts">
// নিরাপত্তা পর্যালোচনা P4: পেমেন্টের নম্বর বদলাতে ১০ মিনিটের মধ্যের লগইন লাগে (সার্ভার 401 `reauth_required`) —
// নিজের ইমেইলে কোড, কোড দিয়ে আবার লগইন (নতুন টোকেন), তারপর একই কাজ আবার (retry)।
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { T } from '../text';

const props = defineProps<{ retry: () => Promise<void> }>();
const emit = defineEmits<{ close: []; done: [] }>();
const s = useSession();
const sent = ref(false);
const code = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await s.sendCode(s.email);
    sent.value = true;
    toast(T.codeSent());
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
    await s.signIn(s.email, code.value);
    await props.retry();
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="T.reauthTitle()" @close="() => !busy && emit('close')">
    <p class="t-body-m">{{ T.reauthText(s.email) }}</p>
    <TextInput
      v-if="sent"
      :label="T.codeLabel()"
      inputmode="numeric"
      autocomplete="one-time-code"
      maxlength="6"
      data-testid="reauth-code"
      :model-value="code"
      @update:model-value="(v: string) => (code = toAsciiDigits(v).replace(/\D/g, '').slice(0, 6))"
    />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button v-if="!sent" :busy="busy" data-testid="reauth-send" @click="send">{{ T.sendCode() }}</Button>
      <Button v-else :busy="busy" :disabled="code.length < 6" data-testid="reauth-verify" @click="verify">{{ T.verify() }}</Button>
    </template>
  </Dialog>
</template>
