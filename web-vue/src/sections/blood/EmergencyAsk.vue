<script setup lang="ts">
// আহ্বান শেষে: "রক্ত দিয়েছেন?"
import { ref } from 'vue';
import { Action, ErrorText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { THANKS_DONATED, type Ask } from './emergency';

const props = defineProps<{ a: Ask }>();
const emit = defineEmits<{ done: [msg: string] }>();
const busy = ref(false);
const err = ref<string | null>(null);
async function answer(yes: boolean) {
  busy.value = true;
  err.value = null;
  try {
    await call('emergency_donated', { id: props.a.id, donated: yes });
    emit('done', yes ? THANKS_DONATED() : tr('Thanks for letting us know.', 'জানানোর জন্য ধন্যবাদ।'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="card tonal col gap-8" :style="{ padding: '14px' }">
    <h3 class="t-title-m bold">{{ tr('Did you donate blood?', 'রক্ত দিয়েছেন?') }}</h3>
    <p class="t-body-m">{{ tr(`You said you were coming to the emergency appeal at ${a.hospitalName}.`, `${a.hospitalName}-এর জরুরি আহ্বানে আপনি "আসছি" বলেছিলেন।`) }}</p>
    <ErrorText :text="err" />
    <div class="row gap-8 wrap">
      <Action icon="check_circle" primary :busy="busy" @click="answer(true)">{{ tr('Yes, I donated', 'হ্যাঁ, দিয়েছি') }}</Action>
      <Action icon="close" :disabled="busy" @click="answer(false)">{{ tr('No', 'না, দিইনি') }}</Action>
    </div>
  </section>
</template>
