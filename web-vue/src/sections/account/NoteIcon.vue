<script setup lang="ts">
import Icon from '@/ui/Icon.vue';
import { cx } from '@/ui/composables';
import { inboxIcon } from './util';

defineProps<{ item: { title: string; data: Record<string, string>; read: boolean } }>();
</script>

<template>
  <span :class="cx('acct-nicon', !item.read && 'new')">
    <Icon :name="inboxIcon(item.data?.type, item.data?.kind, item.title)" :size="22" />
  </span>
</template>
