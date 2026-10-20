<script setup lang="ts">
// পাঠানোর সময়ের আনুমানিক দাম: "আনুমানিক ৳২,৩৪০ · সব পরীক্ষায় ১০% ছাড় · দামের তালিকায় নেই: ECG"
import { num, tr } from '@/lib/i18n';
import { estimateText, taka, type PriceSnap } from '@/lib/labprice';

defineProps<{ p: PriceSnap }>();
</script>

<template>
  <div class="col gap-2">
    <span class="t-body-m semi">{{ estimateText(p.estimate) }}<span v-if="p.discountPct" class="muted"> ({{ taka(p.subtotal) }}, {{ tr(`${num(p.discountPct)}% off`, `${num(p.discountPct)}% ছাড়`) }})</span></span>
    <span v-if="p.missing.length" class="t-body-s muted">{{ tr('Not in the price list: ', 'দামের তালিকায় নেই: ') + p.missing.join(', ') }}</span>
  </div>
</template>
