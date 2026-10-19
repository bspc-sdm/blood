<script setup lang="ts">
// তারকা রেটিং (অ্যাপের StarRatingDialog)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, IconButton, TextArea } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';

const props = defineProps<{ title: string; onSubmit: (stars: number, comment: string) => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const stars = ref(0);
const comment = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function submit() {
  busy.value = true;
  err.value = null;
  try {
    await props.onSubmit(stars.value, comment.value.trim());
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="title" size="small" @close="() => !busy && emit('close')">
    <div class="row appt-stars" role="radiogroup" :aria-label="tr('Rating', 'রেটিং')">
      <IconButton v-for="i in [1, 2, 3, 4, 5]" :key="i" :icon="i <= stars ? 'star_fill' : 'star'" :label="num(i)" :aria-pressed="i <= stars" @click="stars = i" />
    </div>
    <TextArea :label="tr('Comment (optional)', 'মন্তব্য (ঐচ্ছিক)')" :rows="2" :model-value="comment" @update:model-value="(v) => (comment = v.slice(0, 300))" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Later', 'পরে') }}</Button>
      <Button :busy="busy" :disabled="stars === 0" @click="submit">{{ tr('Submit', 'জমা দিন') }}</Button>
    </template>
  </Dialog>
</template>
