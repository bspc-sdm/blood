<script setup lang="ts">
// উপরের ট্যাব: অ্যাম্বুলেন্স / ফার্মেসি / ব্লাড ব্যাংক / প্যাথলজি (+ ফার্মেসির ভেতরে "ওষুধ খুঁজুন")
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import type { Kind } from './data';

defineProps<{ kind: Kind; sub?: 'list' | 'medicine' }>();
const nav = useNav();
const tabs = computed(() => [
  { key: 'ambulance', label: tr('Ambulance', 'অ্যাম্বুলেন্স'), icon: 'ambulance' },
  { key: 'pharmacy', label: tr('Pharmacy', 'ফার্মেসি'), icon: 'medication' },
  { key: 'bloodbank', label: tr('Blood bank', 'ব্লাড ব্যাংক'), icon: 'bloodtype' },
  { key: 'lab', label: tr('Lab', 'প্যাথলজি'), icon: 'local_hospital' },
]);
const subTabs = computed(() => [
  { key: 'list', label: tr('Pharmacies', 'ফার্মেসি'), icon: 'storefront' },
  { key: 'medicine', label: tr('Find a medicine', 'ওষুধ খুঁজুন'), icon: 'search' },
]);
</script>

<template>
  <div class="services-tabs">
    <PillTabs :label="tr('Services', 'সেবা')" :value="kind" :tabs="tabs" @change="(k) => nav(`/services/${k}`)" />
    <PillTabs v-if="sub" secondary :label="tr('Pharmacy', 'ফার্মেসি')" :value="sub" :tabs="subTabs" @change="(k) => nav(k === 'medicine' ? '/services/pharmacy/medicine' : '/services/pharmacy')" />
  </div>
</template>
