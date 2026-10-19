<script setup lang="ts">
// React Router-এর <Navigate replace>: দেখানো হলেই অন্য পাতায় (ইতিহাসে নতুন ধাপ ছাড়া)
import { onMounted } from 'vue';
import { useNav } from '@/lib/nav';

const props = defineProps<{ to: string }>();
const nav = useNav();
onMounted(() => nav(props.to, { replace: true }));
</script>

<template>
  <span hidden />
</template>
