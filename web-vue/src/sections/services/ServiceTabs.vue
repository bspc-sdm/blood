<script setup lang="ts">
// উপরের ট্যাব: অ্যাম্বুলেন্স / ফার্মেসি / ব্লাড ব্যাংক / প্যাথলজি (+ ফার্মেসির ভেতরে "ওষুধ খুঁজুন" আর "আমার প্রেসক্রিপশন")
// মালিক: "আমার প্রেসক্রিপশন" ফার্মেসির ভেতরে আর প্রোফাইলে — স্বাস্থ্যে নয়
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { RX_HOME, type Kind } from './data';

defineProps<{ kind: Kind; sub?: 'list' | 'medicine' | 'rx' }>();
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
  { key: 'rx', label: tr('My prescriptions', 'আমার প্রেসক্রিপশন'), icon: 'list_alt' },
]);
</script>

<template>
  <div class="services-tabs">
    <PillTabs :label="tr('Services', 'সেবা')" :value="kind" :tabs="tabs" @change="(k) => nav(`/services/${k}`)" />
    <PillTabs v-if="sub" secondary :label="tr('Pharmacy', 'ফার্মেসি')" :value="sub" :tabs="subTabs" @change="(k) => nav(k === 'medicine' ? '/services/pharmacy/medicine' : k === 'rx' ? RX_HOME : '/services/pharmacy')" />
  </div>
</template>
