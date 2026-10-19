<script setup lang="ts">
// মালিকের কার্ডে: অ্যাডমিন ছবি চাইলে বা দরকারি ছবি বাকি থাকলে — কারণসহ "ছবি দিন"
import { ref } from 'vue';
import { Button, Dialog, ErrorText, Icon } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { DocFlags, DocSpec, Kind } from './shared';
import DocPhotosField from './DocPhotosField.vue';

const props = defineProps<{ kind: Kind; id: string; specs: DocSpec[]; item: DocFlags; title?: string }>();
const emit = defineEmits<{ done: [] }>();
const open = ref(false);
const photos = ref<Record<string, string>>({});
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await call('verify_docs_upload', { kind: props.kind, id: props.id, docs: photos.value });
    open.value = false;
    photos.value = {};
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div v-if="item.docsMissing || item.docsRequested" class="card tonal work-notice">
    <Icon name="photo_camera" />
    <div class="col gap-4 grow">
      <span class="t-title-s">
        {{ item.docsRequested ? tr('The Lifolo team asked for document photos', 'Lifolo টিম কাগজের ছবি চেয়েছে') : tr('Document photos are needed for checking', 'যাচাইয়ের জন্য কাগজের ছবি লাগবে') }}
      </span>
      <span v-if="title" class="t-body-s">{{ title }}</span>
      <span v-if="item.docsRequestNote" class="t-body-m pre">{{ item.docsRequestNote }}</span>
    </div>
    <Button variant="outline" small @click="open = true">{{ tr('Add photos', 'ছবি দিন') }}</Button>
    <Dialog v-if="open" :title="tr('Add photos', 'ছবি দিন')" @close="() => !busy && (open = false)">
      <p v-if="title" class="t-title-s">{{ title }}</p>
      <DocPhotosField :specs="specs" :photos="photos" @change="(p) => (photos = p)" />
      <ErrorText :text="err" />
      <template #actions>
        <Button variant="text" :disabled="busy" @click="open = false">{{ tr('Cancel', 'বাতিল') }}</Button>
        <Button :busy="busy" :disabled="!Object.keys(photos).length" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
      </template>
    </Dialog>
  </div>
</template>
