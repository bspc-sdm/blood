<script setup lang="ts">
// ছোট সারি: কী হলো + ফিরিয়ে আনার বাটন + বন্ধ
import { Action, IconButton } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ text: string; undo: string; busy: boolean }>();
const emit = defineEmits<{ undo: []; dismiss: [] }>();
</script>

<template>
  <div class="row blood-undo">
    <span class="t-body-m grow clamp2">{{ text }}</span>
    <Action icon="refresh" :busy="busy" @click="emit('undo')">{{ undo }}</Action>
    <IconButton icon="close" small :label="tr('Close', 'বন্ধ')" @click="emit('dismiss')" />
  </div>
</template>
