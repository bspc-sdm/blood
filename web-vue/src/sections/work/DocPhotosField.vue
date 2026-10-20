<script setup lang="ts">
// কাগজের ছবি (Verify.kt)
import { ref } from 'vue';
import { Button, ErrorText, Icon } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { compressDocument, type DocSpec } from './shared';

const props = defineProps<{ specs: DocSpec[]; photos: Record<string, string>; note?: string | null }>();
const emit = defineEmits<{ change: [p: Record<string, string>] }>();
const input = ref<HTMLInputElement | null>(null);
let picking: string | null = null;
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
async function picked(file: File | undefined) {
  const type = picking;
  picking = null;
  if (!file || !type) return;
  busy.value = type;
  try {
    const b = await compressDocument(file);
    emit('change', { ...props.photos, [type]: b });
    err.value = null;
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
function onChange(e: Event) {
  const t = e.target as HTMLInputElement;
  picked(t.files?.[0]);
  t.value = '';
}
function pick(type: string) {
  picking = type;
  input.value?.click();
}
</script>

<template>
  <div class="col gap-8">
    <span class="t-label-l">{{ tr('Document photos', 'কাগজের ছবি') }}</span>
    <span class="t-body-s muted">{{ note ?? tr('Clear photo of the whole paper.', 'পুরো কাগজের পরিষ্কার ছবি দিন।') }}</span>
    <input ref="input" type="file" accept="image/*" hidden @change="onChange" />
    <div class="work-docs">
      <div v-for="d in specs" :key="d.type" class="work-doc">
        <img v-if="photos[d.type]" :src="`data:image/jpeg;base64,${photos[d.type]}`" alt="" class="work-doc-thumb" />
        <span v-else class="work-doc-thumb blank"><Icon name="photo_camera" /></span>
        <div class="col gap-2 grow">
          <span class="t-title-s">{{ d.label + (d.required ? ' *' : '') }}</span>
          <span class="t-body-s muted">{{ d.hint }}</span>
        </div>
        <Button variant="text" small :busy="busy === d.type" :disabled="busy != null" @click="pick(d.type)">
          {{ photos[d.type] ? tr('Change', 'বদলান') : tr('Add photo', 'ছবি দিন') }}
        </Button>
      </div>
    </div>
    <ErrorText :text="err" />
  </div>
</template>
