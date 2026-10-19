<script setup lang="ts">
// লেখার ঘর (লেবেল, ইঙ্গিত, ভুল) — v-model; বাকি সব attribute (type, inputmode, maxlength …) সরাসরি <input>-এ।
// React-এর নিয়ন্ত্রিত ঘরের মতো: বদলানো মান (যেমন শুধু অঙ্ক রাখা) ঘরেও বসে।
import { nextTick, onMounted, ref, useAttrs, useId } from 'vue';
import Field from './Field.vue';

defineOptions({ inheritAttrs: false });
const props = defineProps<{ label?: string | null; hint?: string | null; error?: string | null; modelValue?: string | number | null; id?: string }>();
const emit = defineEmits<{ 'update:modelValue': [v: string] }>();
const attrs = useAttrs();
const autoId = useId();
const el = ref<HTMLInputElement | null>(null);

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value);
  nextTick(() => {
    if (el.value && props.modelValue != null && el.value.value !== String(props.modelValue)) el.value.value = String(props.modelValue);
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
    <input :id="id ?? autoId" ref="el" class="input" v-bind="$attrs" :value="modelValue ?? ''" @input="onInput" />
  </Field>
</template>
