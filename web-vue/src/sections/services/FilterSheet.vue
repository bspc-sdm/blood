<script setup lang="ts">
// ফিল্টার শিট (অ্যাপের FilterSheet)
import { Button, Dialog } from '@/ui/kit';
import { tr } from '@/lib/i18n';

const emit = defineEmits<{ close: []; clear: [] }>();
</script>

<template>
  <Dialog :title="tr('Filter', 'ফিল্টার')" @close="emit('close')">
    <slot />
    <template #actions>
      <Button variant="outline" @click="emit('clear')">{{ tr('Clear all', 'সব মুছুন') }}</Button>
      <Button @click="emit('close')">{{ tr('Show', 'দেখান') }}</Button>
    </template>
  </Dialog>
</template>
