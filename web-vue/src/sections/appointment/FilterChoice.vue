<script setup lang="ts">
import { ChoiceChips } from '@/ui/kit';

const props = defineProps<{ title: string; options: string[]; value: string | null; label: (c: string) => string }>();
const emit = defineEmits<{ change: [v: string | null] }>();
</script>

<template>
  <div class="col gap-6">
    <span class="t-title-s">{{ title }}</span>
    <ChoiceChips :options="options.map((o) => ({ value: o, label: label(o) }))" :model-value="value" @update:model-value="(v: string) => emit('change', v === props.value ? null : v)" />
  </div>
</template>
