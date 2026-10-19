<script setup lang="ts">
import { ref } from 'vue';
import { Field, Icon } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import type { AreaValue } from '@/ui/areaFilter';
import { tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { useAreas } from './shared';

defineProps<{ label: string; value: AreaValue | null; error?: string }>();
const emit = defineEmits<{ change: [v: AreaValue] }>();
const open = ref(false);
useAreas();
function picked(v: AreaValue) {
  emit('change', { district: v.district, upazilaId: v.upazilaId ?? null });
  open.value = false;
}
</script>

<template>
  <Field :label="label" :error="error">
    <button type="button" class="area-btn work-area-btn" @click="open = true">
      <Icon name="location_on" />
      <span>{{ value ? areaLabel(value.district, value.upazilaId) : tr('Choose area', 'এলাকা বেছে নিন') }}</span>
      <Icon name="expand_more_round" />
    </button>
    <AreaPicker v-if="open" :value="value" @picked="picked" @close="open = false" />
  </Field>
</template>
