<script setup lang="ts">
// পাতা ১: তালিকা + বিস্তারিত (ডেস্কটপ/বড় ট্যাবলেটে পাশাপাশি; মোবাইলে বিস্তারিত আলাদা পুরো পাতা)
// list — তালিকার অংশ (default slot); detail — বাছা জিনিসের বিস্তারিত (DetailPane দিয়ে মোড়ানো), না থাকলে hasDetail=false;
// placeholder — বড় পর্দায় কিছু বাছা না থাকলে ডান ভাগে যা থাকে
import { tr } from '@/lib/i18n';

defineProps<{ hasDetail: boolean; flush?: boolean }>();
</script>

<template>
  <div :class="['ld', hasDetail ? 'two' : 'one']">
    <section :class="['ld-list', flush && 'flush']"><slot /></section>
    <aside class="ld-detail" :aria-label="tr('Details', 'বিস্তারিত')">
      <slot v-if="hasDetail" name="detail" />
      <div v-else-if="$slots.placeholder" class="ld-placeholder ld-detail-inner"><slot name="placeholder" /></div>
    </aside>
  </div>
</template>
