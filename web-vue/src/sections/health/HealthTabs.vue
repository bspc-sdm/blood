<script setup lang="ts">
// উপরের ট্যাব: আলোচনা / টিপস ("আমার প্রেসক্রিপশন" সেবা → ফার্মেসিতে আর প্রোফাইলে — মালিক)
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';

defineProps<{ value: 'discussion' | 'tips' }>();
const nav = useNav();
const tabs = computed(() => [
  { key: 'discussion', label: tr('Discussion', 'আলোচনা'), icon: 'forum_outline', selectedIcon: 'forum_filled' },
  { key: 'tips', label: tr('Tips', 'টিপস'), icon: 'lightbulb_outline', selectedIcon: 'lightbulb_filled' },
]);
</script>

<template>
  <div class="health-tabs">
    <PillTabs :label="tr('Health', 'স্বাস্থ্য')" :value="value" :tabs="tabs" @change="(k) => nav(`/health/${k}`)" />
  </div>
</template>
