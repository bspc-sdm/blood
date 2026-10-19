<script setup lang="ts">
// ডায়ালগ / মোবাইলে নিচের শিট — Reka UI-র Dialog (ফোকাস আটকে রাখা, Esc, বাইরে চাপলে বন্ধ, পেছনের পাতা স্ক্রল বন্ধ)।
// দেখতে React-এর মতো: .overlay(.sheet) > .dialog; খোলার সময় আগে লেখার ঘর, না থাকলে প্রথম বাটনে ফোকাস।
import { ref } from 'vue';
import { DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { tr } from '@/lib/i18n';
import IconButton from './IconButton.vue';

const props = withDefaults(defineProps<{ open?: boolean; title?: string | null; size?: 'small' | 'wide'; sheet?: boolean; labelledBy?: string; role?: string }>(), {
  open: true,
  sheet: true,
});
const emit = defineEmits<{ close: [] }>();
const box = ref<InstanceType<typeof DialogContent> | null>(null);

function focusFirst(e: Event) {
  e.preventDefault();
  setTimeout(() => {
    const el = (box.value as any)?.$el as HTMLElement | undefined;
    if (!el || el.contains(document.activeElement)) return;
    const f = el.querySelector<HTMLElement>('input:not([type=hidden]):not([disabled]), select, textarea') ?? el.querySelector<HTMLElement>('button:not([data-close])');
    (f ?? el).focus();
  }, 30);
}
function onOpen(v: boolean) {
  if (!v) emit('close');
}
void props;
</script>

<template>
  <DialogRoot :open="open" @update:open="onOpen">
    <DialogPortal>
      <DialogOverlay :class="['overlay', sheet && 'sheet']">
        <DialogContent
          ref="box"
          :class="['dialog', size]"
          :role="role"
          :aria-labelledby="title || $slots.title ? undefined : labelledBy"
          :aria-describedby="undefined"
          tabindex="-1"
          @open-auto-focus="focusFirst"
        >
          <div v-if="title || $slots.title" class="row">
            <DialogTitle as="h2" class="t-title-l grow"><slot name="title">{{ title }}</slot></DialogTitle>
            <IconButton icon="close" :label="tr('Close', 'বন্ধ')" data-close="" @click="emit('close')" />
          </div>
          <slot />
          <div v-if="$slots.actions" class="dialog-actions"><slot name="actions" /></div>
        </DialogContent>
      </DialogOverlay>
    </DialogPortal>
  </DialogRoot>
</template>
