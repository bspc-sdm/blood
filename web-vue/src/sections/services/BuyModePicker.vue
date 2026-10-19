<script setup lang="ts">
// দুটো বড় ঘর পাশাপাশি — বাছা না থাকলে নিচে তালিকা আসে না
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { BuyMode } from './data';

defineProps<{ value: BuyMode | null }>();
const emit = defineEmits<{ change: [m: BuyMode] }>();
const opts = computed<{ m: BuyMode; icon: string; label: string; sub: string }[]>(() => [
  { m: 'shop', icon: 'storefront', label: tr("I'll buy at the shop", 'দোকানে গিয়ে কিনব'), sub: tr('All pharmacies nearby', 'কাছের সব ফার্মেসি') },
  { m: 'online', icon: 'local_shipping', label: tr('Order online', 'অনলাইনে অর্ডার'), sub: tr('Home delivery', 'বাসায় ডেলিভারি') },
]);
</script>

<template>
  <div class="col gap-6">
    <span v-if="!value" class="t-title-s">{{ tr('How will you buy?', 'কীভাবে কিনবেন?') }}</span>
    <div :class="['ph-mode', !value && 'ask']" role="radiogroup" :aria-label="tr('How will you buy?', 'কীভাবে কিনবেন?')">
      <button v-for="o in opts" :key="o.m" type="button" role="radio" :aria-checked="value === o.m" class="ph-mode-opt" :data-testid="`ph-mode-${o.m}`" @click="emit('change', o.m)">
        <Icon :name="o.icon" />
        <span class="col gap-2" :style="{ minWidth: 0 }">
          <span class="t-label-l">{{ o.label }}</span>
          <span class="t-body-s sub">{{ o.sub }}</span>
        </span>
      </button>
    </div>
  </div>
</template>
