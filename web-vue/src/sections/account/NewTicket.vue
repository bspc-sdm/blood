<script setup lang="ts">
// নতুন অনুরোধ: বিষয় আর লেখা
import { computed, ref } from 'vue';
import { Button, ChoiceChips, ErrorText, TextArea } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { TICKET_CATEGORIES, ticketLabel } from './util';

const props = defineProps<{ start: string | null }>();
const emit = defineEmits<{ sent: [id: string | null] }>();
const category = ref<string | null>(props.start && [...TICKET_CATEGORIES, 'homevisit', 'medorder'].includes(props.start) ? props.start : null);
const text = ref('');
const busy = ref(false);
const error = ref<string | null>(null);
const cats = computed(() => (category.value && !TICKET_CATEGORIES.includes(category.value) ? [...TICKET_CATEGORIES, category.value] : TICKET_CATEGORIES));

async function send() {
  if (!category.value) return;
  busy.value = true;
  error.value = null;
  try {
    const r = await call<{ id?: string }>('ticket_create', { category: category.value, text: text.value.trim() });
    emit('sent', r.id ?? null);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <span class="t-title-s">{{ tr('What is it about?', 'কী নিয়ে?') }}</span>
  <ChoiceChips v-model="category" :options="cats.map((c) => ({ value: c, label: ticketLabel(c) }))" />
  <TextArea v-model="text" :label="tr('Describe the problem', 'সমস্যাটা খুলে লিখুন')" :rows="5" maxlength="1500" />
  <ErrorText :text="error" />
  <div class="form-actions">
    <Button icon="send" :busy="busy" :disabled="!category || text.trim().length < 10" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
  </div>
</template>
