<script setup lang="ts">
// শেয়ার: লিংক আর লেখা কপি, ফোনে থাকলে শেয়ারের তালিকা
import { computed } from 'vue';
import { Button, Dialog, toast } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { shareLink, shareText, type BloodRequest } from '../model';

const props = defineProps<{ r: BloodRequest }>();
const emit = defineEmits<{ close: [] }>();
const text = computed(() => shareText(props.r));
const link = computed(() => shareLink(props.r.id));
const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function';
const copy = (v: string) => {
  navigator.clipboard?.writeText(v).then(
    () => toast(tr('Copied', 'কপি হয়েছে')),
    () => toast(tr("Couldn't copy", 'কপি করা যায়নি')),
  );
};
const share = () => navigator.share({ text: text.value }).catch(() => {});
</script>

<template>
  <Dialog :title="tr('Share request', 'রিকোয়েস্ট শেয়ার করুন')" @close="emit('close')">
    <p class="t-body-m pre blood-sharetext">{{ text }}</p>
    <div class="row blood-link">
      <span class="grow ellipsis num" :style="{ userSelect: 'all' }">{{ link }}</span>
      <Button variant="tonal" small @click="copy(link)">{{ tr('Copy link', 'লিংক কপি') }}</Button>
    </div>
    <template #actions>
      <Button variant="text" @click="copy(text)">{{ tr('Copy text', 'লেখা কপি') }}</Button>
      <Button v-if="canShare" icon="share" @click="share">{{ tr('Share', 'শেয়ার') }}</Button>
    </template>
  </Dialog>
</template>
