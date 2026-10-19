<script setup lang="ts">
// অ্যাপের PillTabs — Reka UI-র Tabs (তীর-চাবি দিয়ে ট্যাব বদল); দেখতে React-এর .pills/.pill
import { nextTick, ref, watch, onMounted } from 'vue';
import { TabsList, TabsRoot, TabsTrigger } from 'reka-ui';
import { num } from '@/lib/i18n';
import Icon from './Icon.vue';
import type { PillTab } from './types';

const props = defineProps<{ tabs: PillTab[]; value: string; secondary?: boolean; label?: string }>();
const emit = defineEmits<{ change: [key: string] }>();
const list = ref<InstanceType<typeof TabsList> | null>(null);

// বাছা ট্যাব সবসময় পুরোটা দেখা যায় (অ্যাপের মতো মাঝখানে আসে)
function center() {
  const box = (list.value as any)?.$el as HTMLElement | undefined;
  const el = box?.querySelector<HTMLElement>('[aria-selected="true"]');
  if (el && box && box.scrollWidth > box.clientWidth) {
    box.scrollTo({ left: el.offsetLeft - box.clientWidth / 2 + el.offsetWidth / 2, behavior: 'smooth' });
  }
}
onMounted(center);
watch(() => props.value, () => nextTick(center));
</script>

<template>
  <!-- বদল শুধু click-এ (React-এর মতো একবার; বাছা ট্যাবে আবার চাপলেও জানায়) — Reka-র নিজের mousedown-বদল নিয়ন্ত্রিত value-তে কিছু করে না -->
  <TabsRoot as-child :model-value="value" activation-mode="manual">
    <TabsList ref="list" :class="['pills', secondary && 'secondary']" :aria-label="label">
      <TabsTrigger v-for="t in tabs" :key="t.key" :value="t.key" class="pill" @click="emit('change', t.key)">
        <Icon v-if="(t.key === value ? t.selectedIcon ?? t.icon : t.icon)" :name="(t.key === value ? t.selectedIcon ?? t.icon : t.icon)!" />{{ t.label }}<span v-if="t.count" class="n">{{ num(t.count) }}</span>
      </TabsTrigger>
    </TabsList>
  </TabsRoot>
</template>
