<script setup lang="ts">
// বিস্তারিত ভাগ: মোবাইলে উপরে "পেছনে" বার (অ্যাপের BackTopBar), বড় পর্দায় শুধু শিরোনাম
import { useNav } from '@/lib/nav';
import { tr } from '@/lib/i18n';
import { IconButton } from '@/ui/kit';
import { useDetailMode, useTwoPane } from './chrome';

const props = defineProps<{ title: string; onBack?: () => void; backTo?: string; hideTitleWide?: boolean }>();
const nav = useNav();
const two = useTwoPane();
useDetailMode(() => !two.value);
const back = () => (props.onBack ? props.onBack() : props.backTo ? nav(props.backTo) : nav(-1));
</script>

<template>
  <div class="back-bar">
    <IconButton icon="arrow_back" :label="tr('Back', 'পেছনে')" @click="back" />
    <h1>{{ title }}</h1>
    <slot name="actions" />
  </div>
  <div class="ld-detail-inner">
    <div v-if="two && !hideTitleWide" class="row">
      <h2 class="t-title-l grow">{{ title }}</h2>
      <slot name="actions" />
      <IconButton icon="close" :label="tr('Close', 'বন্ধ')" @click="back" />
    </div>
    <slot />
  </div>
</template>
