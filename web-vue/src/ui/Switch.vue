<script setup lang="ts">
import { useId } from 'vue';

defineProps<{ modelValue: boolean; label?: string; sub?: string | null; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>();
const id = useId();
</script>

<template>
  <div class="row gap-16" :style="{ minHeight: '56px' }">
    <label :for="id" class="col gap-2 grow" :style="{ cursor: 'pointer' }">
      <span class="t-body-l"><slot name="label">{{ label }}</slot></span>
      <span v-if="sub || $slots.sub" class="t-body-s muted"><slot name="sub">{{ sub }}</slot></span>
    </label>
    <input :id="id" type="checkbox" role="switch" class="switch" :checked="modelValue" :disabled="disabled" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)" />
  </div>
</template>
