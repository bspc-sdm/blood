<script setup lang="ts">
// ডেস্কটপে (অফলাইন চলে): এই কম্পিউটারের সিরিজ, কয়টা মেলানোর অপেক্ষায়, "এখনই মেলান"। সাধারণ ওয়েবে কিছুই দেখায় না।
import { ref } from 'vue';
import { Button, Icon, Tag } from '@/ui/kit';
import { T } from './text';

const props = defineProps<{ st: { desktop: boolean; series: string; pending: number; error: string }; sync: () => Promise<void> }>();
const busy = ref(false);
async function run() {
  busy.value = true;
  try {
    await props.sync();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div v-if="st.desktop" class="row wrap gap-8 card inv-offline" data-testid="offline-bar">
    <Icon name="sync" :size="20" />
    <span v-if="st.series" class="t-label-l">{{ T.desktopDevice(st.series) }}</span>
    <Tag :tone="st.pending ? 'primary' : 'good'">{{ st.pending ? T.offlinePending(st.pending) : T.allSynced() }}</Tag>
    <span v-if="st.error" class="t-body-s c-error">{{ st.error }}</span>
    <span class="grow" />
    <Button small variant="text" icon="sync" :busy="busy" @click="run">{{ T.syncNow() }}</Button>
  </div>
</template>
