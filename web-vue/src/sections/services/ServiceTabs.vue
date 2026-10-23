<script setup lang="ts">
// উপরের ট্যাব (এক লাইনের ছোট চিপ, পাশে সরে): অ্যাম্বুলেন্স / ডাক্তার / ফার্মেসি / ব্লাড ব্যাংক / ল্যাব
// (+ ফার্মেসির ভেতরে "ওষুধ খুঁজুন" আর "আমার প্রেসক্রিপশন")। ডাক্তার "অ্যাপয়েন্টমেন্ট"-এও থাকে।
// মালিক: "আমার প্রেসক্রিপশন" ফার্মেসির ভেতরে আর প্রোফাইলে — স্বাস্থ্যে নয়
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { LISTING_KINDS, LT, type ListingKind } from '@/ui/listing/model';
import { RX_HOME } from './data';

defineProps<{ kind: ListingKind; sub?: 'list' | 'medicine' | 'rx' }>();
const nav = useNav();
const ICON: Record<ListingKind, string> = { ambulance: 'ambulance', doctor: 'stethoscope', pharmacy: 'medication', bloodbank: 'bloodtype', lab: 'local_hospital' };
const tabs = computed(() => LISTING_KINDS.map((k) => ({ key: k, label: LT.tab(k), icon: ICON[k] })));
const subTabs = computed(() => [
  { key: 'list', label: tr('Pharmacies', 'ফার্মেসি'), icon: 'storefront' },
  { key: 'medicine', label: tr('Find a medicine', 'ওষুধ খুঁজুন'), icon: 'search' },
  { key: 'rx', label: tr('My prescriptions', 'আমার প্রেসক্রিপশন'), icon: 'list_alt' },
]);
</script>

<template>
  <div class="services-tabs lst-tabs" data-testid="service-tabs">
    <PillTabs nearest :label="tr('Services', 'সেবা')" :value="kind" :tabs="tabs" @change="(k) => nav(`/services/${k}`)" />
    <PillTabs v-if="sub" nearest secondary :label="tr('Pharmacy', 'ফার্মেসি')" :value="sub" :tabs="subTabs" @change="(k) => nav(k === 'medicine' ? '/services/pharmacy/medicine' : k === 'rx' ? RX_HOME : '/services/pharmacy')" />
  </div>
</template>
