<script setup lang="ts">
// নোটিফিকেশন (অ্যাপের Inbox.kt): খুললেই সব পড়া ধরা হয়; চাপলে অ্যাপের মতো সংশ্লিষ্ট পাতা খোলে।
// বড় পর্দায় পাশে বিস্তারিত, মোবাইলে সরাসরি সেই পাতা।
import { computed, watch } from 'vue';
import { Button, EmptyState, ErrorBox, Icon, IconButton, SkeletonCards, cx } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import ListDetail from '@/shell/ListDetail.vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import { useMobile, usePageTitle, useTwoPane } from '@/shell/chrome';
import { call } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { useCall } from '@/lib/query';
import { setCareMode } from '@/lib/care';
import { useSession } from '@/lib/session';
import { matchPath, useNav } from '@/lib/nav';
import { useRoute } from 'vue-router';
import { inboxTarget, useAccountState } from './util';
import { useBack } from './nav';
import NoteIcon from './NoteIcon.vue';

interface InboxItem { id: string; title: string; body: string; data: Record<string, string>; read: boolean; createdAt: number }

const s = useSession();
const nav = useNav();
const route = useRoute();
const two = useTwoPane();
const mobile = useMobile();
const title = computed(() => tr('Notifications', 'নোটিফিকেশন'));
usePageTitle(title);
const back = useBack('/');
const acct = useAccountState();
const selId = computed(() => matchPath('/inbox/:id', route.path)?.params.id ?? null);
const q = useCall<{ items?: InboxItem[] }>('inbox', {}, () => ({ enabled: !s.guest, staleTime: 0 }));

// খুললেই সব পড়া (কোনগুলো নতুন ছিল তা এবার আলাদা রঙে দেখায়), আর ঘণ্টার সংখ্যা নতুন করে
let marked = 0;
watch(
  () => [q.data, q.dataUpdatedAt] as const,
  () => {
    const items = q.data?.items;
    if (!items || marked === q.dataUpdatedAt) return;
    marked = q.dataUpdatedAt;
    if (items.some((x) => !x.read)) call('inbox_read').then(() => s.reloadMe(), () => {});
  },
  { immediate: true },
);

const items = computed(() => q.data?.items ?? []);
const sel = computed(() => (selId.value ? items.value.find((x) => x.id === selId.value) ?? null : null));
// নোটিফিকেশন নিজের — কেয়ারগিভার মোড চালু থাকলে বন্ধ করে খোলা (রোগীর খবর /care হয়ে আবার রোগীর মোডে যায়)
const go = (data: Record<string, string>) => {
  setCareMode(null);
  nav(inboxTarget(data, acct.value.me, acct.value.work));
};
const open = (it: InboxItem) => (two.value ? nav(`/inbox/${encodeURIComponent(it.id)}`) : go(it.data ?? {}));
</script>

<template>
  <template v-if="s.ready && s.guest">
    <SubPageBar v-if="mobile" :title="title" :on-back="back" />
    <EmptyState icon="notifications" :title="tr('Log in to do this', 'এর জন্য লগইন করুন')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
  </template>
  <template v-else>
    <SubPageBar v-if="mobile && !selId" :title="title" :on-back="back">
      <template #actions><IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" :disabled="q.isFetching" @click="q.refetch()" /></template>
    </SubPageBar>
    <ListDetail :has-detail="!!selId">
      <div class="list-inner">
        <div v-if="!mobile" class="row acct-listhead"><span class="grow" /><IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" :disabled="q.isFetching" @click="q.refetch()" /></div>
        <SkeletonCards v-if="q.isLoading" :n="5" :h="72" />
        <ErrorBox v-else-if="q.error && !items.length" :error="q.error.message" :on-retry="() => q.refetch()" />
        <EmptyState v-else-if="!items.length" icon="notifications" :title="tr('No notifications yet', 'এখনো কোনো নোটিফিকেশন নেই')" />
        <ul v-else class="acct-notes">
          <li v-for="it in items" :key="it.id">
            <button type="button" :class="cx('acct-note', selId === it.id && 'sel')" :aria-current="selId === it.id ? 'true' : undefined" @click="open(it)">
              <NoteIcon :item="it" />
              <span class="col gap-2 grow">
                <span :class="cx('t-title-s', it.read && 'acct-read')">{{ it.title }}</span>
                <span v-if="it.body" class="t-body-m muted clamp2">{{ it.body }}</span>
                <span class="t-label-s muted">{{ timeAgo(it.createdAt) }}</span>
              </span>
              <span v-if="!it.read" class="acct-dot" aria-hidden="true" />
            </button>
          </li>
        </ul>
      </div>
      <template #detail>
        <DetailPane :title="sel?.title ?? title" back-to="/inbox">
          <SkeletonCards v-if="q.isLoading" :n="1" />
          <EmptyState v-else-if="!sel" icon="notifications" :title="tr('Not found', 'পাওয়া যায়নি')" />
          <template v-else>
            <div class="row gap-12">
              <NoteIcon :item="{ ...sel, read: true }" />
              <span class="t-body-s muted">{{ formatDateTime(sel.createdAt) }}</span>
            </div>
            <p v-if="sel.body" class="t-body-l pre">{{ sel.body }}</p>
            <div>
              <Button icon="chevron_right" @click="go(sel.data ?? {})">{{ tr('Open', 'খুলুন') }}</Button>
            </div>
          </template>
        </DetailPane>
      </template>
      <template #placeholder><Icon name="notifications" :size="64" class="acct-ph" /></template>
    </ListDetail>
  </template>
</template>
