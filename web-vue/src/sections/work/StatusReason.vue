<script setup lang="ts">
import { tr } from '@/lib/i18n';

defineProps<{ status: string; reason?: string }>();
</script>

<template>
  <p v-if="reason && ['rejected', 'suspended'].includes(status)" class="t-body-s c-error">{{ tr('Reason: ', 'কারণ: ') + reason }}</p>
</template>
