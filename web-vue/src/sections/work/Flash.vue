<script setup lang="ts">
import { Icon, cx } from '@/ui/kit';

defineProps<{ msg?: string | null; error?: string | null }>();
</script>

<template>
  <div v-if="msg || error" :class="cx('work-flash', error ? 'err' : 'ok')" :role="error ? 'alert' : 'status'">
    <Icon :name="error ? 'warning' : 'check_circle'" />
    <span>{{ error ?? msg }}</span>
  </div>
</template>
