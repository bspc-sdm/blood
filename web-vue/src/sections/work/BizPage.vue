<script setup lang="ts">
// একটা ব্যবসার পাতা (অ্যাম্বুলেন্স, ব্লাড ব্যাংক, ফার্মেসি/প্যাথলজির তথ্য): "আমার কাজ"-এর সব পাতার একই খোলস — উপরের বারে
// পাতার নাম (ছোট পর্দায় পাশে "পেছনে" → "আমার কাজ"), নিচে একই মেনু; পাতার বোতাম পাতার মাথায়।
import { useSlots } from 'vue';
import Workspace from '@/shell/Workspace.vue';
import { slotHasContent } from '@/ui/kit';
import { useTopBack } from '@/shell/chrome';

defineProps<{ title: string }>();
useTopBack('/work');
const slots = useSlots();
</script>

<template>
  <Workspace :title="title">
    <template v-if="slotHasContent(slots.actions)" #actions><div class="row wrap gap-8 work-bar-actions"><slot name="actions" /></div></template>
    <slot />
  </Workspace>
</template>
