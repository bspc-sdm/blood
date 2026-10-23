<script setup lang="ts">
// সারির "আরও" (⋮) মেনু — "আমার কাজ"-এর কাজের সারিতে একটাই মূল বোতাম, বাকি কাজ এখানে (CONTRACT §২)।
// বোতামে আইকনের সাথে দেখা যায় এমন শব্দ ("আরও")। Esc বা বাইরে চাপলে বন্ধ। কিছু না থাকলে কিছুই আঁকে না।
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { tr } from '@/lib/i18n';
import Icon from './Icon.vue';

export interface MenuItem { label: string; icon?: string; danger?: boolean; disabled?: boolean; testid?: string; onClick: () => void }
const props = defineProps<{ items: (MenuItem | null | false | undefined)[]; label?: string; testid?: string }>();
const open = ref(false);
const up = ref(false);
const root = ref<HTMLDivElement | null>(null);
const shown = () => props.items.filter((x): x is MenuItem => !!x);
const onDown = (e: Event) => {
  if (!root.value?.contains(e.target as Node)) open.value = false;
};
const onKey = (e: KeyboardEvent) => {
  if (e.key === 'Escape') open.value = false;
};
const off = () => {
  document.removeEventListener('pointerdown', onDown);
  document.removeEventListener('keydown', onKey);
};
watch(open, async (o) => {
  off();
  if (!o) return;
  document.addEventListener('pointerdown', onDown);
  document.addEventListener('keydown', onKey);
  // পর্দার নিচে জায়গা না থাকলে মেনু ওপরে খোলে
  const r = root.value?.getBoundingClientRect();
  up.value = !!r && window.innerHeight - r.bottom < 56 * shown().length + 24 && r.top > window.innerHeight / 2;
  await nextTick();
  root.value?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
});
onBeforeUnmount(off);
/** Esc: মেনুর ভেতরে চাপলেও বন্ধ (মূল div কিবোর্ডের ঘটনা বাইরে যেতে দেয় না) — বোতামে ফোকাস ফেরে */
function rootKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && open.value) {
    open.value = false;
    root.value?.querySelector<HTMLElement>('button')?.focus();
  }
}
function run(it: MenuItem) {
  open.value = false;
  it.onClick();
}
</script>

<template>
  <div v-if="shown().length" ref="root" class="q-more" @click.stop @keydown.stop="rootKey">
    <button type="button" class="btn text small has-icon" :aria-expanded="open" aria-haspopup="menu" :data-testid="testid ?? 'row-more'" @click="open = !open">
      <Icon name="more_vert" />{{ label ?? tr('More', 'আরও') }}
    </button>
    <div v-if="open" :class="['q-menu', up && 'up']" role="menu">
      <button
        v-for="(it, i) in shown()"
        :key="i"
        type="button"
        role="menuitem"
        :class="it.danger && 'danger'"
        :disabled="it.disabled"
        :data-testid="it.testid"
        @click="run(it)"
      >
        <Icon v-if="it.icon" :name="it.icon" />{{ it.label }}
      </button>
    </div>
  </div>
</template>
