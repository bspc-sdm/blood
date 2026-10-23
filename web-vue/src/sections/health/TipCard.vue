<script setup lang="ts">
import { tr } from '@/lib/i18n';
import type { Tip } from './paged';
import AuthorLine from './AuthorLine.vue';

const props = defineProps<{ t: Tip; selected: boolean; onClick: () => void }>();
function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    props.onClick();
  }
}
</script>

<template>
  <article :class="['card click col', selected && 'sel']" role="button" tabindex="0" :aria-current="selected || undefined" @click="onClick()" @keydown="onKey">
    <AuthorLine :name="t.authorName" :badge="t.badge" :label="t.badgeLabel ? tr(t.badgeLabelEn || t.badgeLabel, t.badgeLabel) : null" :created-at="t.createdAt" />
    <h3 class="t-title-m">{{ t.title }}</h3>
    <p class="t-body-m muted health-clamp3">{{ t.body }}</p>
  </article>
</template>
