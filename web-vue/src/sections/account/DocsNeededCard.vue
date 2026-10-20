<script setup lang="ts">
// ব্যাজের কাগজের ছবি (অ্যাপের Verify.kt-এর DocsNeededNotice)
import { ref } from 'vue';
import { Button, ErrorText, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { toDocument } from './util';

interface DocSpec { type: string; label: string; hint: string; required: boolean; has?: boolean }
const props = defineProps<{ uid: string; docs: DocSpec[]; requested: boolean; note: string }>();
const emit = defineEmits<{ done: [] }>();
const open = ref(false);
const photos = ref<Record<string, string>>({});
const reading = ref<string | null>(null);
const busy = ref(false);
const error = ref<string | null>(null);
let picking: string | null = null;
const input = ref<HTMLInputElement | null>(null);

async function onFile(f: File | undefined) {
  const type = picking;
  picking = null;
  if (!f || !type) return;
  reading.value = type;
  try {
    const b64 = await toDocument(f);
    photos.value = { ...photos.value, [type]: b64 };
    error.value = null;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    reading.value = null;
  }
}
function onChange(e: Event) {
  const t = e.target as HTMLInputElement;
  onFile(t.files?.[0]);
  t.value = '';
}
function pick(type: string) {
  picking = type;
  input.value?.click();
}
async function send() {
  busy.value = true;
  error.value = null;
  try {
    await call('verify_docs_upload', { kind: 'badge', id: props.uid, docs: photos.value });
    toast(tr('Sent.', 'পাঠানো হয়েছে।'));
    open.value = false;
    photos.value = {};
    emit('done');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="acct-docs">
    <span class="t-title-s">
      {{ requested ? tr('The Lifolo team asked for document photos', 'Lifolo টিম কাগজের ছবি চেয়েছে') : tr('Document photos are needed for checking', 'যাচাইয়ের জন্য কাগজের ছবি লাগবে') }}
    </span>
    <p v-if="note" class="t-body-m pre">{{ note }}</p>
    <div v-if="!open"><Button variant="outline" small @click="open = true">{{ tr('Add photos', 'ছবি দিন') }}</Button></div>
    <template v-else>
      <input ref="input" type="file" accept="image/*" hidden @change="onChange" />
      <span class="t-body-s muted">{{ tr('Clear photo of the whole paper.', 'পুরো কাগজের পরিষ্কার ছবি দিন।') }}</span>
      <div v-for="d in docs" :key="d.type" class="row gap-12">
        <img v-if="photos[d.type]" class="acct-doc-thumb" :src="`data:image/jpeg;base64,${photos[d.type]}`" alt="" />
        <div class="col gap-2 grow">
          <span class="t-body-m semi">{{ d.label }}{{ d.required ? ' *' : '' }}</span>
          <span v-if="d.hint" class="t-body-s muted">{{ d.hint }}</span>
        </div>
        <Button variant="text" small :busy="reading === d.type" :disabled="reading != null" @click="pick(d.type)">
          {{ photos[d.type] ? tr('Change', 'বদলান') : tr('Add photo', 'ছবি দিন') }}
        </Button>
      </div>
      <ErrorText :text="error" />
      <div class="row gap-8">
        <Button small :busy="busy" :disabled="!Object.keys(photos).length" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
        <Button variant="text" small @click="open = false">{{ tr('Cancel', 'বাতিল') }}</Button>
      </div>
    </template>
  </div>
</template>
