<script setup lang="ts">
import { tr } from '@/lib/i18n';
import UndoNotice from './UndoNotice.vue';

defineProps<{ name: string; busy: boolean }>();
const emit = defineEmits<{ undo: []; dismiss: [] }>();
</script>

<template>
  <UndoNotice :text="tr(`${name} was removed`, `${name}-কে বাদ দেওয়া হয়েছে`)" :undo="tr('Undo', 'ফিরিয়ে আনুন')" :busy="busy" @undo="emit('undo')" @dismiss="emit('dismiss')" />
</template>
