<script setup lang="ts">
import { ChoiceChips, Field } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { WEEK_ORDER, weekdayShort } from './shared';

const props = defineProps<{ value: number[] }>();
const emit = defineEmits<{ change: [v: number[]] }>();
</script>

<template>
  <Field :label="tr('Weekly closed days (if any)', 'সাপ্তাহিক বন্ধ (থাকলে)')">
    <ChoiceChips
      multi
      :options="WEEK_ORDER.map((d) => ({ value: d, label: weekdayShort(d) }))"
      :model-value="value"
      @update:model-value="(v: number[]) => emit('change', v.length > 6 ? props.value : v)"
    />
  </Field>
</template>
