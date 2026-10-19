<script setup lang="ts">
// পাতা ৩: ফর্ম পাতা — মোবাইলে পুরো পাতা "পেছনে" বারসহ; বড় পর্দায় মাঝখানে বাক্স
import { usePageTitle } from './chrome';
import SubPageBar from './SubPageBar.vue';

const props = withDefaults(defineProps<{ title: string; onBack?: () => void; backTo?: string; boxed?: boolean }>(), { boxed: true });
usePageTitle(() => props.title);
</script>

<template>
  <SubPageBar :title="title" :on-back="onBack" :back-to="backTo" />
  <div class="form-page">
    <div :class="['form-inner', boxed && 'boxed']"><slot /></div>
  </div>
</template>
