<script setup lang="ts">
// ফোন করা গাড়ি থেকে একটা বাছা
import { ref } from 'vue';
import { Button, Dialog, ErrorText } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { AmbCar } from './ambreq';

const props = defineProps<{ title: string; cars: AmbCar[]; noLabel: string; onPick: (c: AmbCar | null) => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const busy = ref(false);
const err = ref<string | null>(null);
async function go(c: AmbCar | null) {
  busy.value = true;
  try {
    await props.onPick(c);
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="title" @close="!busy && emit('close')">
    <div class="col gap-8">
      <Button v-for="c in cars" :key="c.id" variant="outline" icon="check_circle" block :disabled="busy" @click="go(c)">{{ tr('Yes — ', 'হ্যাঁ — ') + c.name }}</Button>
    </div>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :busy="busy" @click="go(null)">{{ noLabel }}</Button>
    </template>
  </Dialog>
</template>
