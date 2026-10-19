<script setup lang="ts">
// তালিকার মাথা: বড় শিরোনাম + রিফ্রেশ (+ চওড়া পর্দায় কাজের বাটন — action slot)
import { IconButton } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ title: string; onRefresh?: () => void; refreshing?: boolean }>();
</script>

<template>
  <div class="row">
    <h2 class="t-headline grow">{{ title }}</h2>
    <slot name="action" />
    <IconButton v-if="onRefresh" icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" :disabled="refreshing" :class="refreshing ? 'health-spin' : undefined" @click="onRefresh()" />
  </div>
</template>
