<script setup lang="ts">
// নামের পাশে ব্যাজ (অ্যাপের BadgeChip)
import { Icon } from '@/ui/kit';
import { badgeLabel } from './common';

defineProps<{ type: string; label?: string | null }>();
</script>

<template>
  <span class="health-badge"><Icon name="verified" />{{ label || badgeLabel(type) }}</span>
</template>
