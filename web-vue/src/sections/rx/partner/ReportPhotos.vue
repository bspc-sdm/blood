<script setup lang="ts">
// রিপোর্টের ছবি (ঐচ্ছিক, সর্বোচ্চ ২): ক্যানভাসে ছোট করে JPEG
import { ref } from 'vue';
import { Button, ErrorText, IconButton } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { compressDocument } from '../../work/shared';

const props = defineProps<{ photos: string[] }>();
const emit = defineEmits<{ change: [p: string[]] }>();
const input = ref<HTMLInputElement | null>(null);
const busy = ref(false);
const err = ref<string | null>(null);
async function picked(f: File | undefined) {
  if (!f) return;
  busy.value = true;
  err.value = null;
  try {
    emit('change', [...props.photos, await compressDocument(f)].slice(0, 2));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function onChange(e: Event) {
  const t = e.target as HTMLInputElement;
  picked(t.files?.[0]);
  t.value = '';
}
</script>

<template>
  <div class="col gap-8">
    <span class="t-label-l">{{ tr('Report photos', 'রিপোর্টের ছবি') }}</span>
    <span class="t-body-s muted">{{ tr('Photos of the printed report, if you like (up to 2).', 'চাইলে ছাপানো রিপোর্টের ছবি দিন (সর্বোচ্চ ২টা)।') }}</span>
    <input ref="input" type="file" accept="image/*" hidden @change="onChange" />
    <div class="row wrap gap-8">
      <div v-for="(p, i) in photos" :key="i" class="rxp-thumb">
        <img :src="`data:image/jpeg;base64,${p}`" :alt="tr(`Report photo ${i + 1}`, `রিপোর্টের ছবি ${num(i + 1)}`)" />
        <IconButton small icon="close" :label="tr('Remove', 'সরান')" @click="emit('change', photos.filter((_, j) => j !== i))" />
      </div>
      <Button v-if="photos.length < 2" variant="outline" icon="photo_camera" :busy="busy" @click="input?.click()">{{ tr('Add photo', 'ছবি দিন') }}</Button>
    </div>
    <ErrorText :text="err" />
  </div>
</template>
