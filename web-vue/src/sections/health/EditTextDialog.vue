<script setup lang="ts">
// নিজের লেখা বদলানো (প্রশ্ন, উত্তর; টিপস হলে শিরোনামও) — অ্যাপের EditTextDialog
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, TextArea, TextInput } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';

const props = defineProps<{ title: string; initial: string; maxLen: number; initialTitle?: string; onSave: (text: string, head: string) => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const text = ref(props.initial);
const head = ref(props.initialTitle ?? '');
const busy = ref(false);
const err = ref<string | null>(null);
const ok = computed(() => !!text.value.trim() && (props.initialTitle == null || !!head.value.trim()));
async function save() {
  busy.value = true;
  err.value = null;
  try {
    await props.onSave(text.value.trim(), head.value.trim());
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <Dialog :title="title" @close="!busy && emit('close')">
    <TextInput v-if="initialTitle != null" v-model="head" :label="tr('Title', 'শিরোনাম')" maxlength="120" />
    <TextArea v-model="text" :maxlength="maxLen" rows="6" :aria-label="title" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!ok" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
    </template>
  </Dialog>
</template>
