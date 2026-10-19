<script setup lang="ts">
// পুরো পাতা যার নিজের "পেছনে" বার লাগে (মোবাইলে উপরের বার লুকিয়ে) — যেমন ফর্ম
import { useNav } from '@/lib/nav';
import { tr } from '@/lib/i18n';
import { IconButton } from '@/ui/kit';
import { useDetailMode, useSubPage } from './chrome';

const props = defineProps<{ title: string; onBack?: () => void; backTo?: string }>();
const nav = useNav();
useDetailMode(true);
useSubPage();
const back = () => (props.onBack ? props.onBack() : props.backTo ? nav(props.backTo) : nav(-1));
</script>

<template>
  <div class="back-bar sub-bar">
    <IconButton icon="arrow_back" :label="tr('Back', 'পেছনে')" @click="back" />
    <h1>{{ title }}</h1>
    <slot name="actions" />
  </div>
</template>
