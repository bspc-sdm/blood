<script setup lang="ts">
// "নিশ্চিত?" — ব্রাউজারের confirm() এর বদলে
import { tr } from '@/lib/i18n';
import Dialog from './Dialog.vue';
import Button from './Button.vue';
import ErrorText from './ErrorText.vue';

defineProps<{ title: string; text?: string | null; confirm: string; danger?: boolean; busy?: boolean; error?: string | null }>();
const emit = defineEmits<{ confirm: []; close: [] }>();
</script>

<template>
  <Dialog size="small" :title="title" @close="emit('close')">
    <div v-if="text || $slots.default" class="t-body-m muted"><slot>{{ text }}</slot></div>
    <ErrorText :text="error" />
    <template #actions>
      <Button variant="text" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :variant="danger ? 'danger' : 'filled'" :busy="busy" @click="emit('confirm')">{{ confirm }}</Button>
    </template>
  </Dialog>
</template>
