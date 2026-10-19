<script setup lang="ts">
// রক্ত লাগার সময় পর্যন্ত প্রতি সেকেন্ডে কমতে থাকা কাউন্টডাউন
import { computed } from 'vue';
import { Icon, useNow } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { formatDuration } from '@/lib/format';

const props = defineProps<{ target: number; slotW?: number }>();
const now = useNow();
const left = computed(() => props.target - now.value);
</script>

<template>
  <div class="row blood-count" :style="slotW ? { gap: '12px' } : undefined">
    <span class="blood-slot" :style="slotW ? { width: slotW + 'px' } : undefined"><Icon name="schedule" :size="20" /></span>
    <span class="t-title-m bold c-primary num">{{ left > 0 ? formatDuration(left) : tr("Time's up", 'সময় হয়ে গেছে') }}</span>
    <span v-if="left > 0" class="t-body-m muted">{{ tr('left', 'বাকি') }}</span>
  </div>
</template>
