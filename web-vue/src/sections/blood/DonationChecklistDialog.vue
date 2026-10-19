<script setup lang="ts">
// "রক্ত দেব" চাপার আগে স্বাস্থ্য যাচাই (উত্তর কোথাও সেভ হয় না)
import { computed, ref } from 'vue';
import { Button, Check, Dialog } from '@/ui/kit';
import { tr } from '@/lib/i18n';

const emit = defineEmits<{ dismiss: []; confirm: [] }>();
const items = computed(() => [
  tr('I feel well today and slept and ate well', 'আজ সুস্থ বোধ করছি, ভালো ঘুম ও খাওয়া হয়েছে'),
  tr('No fever, cold or antibiotics in the last 7 days', 'গত ৭ দিনে জ্বর, সর্দি-কাশি বা অ্যান্টিবায়োটিক নেই'),
  tr('No surgery, tattoo, piercing or transfusion in 6 months', 'গত ৬ মাসে অপারেশন, ট্যাটু, কান-নাক ফোঁড়ানো বা রক্ত নেওয়া হয়নি'),
  tr('No hepatitis B/C, HIV, malaria or heart disease', 'হেপাটাইটিস বি/সি, এইচআইভি, ম্যালেরিয়া বা হৃদরোগ নেই'),
  tr('Not pregnant or breastfeeding (women)', 'গর্ভবতী নই বা বুকের দুধ খাওয়াচ্ছি না (নারীদের জন্য)'),
]);
const checked = ref<number[]>([]);
const set = (i: number, v: boolean) => (checked.value = v ? [...checked.value, i] : checked.value.filter((x) => x !== i));
</script>

<template>
  <Dialog :title="tr('Before you donate', 'রক্ত দেওয়ার আগে')" @close="emit('dismiss')">
    <div class="col gap-4">
      <Check v-for="(t, i) in items" :key="i" :model-value="checked.includes(i)" @update:model-value="(v: boolean) => set(i, v)">{{ t }}</Check>
    </div>
    <template #actions>
      <Button variant="text" @click="emit('dismiss')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :disabled="checked.length !== items.length" @click="emit('confirm')">{{ tr("I'll donate", 'রক্ত দেব') }}</Button>
    </template>
  </Dialog>
</template>
