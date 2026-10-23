<script setup lang="ts">
// কথোপকথনের তালিকা (chat_threads): ছবি, নাম, প্রসঙ্গ, শেষ মেসেজ, সময়, না পড়া সংখ্যা; নিচে "আরও দেখুন" (cursor)।
import { RouterLink } from 'vue-router';
import { Avatar, Button, EmptyState, ErrorBox, IconButton, SkeletonCards } from '@/ui/kit';
import { useNow } from '@/ui/composables';
import { num, tr } from '@/lib/i18n';
import { chat, loadThreads } from './store';
import { imgSrc } from './photo';
import { T, ctxText, lastText, shortWhen } from './text';

defineProps<{ active: string | null }>();
const now = useNow(60_000);
const reload = () => void loadThreads().catch(() => {});
const more = () => void loadThreads(true).catch(() => {});
</script>

<template>
  <div class="chat-list" data-testid="thread-list">
    <div class="chat-list-head">
      <h2 class="t-title-l grow">{{ T.messages() }}</h2>
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" :disabled="chat.threadsLoading" @click="reload" />
    </div>
    <SkeletonCards v-if="!chat.threadsLoaded && !chat.threadsError" :n="5" :h="64" />
    <ErrorBox v-else-if="chat.threadsError && !chat.threads.length" :error="chat.threadsError" :on-retry="reload" />
    <EmptyState v-else-if="!chat.threads.length" icon="forum_outline" :title="T.empty()" :text="T.emptySub()" />
    <ul v-else class="chat-threads">
      <li v-for="t in chat.threads" :key="t.threadId">
        <RouterLink
          :to="`/messages/${encodeURIComponent(t.threadId)}`"
          :class="['chat-thread-row', active === t.threadId && 'on', t.unread > 0 && 'unread']"
          :aria-current="active === t.threadId ? 'page' : undefined"
          data-testid="thread-row"
          :data-thread="t.threadId"
        >
          <Avatar :photo="t.peer.photoThumb ? imgSrc(t.peer.photoThumb) : null" :size="44" />
          <span class="chat-thread-main">
            <span class="row gap-8">
              <span class="name ellipsis grow">{{ t.peer.name || '—' }}</span>
              <span class="when">{{ t.last ? shortWhen(t.last.at, now) : '' }}</span>
            </span>
            <span class="row gap-8">
              <span class="last ellipsis grow">
                <template v-if="t.last">{{ (t.last.mine ? tr('You: ', 'আপনি: ') : '') + lastText(t.last.text) }}</template><!-- bn-ok -->
                <template v-else>{{ ctxText(t.ctx) }}</template>
              </span>
              <span v-if="t.unread > 0" class="chat-unread" data-testid="thread-unread" :aria-label="tr(`${t.unread} unread`, `${num(t.unread)}টা না পড়া`)">{{ num(t.unread > 99 ? '99+' : t.unread) }}</span><!-- bn-ok -->
            </span>
            <span v-if="t.last" class="ctx">{{ ctxText(t.ctx) }}<template v-if="!t.canSend"> · {{ tr('closed', 'বন্ধ') }}</template></span><!-- bn-ok -->
          </span>
        </RouterLink>
      </li>
    </ul>
    <Button v-if="chat.threadsNext && chat.threads.length" variant="outline" :busy="chat.threadsLoading" @click="more">{{ tr('Load more', 'আরও দেখুন') }}</Button><!-- bn-ok -->
  </div>
</template>
