<script setup lang="ts">
// প্রেসক্রিপশন বাছা থাকলে: কত % ওষুধ আছে, মোট ছাড় (দাম না জানলে দোকানের সাধারণ %), আনুমানিক দাম; সবসময় ছাড়ের শর্ত
// আর (অনলাইনে) ডেলিভারির চার্জ ও কত টাকার বেশিতে ফ্রি। প্রেসক্রিপশন না থাকলে কিছুই নয় (কার্ডের সাধারণ লাইনগুলোই)।
import { computed } from 'vue';
import { IconLine } from '@/ui/kit';
import { deliveryText, discountTermsText, rxDiscountText, rxHaveText, rxPayableText, type BuyMode, type Pharmacy } from './data';

const props = defineProps<{ p: Pharmacy; mode: BuyMode | null; big?: boolean }>();
const r = computed(() => props.p.rx);
const terms = computed(() => discountTermsText(props.p));
const delivery = computed(() => deliveryText(props.p, props.mode === 'online'));
const color = 'var(--on-surface)';
</script>

<template>
  <div v-if="r" :class="['ph-rxinfo', big && 'big']" data-testid="ph-rxinfo">
    <IconLine :icon="r.keepsStock && r.total ? (r.have === r.total ? 'check_circle' : 'medication') : 'info'" :color="color">
      <span :class="[r.keepsStock && r.total > 0 && 'semi']">{{ rxHaveText(r) }}</span>
    </IconLine>
    <IconLine v-if="r.total" icon="payments" :color="color"><span class="semi">{{ rxPayableText(r) }}</span></IconLine>
    <IconLine v-if="rxDiscountText(p, r)" icon="verified" color="var(--primary)">{{ rxDiscountText(p, r) }}</IconLine>
    <IconLine v-if="terms && !r.belowMin" icon="info">{{ terms }}</IconLine>
    <IconLine v-if="delivery" icon="local_shipping">{{ delivery }}</IconLine>
  </div>
</template>
