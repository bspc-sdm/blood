<script setup lang="ts">
// ছোট শিরোনামসহ কার্ড
import { Icon, cx } from '@/ui/kit';

defineProps<{ title?: string; icon?: string; count?: string | number | null; cls?: string }>();
</script>

<template>
  <section :class="cx('card rxp-panel', cls)">
    <div class="rxp-panel-head">
      <span v-if="icon" class="rxp-panel-ic"><Icon :name="icon" /></span>
      <h2 class="t-title-m grow"><slot name="title">{{ title }}</slot></h2>
      <span v-if="count != null" class="rxp-count num">{{ count }}</span>
      <slot name="actions" />
    </div>
    <slot />
  </section>
</template>
