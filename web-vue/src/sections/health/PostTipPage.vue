<script setup lang="ts">
// টিপস দিন — অ্যাপের PostTipScreen
import { ref } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, EmptyState, ErrorText, Loading, TextInput } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { call, errorText } from '@/lib/api';
import { queryClient } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { approvedBadge, goBack, useMe } from './common';

const nav = useNav();
const s = useSession();
const me = useMe();
const title = ref('');
const body = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const back = () => goBack(nav, '/health/tips');

async function post() {
  if (!title.value.trim() || !body.value.trim()) {
    err.value = tr('Add a title and the tip', 'শিরোনাম আর টিপস দুটোই লিখুন');
    return;
  }
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ id?: string }>('post_tip', { title: title.value.trim(), body: body.value.trim() });
    await queryClient.invalidateQueries({ queryKey: ['tips'] });
    nav(r.id ? `/health/tip/${r.id}` : '/health/tips', { replace: true });
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <FormPage :title="tr('Post a tip', 'টিপস দিন')" :on-back="back">
    <EmptyState v-if="s.guest" icon="lightbulb_outline" :title="tr('Only verified health professionals can post tips', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা টিপস দিতে পারেন')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
    <Loading v-else-if="!me" />
    <EmptyState v-else-if="!approvedBadge(me)" icon="workspace_premium" :title="tr('Only verified health professionals can post tips', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা টিপস দিতে পারেন')">
      <RouterLink class="btn tonal" to="/health/badge">{{ tr('Professional badge', 'পেশাদার ব্যাজ') }}</RouterLink>
    </EmptyState>
    <template v-else>
      <TextInput v-model="title" :label="tr('Title', 'শিরোনাম')" maxlength="120" autofocus />
      <div class="field">
        <label for="health-tip">{{ tr('Tip', 'টিপস') }}</label>
        <textarea id="health-tip" v-model="body" class="textarea" rows="10" maxlength="3000" />
        <span class="hint" :style="{ textAlign: 'right' }">{{ num(body.length) }}/{{ num(3000) }}</span>
      </div>
      <ErrorText :text="err" />
      <Button icon="send" block :busy="busy" @click="post">{{ tr('Post', 'পোস্ট করুন') }}</Button>
    </template>
  </FormPage>
</template>
