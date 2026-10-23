<script setup lang="ts">
// "আমার কাজ"-এর কাজের সারি — পাঁচ ধরনেই একই ভাগ: নতুন / চলছে / শেষ (সংখ্যাসহ)। কোন জিনিস কোন ভাগে, তা পাতা ঠিক করে
// (ফার্মেসি: অর্ডার, প্যাথলজি: অনুরোধ ও বাসা থেকে নমুনা, অ্যাম্বুলেন্স: ডাক, ডাক্তার: সিরিয়াল)।
import { PillTabs } from '@/ui/kit';
import { LT } from './model';

export type QSeg = 'new' | 'active' | 'done';
defineProps<{ value: QSeg; counts: Partial<Record<QSeg, number>>; label: string }>();
const emit = defineEmits<{ change: [k: QSeg] }>();
</script>

<template>
  <div class="rxp-sub-pills q-segs" data-testid="queue-segs">
    <PillTabs
      secondary
      nearest
      :label="label"
      :value="value"
      :tabs="[
        { key: 'new', label: LT.segNew(), count: counts.new },
        { key: 'active', label: LT.segActive(), count: counts.active },
        { key: 'done', label: LT.segDone(), count: counts.done },
      ]"
      @change="(k) => emit('change', k as QSeg)"
    />
  </div>
</template>
