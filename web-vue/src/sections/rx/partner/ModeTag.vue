<script setup lang="ts">
// দোকানে এসেছেন / অনলাইন অর্ডার; ল্যাবে আসবেন / বাসা থেকে নমুনা — আইকনসহ, দুই রঙে (২.২৭.০)
import { Icon, cx } from '@/ui/kit';

defineProps<{ remote: boolean; icon: string }>();
</script>

<template>
  <span :class="cx('rxp-mode', remote ? 'remote' : 'local')"><Icon :name="icon" /><slot /></span>
</template>
