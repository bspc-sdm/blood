<script setup lang="ts">
import { tr } from '@/lib/i18n';

defineProps<{ inline?: boolean }>();
</script>

<template>
  <span v-if="inline" class="spinner small" role="status" :aria-label="tr('Loading', 'লোড হচ্ছে')" />
  <div v-else class="loading" role="status" :aria-label="tr('Loading', 'লোড হচ্ছে')">
    <span class="spinner" />
  </div>
</template>
