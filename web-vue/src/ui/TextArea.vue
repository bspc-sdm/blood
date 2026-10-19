<script setup lang="ts">
import { nextTick, onMounted, ref, useAttrs, useId } from 'vue';
import Field from './Field.vue';

defineOptions({ inheritAttrs: false });
const props = defineProps<{ label?: string | null; hint?: string | null; error?: string | null; modelValue?: string | null; id?: string }>();
const emit = defineEmits<{ 'update:modelValue': [v: string] }>();
const attrs = useAttrs();
const autoId = useId();
const el = ref<HTMLTextAreaElement | null>(null);

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value);
  nextTick(() => {
    if (el.value && props.modelValue != null && el.value.value !== props.modelValue) el.value.value = props.modelValue;
  });
}
onMounted(() => {
  if (attrs.autofocus !== undefined && attrs.autofocus !== false) el.value?.focus();
});
defineExpose({ el });
</script>

<template>
  <Field :label="label" :hint="hint" :error="error" :html-for="id ?? autoId">
    <template v-if="$slots.label" #label><slot name="label" /></template>
    <template v-if="$slots.hint" #hint><slot name="hint" /></template>
    <textarea :id="id ?? autoId" ref="el" class="textarea" v-bind="$attrs" :value="modelValue ?? ''" @input="onInput" />
  </Field>
</template>
