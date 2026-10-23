<script setup lang="ts">
// কার্ডের ⋮ মেনু — পাঁচ ধরনেই একই (অ্যান্ড্রয়েডের মতো): বিস্তারিত · রিপোর্ট (যেগুলো রিপোর্ট করা যায়)
import { onBeforeUnmount, ref, watch } from 'vue';
import { Icon, IconButton } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { LT } from './model';

const props = defineProps<{ onDetails?: () => void; onReport?: () => void }>();
const open = ref(false);
const root = ref<HTMLDivElement | null>(null);
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
watch(open, (o) => {
  off();
  if (o) {
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
  }
});
onBeforeUnmount(off);
function run(f?: () => void) {
  open.value = false;
  f?.();
}
</script>

<template>
  <div v-if="props.onDetails || props.onReport" ref="root" class="services-more" @click.stop @keydown.stop>
    <IconButton small icon="more_vert" :label="tr('More', 'আরও')" :aria-expanded="open" aria-haspopup="menu" @click="open = !open" />
    <div v-if="open" class="services-menu" role="menu">
      <button v-if="props.onDetails" type="button" role="menuitem" @click="run(props.onDetails)"><Icon name="info" />{{ LT.details() }}</button>
      <button v-if="props.onReport" type="button" role="menuitem" @click="run(props.onReport)"><Icon name="flag" />{{ tr('Report', 'রিপোর্ট') }}</button>
    </div>
  </div>
</template>
