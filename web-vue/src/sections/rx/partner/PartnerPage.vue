<script setup lang="ts">
// "আমার কাজ"-এর ব্যবসার পাতা (ফার্মেসি, প্যাথলজি, পেমেন্ট): বাকি সব কাজের পাতার একই খোলস — উপরের বারে পাতার নাম
// (ছোট পর্দায় পাশে "পেছনে" → backTo), নিচে একই মেনু; বড় পর্দায় বাঁ মেনুতে ভূমিকার ভাগ। পাতার বোতাম পাতার মাথায়।
import { toRef, useSlots } from 'vue';
import Workspace from '@/shell/Workspace.vue';
import { slotHasContent } from '@/ui/kit';
import { useTopBack } from '@/shell/chrome';

const props = defineProps<{ title: string; backTo: string }>();
useTopBack(toRef(props, 'backTo'));
const slots = useSlots();
</script>

<template>
  <Workspace :title="title">
    <template v-if="slotHasContent(slots.actions)" #actions><div class="row wrap gap-8 rxp-bar-actions"><slot name="actions" /></div></template>
    <slot />
  </Workspace>
</template>
