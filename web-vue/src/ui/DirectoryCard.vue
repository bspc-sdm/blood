<script setup lang="ts">
// ডিরেক্টরি কার্ড (অ্যাপের DirectoryCard)
import Avatar from './Avatar.vue';
import Tag from './Tag.vue';
import IconLine from './IconLine.vue';
import Button from './Button.vue';
import type { CardActionDef } from './types';

withDefaults(defineProps<{
  title: string; icon: string; photo?: string | null; tint?: 'primary' | 'error'; subtitle?: string | null;
  status?: { text: string; good: boolean } | null; meta?: string | null; details?: [string, string | null | undefined][];
  tags?: string[]; note?: string | null; primary?: CardActionDef | null; secondary?: CardActionDef | null;
  onClick?: () => void; selected?: boolean;
  /** নামের পাশে ছোট লেবেল (যেমন চিকিৎসা পদ্ধতি); না দিলে আগের মতো */
  titleTag?: string | null;
}>(), { details: () => [], tags: () => [] });

function onKey(e: KeyboardEvent, f?: () => void) {
  if (f && (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
    e.preventDefault();
    f();
  }
}
</script>

<template>
  <article
    :class="['card', onClick && 'click', selected && 'sel']"
    :role="onClick ? 'button' : undefined"
    :tabindex="onClick ? 0 : undefined"
    :aria-pressed="onClick ? selected : undefined"
    @click="onClick?.()"
    @keydown="onKey($event, onClick)"
  >
    <div class="row top gap-12">
      <Avatar :photo="photo" :icon="icon" :tint="tint" />
      <div class="col gap-2 grow">
        <div class="row" :style="{ alignItems: 'center' }">
          <span class="t-title-m clamp2" :style="{ minWidth: 0 }">{{ title }}</span>
          <Tag v-if="titleTag" tone="info" class="title-tag">{{ titleTag }}</Tag>
          <Tag v-if="status" :tone="status.good ? 'good' : undefined">{{ status.text }}</Tag>
        </div>
        <span v-if="subtitle" class="t-body-m c-primary clamp2">{{ subtitle }}</span>
        <span v-if="meta" class="t-body-s muted">{{ meta }}</span>
      </div>
      <slot name="menu" />
    </div>
    <!-- কার্ডের নিজের অবস্থা-লাইন ইত্যাদি (যেমন প্রেসক্রিপশনের চিপ) — না দিলে আগের মতো -->
    <slot name="extra" />
    <div v-if="details.some((d) => d[1]) || tags.length || note" class="col gap-6" :style="{ marginTop: '10px' }">
      <IconLine v-for="[ic, text] in details.filter((d) => d[1])" :key="ic + text" :icon="ic">{{ text }}</IconLine>
      <div v-if="tags.length" class="row wrap gap-6"><Tag v-for="t in tags" :key="t" tone="primary">{{ t }}</Tag></div>
      <p v-if="note" class="t-body-s muted">{{ note }}</p>
    </div>
    <div v-if="primary || secondary" class="row" :style="{ marginTop: '12px' }">
      <Button v-if="primary" square :icon="primary.icon" :busy="primary.busy" @click.stop="primary.onClick()">{{ primary.label }}</Button>
      <span class="grow" />
      <Button v-if="secondary" variant="text" :icon="secondary.icon" @click.stop="secondary.onClick()">{{ secondary.label }}</Button>
    </div>
  </article>
</template>
