<script setup lang="ts">
// প্রোফাইলে "রোল বদলান" — ডেমো অ্যাকাউন্টে থাকলেই দেখায়
import { tr } from '@/lib/i18n';
import { Dialog } from '@/ui/kit';
import DemoRoleList from './DemoRoleList.vue';

const emit = defineEmits<{ close: []; done: [] }>();
</script>

<template>
  <Dialog :title="tr('Switch role', 'রোল বদলান')" @close="emit('close')">
    <DemoRoleList @done="emit('done')" />
  </Dialog>
</template>
