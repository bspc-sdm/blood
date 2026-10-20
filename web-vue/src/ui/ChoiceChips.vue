<script setup lang="ts" generic="T extends string | number">
// চিপ দিয়ে এক বা একাধিক বাছাই (অ্যাপের FilterChip)
import Icon from './Icon.vue';

const props = defineProps<{ options: { value: T; label: string }[]; modelValue: T | T[] | null; multi?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [v: any] }>();
const isOn = (v: T) => (props.multi ? (props.modelValue as T[] | null)?.includes(v) ?? false : props.modelValue === v);
function pick(v: T) {
  if (!props.multi) return emit('update:modelValue', v);
  const cur = (props.modelValue as T[] | null) ?? [];
  emit('update:modelValue', cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v]);
}
</script>

<template>
  <div class="chips">
    <button v-for="o in options" :key="String(o.value)" type="button" class="chip" :aria-pressed="isOn(o.value)" @click="pick(o.value)">
      <Icon v-if="isOn(o.value)" name="check_circle" />{{ o.label }}
    </button>
  </div>
</template>
