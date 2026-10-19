<script setup lang="ts">
import { computed } from 'vue';
import { ChoiceChips } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { BLOOD_GROUPS, UNKNOWN_GROUP } from '@/lib/rules';

const props = defineProps<{ modelValue: string; allowUnknown?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [g: string] }>();
const opts = computed(() => {
  const o = BLOOD_GROUPS.map((g) => ({ value: g, label: g }));
  if (props.allowUnknown) o.push({ value: UNKNOWN_GROUP, label: tr("I don't know", 'জানি না') });
  return o;
});
</script>

<template>
  <div class="col gap-6">
    <ChoiceChips :options="opts" :model-value="modelValue" @update:model-value="(g: string) => emit('update:modelValue', g)" />
    <p v-if="modelValue === UNKNOWN_GROUP" class="t-body-s muted">
      {{ tr(
        "You won't get donation requests until you add your group. Get it tested at a hospital or blood bank, then update your profile.",
        'গ্রুপ না দেওয়া পর্যন্ত রক্তদানের নোটিফিকেশন আসবে না। হাসপাতাল বা ব্লাড ব্যাংকে পরীক্ষা করে জেনে প্রোফাইলে দিন।',
      ) }}
    </p>
  </div>
</template>
