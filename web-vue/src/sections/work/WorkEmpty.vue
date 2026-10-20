<script setup lang="ts">
// খালি অবস্থা (ভেতরের বাটনের আইকন ছোট থাকে)
import { EmptyState } from '@/ui/kit';

defineProps<{ icon: string; title: string; text?: string }>();
</script>

<template>
  <div class="work-empty">
    <EmptyState :icon="icon" :title="title" :text="text"><slot /></EmptyState>
  </div>
</template>
