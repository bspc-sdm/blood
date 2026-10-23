<script setup lang="ts">
// মেসেজ (/messages আর /messages/:threadId): বড় পর্দায় (≥৯৬০) বাঁয়ে কথোপকথনের তালিকা, ডানে খোলা কথোপকথন;
// ছোট পর্দায় একটা করে — তালিকা, অথবা পুরো পাতা জুড়ে কথোপকথন (মোবাইলে উপরের বার আর নিচের ট্যাব লুকিয়ে)।
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Button, EmptyState, Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { comingSoon, featureOn } from '@/lib/features';
import { useDetailMode, useMobile, usePageTitle, useTwoPane } from '@/shell/chrome';
import { chat, loadThreads } from './store';
import { T } from './text';
import ThreadList from './ThreadList.vue';
import ThreadView from './ThreadView.vue';

const route = useRoute();
const s = useSession();
const two = useTwoPane();
const mobile = useMobile();
const threadId = computed(() => {
  const p = route.params.threadId;
  return typeof p === 'string' && p ? p : null;
});
const authed = computed(() => s.ready && !s.guest && !!s.uid);
usePageTitle(() => T.messages());
useDetailMode(() => mobile.value && !!threadId.value && authed.value && featureOn('chat'));

// তালিকা: পাতা খুললে একবার (তারপর SSE/পোল নতুন রাখে)
watch(
  () => (authed.value && featureOn('chat') && (two.value || !threadId.value) ? s.uid : null),
  (u) => {
    if (u) void loadThreads().catch(() => {});
  },
  { immediate: true },
);
</script>

<template>
  <div v-if="!featureOn('chat')" class="ld-list"><div class="list-inner">
    <EmptyState icon="forum_outline" :title="comingSoon()" />
  </div></div>
  <div v-else-if="s.ready && s.guest" class="ld-list"><div class="list-inner">
    <EmptyState icon="forum_outline" :title="tr('Log in to do this', 'এর জন্য লগইন করুন')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
  </div></div>
  <div v-else-if="authed" :class="['chat-page', threadId ? 'has-thread' : 'no-thread']" data-testid="messages-page">
    <section v-if="two || !threadId" class="chat-list-pane" :aria-label="T.messages()">
      <ThreadList :active="threadId" />
    </section>
    <section v-if="threadId" class="chat-thread-pane">
      <ThreadView :key="threadId" :thread-id="threadId" :two="two" />
    </section>
    <section v-else-if="two" class="chat-thread-pane chat-pick">
      <Icon name="forum_outline" :size="48" />
      <p class="t-title-m">{{ chat.threads.length ? T.pick() : T.empty() }}</p>
    </section>
  </div>
</template>
