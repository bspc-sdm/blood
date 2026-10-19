<script setup lang="ts">
import { computed } from 'vue';
import { ChoiceChips } from '@/ui/kit';
import { num } from '@/lib/i18n';

const props = defineProps<{ modelValue: number }>();
const emit = defineEmits<{ 'update:modelValue': [n: number] }>();
const opts = computed(() => [1, 2, 3, 4, 5, ...(props.modelValue > 5 ? [props.modelValue] : [])].map((n) => ({ value: n, label: num(n) })));
</script>

<template>
  <ChoiceChips :options="opts" :model-value="modelValue" @update:model-value="(n: number) => emit('update:modelValue', n)" />
</template>
