<script setup lang="ts">
// ডাক্তারের এক সারি ট্যাব (doctorNav.ts) — "আমার চেম্বার" আর প্রেসক্রিপশনের পাতায় একই; "আরও" মেনু পাতার মাথায় (DoctorMore)।
// "আরও"-র পাতায় (সেটআপ, সহকারী …) কোনো ট্যাব বাছা থাকে না, নিচে সেই পাতার নাম।
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useDoctorNav, type DocTab } from './doctorNav';

const props = defineProps<{ type: string | null | undefined; active: DocTab; page?: string }>();
const n = useDoctorNav(() => props.type);
</script>

<template>
  <div class="col gap-8">
    <div class="q-fixed" data-testid="doctor-tabs">
      <PillTabs :tabs="n.tabs.value" :value="active" :label="tr('My chamber', 'আমার চেম্বার')" @change="n.go" />
    </div>
    <h2 v-if="active === 'more' && page" class="t-title-m" data-testid="doctor-subpage">{{ page }}</h2>
  </div>
</template>
