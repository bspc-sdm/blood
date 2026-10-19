<script setup lang="ts">
// পাতা: উপরে "পেছনে" বার (নিজের ব্যবসার পাতায় ফেরা), নিচে চওড়া কাজের জায়গা
import { useSlots } from 'vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import Workspace from '@/shell/Workspace.vue';
import { useMobile } from '@/shell/chrome';
import { slotHasContent } from '@/ui/kit';

defineProps<{ title: string; backTo: string }>();
const mobile = useMobile();
const slots = useSlots();
</script>

<template>
  <SubPageBar :title="title" :back-to="backTo">
    <template v-if="!mobile && slotHasContent(slots.actions)" #actions><div class="row gap-8 rxp-bar-actions"><slot name="actions" /></div></template>
  </SubPageBar>
  <Workspace :title="title">
    <template v-if="mobile && slotHasContent(slots.actions)" #actions><slot name="actions" /></template>
    <slot />
  </Workspace>
</template>
