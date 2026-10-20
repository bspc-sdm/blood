<script setup lang="ts">
// নাম · ব্যাজ · কখন (অ্যাপের AuthorLine)
import { tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import BadgeChip from './BadgeChip.vue';

defineProps<{ name: string | null; badge?: string | null; createdAt: number }>();
</script>

<template>
  <div class="health-author">
    <span class="t-label-l semi ellipsis">{{ name || tr('Anonymous', 'নাম প্রকাশে অনিচ্ছুক') }}</span>
    <BadgeChip v-if="badge" :type="badge" />
    <span class="t-label-s muted none">{{ timeAgo(createdAt) }}</span>
  </div>
</template>
