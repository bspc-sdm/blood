<script setup lang="ts">
// পেমেন্টের শিট ডায়ালগে (মোবাইলে নিচের শিট): id দিলে সেই পেমেন্ট; start দিলে আগে শুরু করে (pay_due_start,
// পেমেন্ট চাওয়া — সার্ভার একই জিনিসে খোলা থাকলে সেটাই ফেরত দেয়), তারপর সেটা দেখায়।
import { onMounted, ref } from 'vue';
import { Dialog, ErrorBox, SkeletonCards } from '@/ui/kit';
import { ServerError, errorText } from '@/lib/api';
import type { PayRequest } from './model';
import { T, payErrText, purposeText } from './text';
import PayRequestSheet from './PayRequestSheet.vue';

const props = defineProps<{ id?: string | null; initial?: PayRequest | null; start?: (() => Promise<PayRequest | null>) | null; title?: string }>();
const emit = defineEmits<{ close: []; changed: [req: PayRequest | null] }>();
const reqId = ref<string | null>(props.id ?? props.initial?.id ?? null);
const first = ref<PayRequest | null>(props.initial ?? null);
const title = ref(props.title ?? (props.initial ? purposeText(props.initial.purpose) : T.sheetTitle()));
const err = ref<string | null>(null);
const starting = ref(false);
async function begin() {
  if (!props.start || reqId.value) return;
  starting.value = true;
  err.value = null;
  try {
    const r = await props.start();
    if (!r) throw new Error(T.loadErr());
    first.value = r;
    reqId.value = r.id;
    if (!props.title) title.value = purposeText(r.purpose);
  } catch (e) {
    err.value = payErrText(e instanceof ServerError ? e.code : undefined, 'bkash', errorText(e));
  } finally {
    starting.value = false;
  }
}
onMounted(begin);
function loaded(r: PayRequest) {
  if (!props.title) title.value = purposeText(r.purpose);
}
</script>

<template>
  <Dialog :title="title" @close="emit('close')">
    <PayRequestSheet v-if="reqId" :id="reqId" :initial="first" @loaded="loaded" @changed="(r) => emit('changed', r)" />
    <SkeletonCards v-else-if="starting" :n="1" :h="200" />
    <ErrorBox v-else-if="err" :error="err" :on-retry="begin" />
  </Dialog>
</template>
