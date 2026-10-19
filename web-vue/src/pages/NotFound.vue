<script setup lang="ts">
import { RouterLink } from 'vue-router';
import { EmptyState } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { usePageTitle } from '@/shell/chrome';

usePageTitle(() => tr('Not found', 'পাওয়া যায়নি'));
</script>

<template>
  <EmptyState icon="info" :title="tr('Page not found', 'পাতাটা পাওয়া যায়নি')">
    <RouterLink to="/" class="btn">{{ tr('Go home', 'হোমে যান') }}</RouterLink>
  </EmptyState>
</template>
