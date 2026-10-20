<script setup lang="ts">
// একটা ব্যবসার পাতা: উপরে "পেছনে" বার ("আমার কাজ"-এ ফেরা), নিচে চওড়া কাজের পাতা
import { useSlots } from 'vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import Workspace from '@/shell/Workspace.vue';
import { useMobile } from '@/shell/chrome';
import { slotHasContent } from '@/ui/kit';

defineProps<{ title: string }>();
const mobile = useMobile();
const slots = useSlots();
</script>

<template>
  <SubPageBar :title="title" back-to="/work">
    <template v-if="!mobile && slotHasContent(slots.actions)" #actions><div class="row gap-8 work-bar-actions"><slot name="actions" /></div></template>
  </SubPageBar>
  <Workspace :title="title">
    <template v-if="mobile && slotHasContent(slots.actions)" #actions><slot name="actions" /></template>
    <slot />
  </Workspace>
</template>
