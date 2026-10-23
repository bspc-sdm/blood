<script setup lang="ts">
// কেয়ারগিভারপ্রতি সুইচ: সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট, সেশন নোট আর রসিদ এই কেয়ারগিভারকে দেখানো (শুরুতে বন্ধ) —
// care_share_psych {caretakerUid, share}; শুধু রোগী নিজে (কেয়ারগিভারের কাজের তালিকায় নেই)। pro_psychologist বন্ধ থাকলে ডাকা জায়গা দেখায় না।
import { ref, watch } from 'vue';
import { ErrorText, Switch } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { T } from '@/lib/practice';

const props = defineProps<{ caretakerUid: string; shared: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const on = ref(props.shared);
watch(
  () => props.shared,
  (v) => (on.value = v),
);
const busy = ref(false);
const err = ref<string | null>(null);

async function set(v: boolean) {
  const old = on.value;
  on.value = v;
  busy.value = true;
  err.value = null;
  try {
    await call('care_share_psych', { caretakerUid: props.caretakerUid, share: v });
    emit('changed');
  } catch (e) {
    on.value = old;
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <div class="col practice-share" data-testid="psych-share">
    <Switch :label="T.shareTitle()" :sub="T.shareSub()" :model-value="on" :disabled="busy" @update:model-value="set" />
    <ErrorText :text="err" />
  </div>
</template>
