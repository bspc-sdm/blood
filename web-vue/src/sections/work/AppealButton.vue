<script setup lang="ts">
// বাতিল/বন্ধ হলে: আপিল (সাহায্য পাতা)
import { RouterLink } from 'vue-router';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ category?: 'ambulance' | 'pharmacy' | 'bloodbank' | 'other' }>();
</script>

<template>
  <RouterLink :to="`/help/new?category=${category ?? 'other'}`" class="btn outline has-icon small"><Icon name="help" />{{ tr('Appeal / contact us', 'আপিল করুন / আমাদের লিখুন') }}</RouterLink>
</template>
