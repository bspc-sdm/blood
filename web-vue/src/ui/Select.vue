<script setup lang="ts">
import { useId } from 'vue';
import Field from './Field.vue';

defineOptions({ inheritAttrs: false });
defineProps<{ label?: string | null; hint?: string | null; error?: string | null; options: { value: string; label: string }[]; placeholder?: string; modelValue?: string | null; id?: string }>();
const emit = defineEmits<{ 'update:modelValue': [v: string] }>();
const autoId = useId();
</script>

<template>
  <Field :label="label" :hint="hint" :error="error" :html-for="id ?? autoId">
    <select :id="id ?? autoId" class="select" v-bind="$attrs" :value="modelValue ?? ''" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
      <option v-if="placeholder != null" value="">{{ placeholder }}</option>
      <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>
  </Field>
</template>
