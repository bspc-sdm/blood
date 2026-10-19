<script setup lang="ts">
// রিকোয়েস্টে ডোনারের অবস্থা: এখনো কেউ নেই / কতজন রাজি / পাওয়া গেছে
import { Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import type { BloodRequest } from '../model';

defineProps<{ r: BloodRequest }>();
</script>

<template>
  <Tag v-if="r.fulfilled" tone="good">{{ tr('Donors found', 'ডোনার পাওয়া গেছে') }}</Tag>
  <Tag v-else-if="r.acceptedCount > 0" tone="info">{{ tr(`${num(r.acceptedCount)} agreed`, `${num(r.acceptedCount)} জন রাজি`) }}</Tag>
  <Tag v-else tone="error">{{ tr('No donor yet', 'এখনো কোনো ডোনার নেই') }}</Tag>
</template>
