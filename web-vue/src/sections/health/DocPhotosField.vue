<script setup lang="ts">
// কাগজের ছবি বাছাই: প্রতিটার পাশে "ছবি দিন" আর ছোট ছবি (অ্যাপের DocPhotosField)
import { ref } from 'vue';
import { Button, ErrorText, Icon } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { imgSrc, toDocumentBase64 } from './common';
import type { DocSpec } from './badge';

const props = defineProps<{ specs: DocSpec[]; photos: Record<string, string>; note?: string | null }>();
const emit = defineEmits<{ change: [p: Record<string, string>] }>();
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const input = ref<HTMLInputElement | null>(null);
let picking: string | null = null;
async function onFile(e: Event) {
  const t = e.target as HTMLInputElement;
  const f = t.files?.[0];
  const type = picking;
  t.value = '';
  if (!f || !type) return;
  busy.value = type;
  err.value = null;
  try {
    emit('change', { ...props.photos, [type]: await toDocumentBase64(f) });
  } catch (x) {
    err.value = errorText(x);
  }
  busy.value = null;
}
function pick(type: string) {
  picking = type;
  input.value?.click();
}
</script>

<template>
  <div class="col">
    <span class="t-label-l">{{ tr('Document photos', 'কাগজের ছবি') }}</span>
    <span class="t-body-s muted">{{ note ?? tr('Clear photo of the whole paper.', 'পুরো কাগজের পরিষ্কার ছবি দিন।') }}</span>
    <input ref="input" type="file" accept="image/*" hidden @change="onFile" />
    <div v-for="d in specs" :key="d.type" class="row health-doc">
      <img v-if="photos[d.type]" :src="imgSrc(photos[d.type])" alt="" class="health-doc-thumb" />
      <span v-else class="health-doc-thumb health-doc-ph"><Icon name="photo_camera" /></span>
      <div class="col gap-2 grow">
        <span class="t-body-m semi">{{ d.label }}{{ d.required ? ' *' : '' }}</span>
        <span class="t-body-s muted">{{ d.hint }}</span>
      </div>
      <Button variant="text" small :busy="busy === d.type" :disabled="busy != null" @click="pick(d.type)">{{ photos[d.type] ? tr('Change', 'বদলান') : tr('Add photo', 'ছবি দিন') }}</Button>
    </div>
    <ErrorText :text="err" />
  </div>
</template>
