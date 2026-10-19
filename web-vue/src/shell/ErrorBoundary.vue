<script setup lang="ts">
// কোনো অংশ ভেঙে গেলে (বা নতুন ডিপ্লয়ের পর পুরোনো ফাইল না পেলে) সাদা পাতার বদলে "আবার লোড করুন"
import { onErrorCaptured, ref, watch } from 'vue';
import { tr } from '@/lib/i18n';

const props = defineProps<{ resetKey?: string }>();
const error = ref<unknown>(null);
onErrorCaptured((e) => {
  error.value = e;
  console.error(e);
  return false;
});
// অন্য পাতায় গেলে আবার চেষ্টা
watch(
  () => props.resetKey,
  () => {
    if (error.value) error.value = null;
  },
);
const reload = () => window.location.reload();
</script>

<template>
  <slot v-if="!error" />
  <div v-else class="empty" role="alert">
    <div class="t">{{ tr('Something went wrong', 'কিছু একটা সমস্যা হয়েছে') }}</div>
    <div class="s">{{ tr('Reload the page to get the latest version.', 'নতুন সংস্করণ পেতে পাতাটা আবার লোড করুন।') }}</div>
    <button type="button" class="btn" @click="reload">{{ tr('Reload', 'আবার লোড করুন') }}</button>
  </div>
</template>
