<script setup lang="ts">
// অ্যাডমিন ছবি চাইলে বা দরকারি ছবি বাকি থাকলে (অ্যাপের DocsNeededNotice)
import { ref } from 'vue';
import { Button, ErrorText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { badgeDocs } from './badge';
import DocPhotosField from './DocPhotosField.vue';

const props = defineProps<{ uid: string; type: string; requested: boolean; note: string }>();
const emit = defineEmits<{ done: [] }>();
const open = ref(false);
const photos = ref<Record<string, string>>({});
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await call('verify_docs_upload', { kind: 'badge', id: props.uid, docs: photos.value });
    open.value = false;
    photos.value = {};
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <div class="card tonal col">
    <span class="t-title-s">{{ requested ? tr('The Lifolo team asked for document photos', 'Lifolo টিম কাগজের ছবি চেয়েছে') : tr('Document photos are needed for checking', 'যাচাইয়ের জন্য কাগজের ছবি লাগবে') }}</span>
    <p v-if="note" class="t-body-m pre">{{ note }}</p>
    <div v-if="!open"><Button variant="outline" small @click="open = true">{{ tr('Add photos', 'ছবি দিন') }}</Button></div>
    <template v-else>
      <DocPhotosField :specs="badgeDocs(type)" :photos="photos" @change="(p) => (photos = p)" />
      <ErrorText :text="err" />
      <div class="row">
        <Button variant="outline" small :busy="busy" :disabled="!Object.keys(photos).length" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
        <Button variant="text" small @click="open = false">{{ tr('Cancel', 'বাতিল') }}</Button>
      </div>
    </template>
  </div>
</template>
