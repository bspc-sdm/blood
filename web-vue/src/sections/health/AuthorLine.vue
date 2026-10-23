<script setup lang="ts">
// নাম · ব্যাজ · কখন (অ্যাপের AuthorLine)
import { tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import BadgeChip from './BadgeChip.vue';

// label: সার্ভারের ব্যাজের নাম (title_guard: অ-এলোপ্যাথিক চিকিৎসক "ডাক্তার" নন), না থাকলে ধরনের নাম
defineProps<{ name: string | null; badge?: string | null; label?: string | null; createdAt: number }>();
</script>

<template>
  <div class="health-author">
    <span class="t-label-l semi ellipsis">{{ name || tr('Anonymous', 'নাম প্রকাশে অনিচ্ছুক') }}</span>
    <BadgeChip v-if="badge" :type="badge" :label="label" />
    <span class="t-label-s muted none">{{ timeAgo(createdAt) }}</span>
  </div>
</template>
