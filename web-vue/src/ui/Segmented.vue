<script setup lang="ts" generic="T extends string | number">
defineProps<{ modelValue: T; options: { value: T; label: string }[]; label?: string }>();
const emit = defineEmits<{ 'update:modelValue': [v: T] }>();
</script>

<template>
  <div class="seg" role="group" :aria-label="label">
    <button v-for="o in options" :key="String(o.value)" type="button" :aria-pressed="o.value === modelValue" @click="emit('update:modelValue', o.value)">{{ o.label }}</button>
  </div>
</template>
