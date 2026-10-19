<script setup lang="ts">
import { tr } from '@/lib/i18n';
import Button from './Button.vue';
import ErrorText from './ErrorText.vue';

defineProps<{ error: string; onRetry?: () => void }>();
</script>

<template>
  <div class="col" :style="{ alignItems: 'center', padding: '24px' }">
    <ErrorText :text="error" />
    <Button v-if="onRetry" variant="text" @click="onRetry()">{{ tr('Try again', 'আবার চেষ্টা করুন') }}</Button>
  </div>
</template>
