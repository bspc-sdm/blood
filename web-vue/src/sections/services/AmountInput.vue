<script setup lang="ts">
import { TextInput } from '@/ui/kit';

defineProps<{ label: string; modelValue: string; hint?: string }>();
const emit = defineEmits<{ 'update:modelValue': [v: string] }>();
</script>

<template>
  <TextInput :label="label" :hint="hint" inputmode="numeric" placeholder="৳" maxlength="7" :model-value="modelValue" @update:model-value="(v: string) => emit('update:modelValue', v)" />
</template>
