<script setup lang="ts">
// ফিল্টার প্যানেল (অ্যাপের FilterSheet)
import { Button, Dialog } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ title: string; onClear?: () => void }>();
const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <Dialog :title="title" @close="emit('close')">
    <slot />
    <template #actions>
      <Button v-if="onClear" variant="outline" @click="onClear()">{{ tr('Clear all', 'সব মুছুন') }}</Button>
      <Button @click="emit('close')">{{ tr('Show', 'দেখান') }}</Button>
    </template>
  </Dialog>
</template>
