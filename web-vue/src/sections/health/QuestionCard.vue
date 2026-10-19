<script setup lang="ts">
import { IconLine } from '@/ui/kit';
import { answersText } from './common';
import type { Question } from './paged';
import AuthorLine from './AuthorLine.vue';

const props = defineProps<{ q: Question; selected: boolean; onClick: () => void }>();
function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    props.onClick();
  }
}
</script>

<template>
  <article :class="['card click col', selected && 'sel']" role="button" tabindex="0" :aria-current="selected || undefined" @click="onClick()" @keydown="onKey">
    <AuthorLine :name="q.authorName" :created-at="q.createdAt" />
    <p class="t-body-l health-clamp4">{{ q.text }}</p>
    <IconLine icon="forum_outline" :color="q.answerCount > 0 ? 'var(--tertiary)' : undefined">{{ answersText(q.answerCount) }}</IconLine>
  </article>
</template>
