<script setup lang="ts">
import { Tag } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { statusShort } from './shared';

defineProps<{ status: string; closed?: boolean }>();
</script>

<template>
  <Tag v-if="status === 'approved' && closed">{{ tr('Closed', 'বন্ধ') }}</Tag>
  <Tag v-else :tone="status === 'approved' ? 'good' : status === 'pending' ? 'info' : 'error'">{{ statusShort(status) }}</Tag>
</template>
