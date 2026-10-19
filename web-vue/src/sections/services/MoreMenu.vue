<script setup lang="ts">
// ⋮ মেনু (রিপোর্ট) — Reka UI-র DropdownMenu নয়: React-এর মতো কার্ডের ভেতরে (.services-menu) বসে, একই চেহারা।
import { onBeforeUnmount, ref, watch } from 'vue';
import { Icon, IconButton } from '@/ui/kit';
import { tr } from '@/lib/i18n';

const emit = defineEmits<{ report: [] }>();
const open = ref(false);
const root = ref<HTMLDivElement | null>(null);
const onDown = (e: MouseEvent) => {
  if (!root.value?.contains(e.target as Node)) open.value = false;
};
const onKey = (e: KeyboardEvent) => {
  if (e.key === 'Escape') open.value = false;
};
watch(open, (o) => {
  if (o) {
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
  } else {
    document.removeEventListener('mousedown', onDown);
    document.removeEventListener('keydown', onKey);
  }
});
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDown);
  document.removeEventListener('keydown', onKey);
});
function report() {
  open.value = false;
  emit('report');
}
</script>

<template>
  <div ref="root" class="services-more" @click.stop>
    <IconButton small icon="more_vert" :label="tr('More', 'আরও')" :aria-expanded="open" aria-haspopup="menu" @click="open = !open" />
    <div v-if="open" class="services-menu" role="menu">
      <button type="button" role="menuitem" @click="report"><Icon name="flag" />{{ tr('Report', 'রিপোর্ট') }}</button>
    </div>
  </div>
</template>
