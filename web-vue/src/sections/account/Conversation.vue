<script setup lang="ts">
// একটা অনুরোধের কথোপকথন আর আরও লেখা
import { ref } from 'vue';
import { Button, ErrorText, TextArea, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { ticketStatus, type Ticket } from './util';
import Bubble from './Bubble.vue';

const props = defineProps<{ t: Ticket }>();
const emit = defineEmits<{ sent: [] }>();
const text = ref('');
const busy = ref(false);
const error = ref<string | null>(null);
async function send() {
  busy.value = true;
  error.value = null;
  try {
    await call('ticket_reply', { id: props.t.id, text: text.value.trim() });
    text.value = '';
    toast(tr('Sent.', 'পাঠানো হয়েছে।'));
    emit('sent');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <span class="t-label-l c-primary">{{ ticketStatus(t) }}</span>
  <div class="acct-thread">
    <Bubble v-for="(m, i) in t.messages" :key="i" :m="m" />
  </div>
  <TextArea
    v-model="text"
    :label="t.status === 'closed' ? tr('Write again (reopens it)', 'আবার লিখুন (আবার খুলবে)') : tr('Write more', 'আরও লিখুন')"
    :rows="3"
    maxlength="1500"
  />
  <ErrorText :text="error" />
  <div class="form-actions">
    <Button icon="send" :busy="busy" :disabled="!text.trim()" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
  </div>
</template>
