<script setup lang="ts">
// বড় পর্দায় ডান ভাগ (কিছু বাছা না থাকলে): এখনই দরকার হলে সেবার শর্টকাট আর ৯৯৯
import { computed } from 'vue';
import { ListRow, PhoneLine } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';

const nav = useNav();
const items = computed(() => [
  { to: '/services/ambulance', icon: 'ambulance', label: tr('Ambulance', 'অ্যাম্বুলেন্স') },
  { to: '/services/bloodbank', icon: 'bloodtype', label: tr('Blood bank', 'ব্লাড ব্যাংক') },
  { to: '/services/pharmacy', icon: 'medication', label: tr('Pharmacy', 'ফার্মেসি') },
]);
</script>

<template>
  <div class="col gap-16 blood-neednow">
    <h2 class="t-title-l">{{ tr('Need it now?', 'এখনই দরকার?') }}</h2>
    <div class="col gap-4 blood-shortcuts">
      <ListRow v-for="it in items" :key="it.to" :icon="it.icon" :title="it.label" @click="nav(it.to)" />
    </div>
    <div class="card col blood-999">
      <span class="t-body-m">{{ tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা') }}</span>
      <PhoneLine phone="999" />
    </div>
  </div>
</template>
