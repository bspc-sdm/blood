<script setup lang="ts">
// প্রোফাইলের ছবি থাকলে: বদলান / মুছুন
import { Button, Dialog } from '@/ui/kit';
import { tr } from '@/lib/i18n';

const emit = defineEmits<{ close: []; change: []; remove: [] }>();
function remove() {
  emit('close');
  emit('remove');
}
function change() {
  emit('close');
  emit('change');
}
</script>

<template>
  <Dialog size="small" :title="tr('Profile photo', 'প্রোফাইলের ছবি')" @close="emit('close')">
    <template #actions>
      <Button variant="danger-text" icon="delete" @click="remove">{{ tr('Remove', 'মুছুন') }}</Button>
      <Button icon="photo_camera" @click="change">{{ tr('Change', 'বদলান') }}</Button>
    </template>
  </Dialog>
</template>
