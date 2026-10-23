<script setup lang="ts">
// তালিকার সারিতে "বাসা থেকে" চিহ্ন (চাওয়া/নিশ্চিত সময়সহ)
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { visitText, wantedText } from '@/lib/labprice';
import type { LabOrder } from './model';

defineProps<{ o: LabOrder }>();
</script>

<template>
  <template v-if="o.home && ['sent', 'received'].includes(o.status)">
    <span v-if="o.home.status === 'declined'" class="t-body-s muted">{{ tr('Home visit: no', 'বাসায় যাওয়া: না') }}</span>
    <span v-else :class="['lp-home-tag', o.home.status === 'expired' && 'expired']" :data-testid="o.home.status === 'expired' ? 'home-slot-expired' : undefined"><Icon :name="o.home.status === 'expired' ? 'schedule' : 'home_health'" />{{
      o.home.status === 'confirmed'
        ? tr(`From home · ${visitText(o.home)}`, `বাসা থেকে · ${visitText(o.home)}`)
        : tr(`From home · ${wantedText(o.home)}`, `বাসা থেকে · ${wantedText(o.home)}`) + (o.home.status === 'expired' ? tr(' — time has passed', ' — সময় পেরিয়ে গেছে') : '')
    }}</span>
  </template>
</template>
