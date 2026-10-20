<script setup lang="ts">
import { Switch } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>();
</script>

<template>
  <Switch
    :model-value="modelValue"
    :label="tr('I\'ll pay donors\' travel cost', 'ডোনারের যাতায়াত খরচ দেব')"
    :sub="tr('Travel cost only — buying or selling blood is not allowed. Donors farther away may need this.', 'শুধু যাতায়াত খরচ — রক্ত কেনাবেচা নিষিদ্ধ। দূরের ডোনারদের এটা লাগতে পারে।')"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  />
</template>
