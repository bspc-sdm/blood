<script setup lang="ts">
import { Field } from '@/ui/kit';
import { tr } from '@/lib/i18n';

const props = defineProps<{ openAt: string; closeAt: string }>();
const emit = defineEmits<{ change: [o: string, c: string] }>();
const val = (e: Event) => (e.target as HTMLInputElement).value;
</script>

<template>
  <div class="work-two">
    <Field :label="tr('Opens at', 'খোলে')">
      <input type="time" class="input" :value="openAt" @input="emit('change', val($event), props.closeAt)" />
    </Field>
    <Field :label="tr('Closes at', 'বন্ধ হয়')">
      <input type="time" class="input" :value="closeAt" @input="emit('change', props.openAt, val($event))" />
    </Field>
  </div>
</template>
