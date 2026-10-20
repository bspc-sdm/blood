<script setup lang="ts">
// পাঠানোর ভুল: যেখানে চাপা হয়েছে সেই ডায়ালগেই, লাল ঘরে আইকনসহ — আর চোখের সামনে স্ক্রল করে আনা (লম্বা তালিকার নিচে চাপলেও দেখা যায়)
import { nextTick, ref, watch } from 'vue';
import { Icon } from '@/ui/kit';

const props = defineProps<{ text?: string | null }>();
const el = ref<HTMLDivElement | null>(null);
watch(
  () => props.text,
  (t) => {
    if (t) nextTick(() => el.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }));
  },
  { immediate: true },
);
</script>

<template>
  <div v-if="text" ref="el" class="card danger row gap-8 health-send-err" role="alert">
    <Icon name="warning" :size="20" />
    <span class="t-body-m">{{ text }}</span>
  </div>
</template>
