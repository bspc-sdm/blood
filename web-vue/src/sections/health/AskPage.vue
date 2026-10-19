<script setup lang="ts">
// প্রশ্ন করুন — অ্যাপের AskQuestionScreen
import { ref } from 'vue';
import { Button, Check, ErrorText } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { call, errorText } from '@/lib/api';
import { queryClient } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { goBack } from './common';

const nav = useNav();
const s = useSession();
const text = ref('');
const anonymous = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);

async function post() {
  if (!text.value.trim()) {
    err.value = tr('Write your question', 'প্রশ্ন লিখুন');
    return;
  }
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ id: string }>('ask', { text: text.value.trim(), anonymous: anonymous.value });
    queryClient.invalidateQueries({ queryKey: ['questions'] });
    nav(r.id ? `/health/question/${r.id}` : '/health/discussion', { replace: true });
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <FormPage :title="tr('Ask a question', 'প্রশ্ন করুন')" :on-back="() => goBack(nav, '/health/discussion')">
    <div class="field">
      <label for="health-q">{{ tr('Your question', 'আপনার প্রশ্ন') }}</label>
      <textarea id="health-q" v-model="text" v-focus class="textarea" rows="6" maxlength="1000" />
      <div class="row hint health-hint">
        <span class="grow">{{ tr('Answers are general advice, not a diagnosis.', 'উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।') }}</span>
        <span class="num none">{{ num(text.length) }}/{{ num(1000) }}</span>
      </div>
    </div>
    <Check v-model="anonymous">{{ tr('Hide my name', 'আমার নাম দেখাবে না') }}</Check>
    <ErrorText :text="err" />
    <Button icon="send" block :busy="busy" @click="s.requireLogin(tr('Log in to ask a question', 'প্রশ্ন করতে লগইন করুন'), post)">{{ tr('Post', 'পোস্ট করুন') }}</Button>
  </FormPage>
</template>
