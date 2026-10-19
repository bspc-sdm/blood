<script setup lang="ts">
// সাহায্য ও আপিল (অ্যাপের Support.kt): নতুন অনুরোধ, নিজের অনুরোধের তালিকা, কথোপকথন আর আরও লেখা।
// /help — তালিকা; /help/new?category=pharmacy — নতুন (বিষয় আগে থেকে বাছা); /help/:id — কথোপকথন
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Button, EmptyState, ErrorBox, Icon, ListRow, SkeletonCards, cx, toast } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import ListDetail from '@/shell/ListDetail.vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import { useMobile, usePageTitle } from '@/shell/chrome';
import { tr, trPlain } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { matchPath, useNav, useSearchParams } from '@/lib/nav';
import { SUPPORT_EMAIL, ticketAnswered, ticketLabel, ticketStatus, type Ticket } from './util';
import { useBack } from './nav';
import NewTicket from './NewTicket.vue';
import Conversation from './Conversation.vue';

const s = useSession();
const nav = useNav();
const route = useRoute();
const [params] = useSearchParams();
const mobile = useMobile();
const title = computed(() => tr('Help & appeals', 'সাহায্য ও আপিল'));
usePageTitle(title);
const back = useBack(() => (s.guest ? '/' : '/profile'));
const startCat = computed(() => params.value.get('category'));
const isNew = computed(() => !!matchPath('/help/new', route.path) || (!!startCat.value && route.path.replace(/\/$/, '') === '/help'));
const selId = computed(() => (!isNew.value ? matchPath('/help/:id', route.path)?.params.id ?? null : null));
const q = useCall<{ items?: Ticket[] }>('tickets_mine', {}, () => ({ enabled: !s.guest, staleTime: 0 }));

const items = computed(() => q.data?.items ?? []);
const sel = computed(() => (selId.value ? items.value.find((t) => t.id === selId.value) ?? null : null));
const hasDetail = computed(() => isNew.value || !!selId.value);
const lastOf = (t: Ticket) => t.messages[t.messages.length - 1];
const hot = (t: Ticket) => ticketAnswered(t) && t.status !== 'closed';

function sent(id: string | null) {
  toast(trPlain('Sent. The Lifolo team will reply here.', 'পাঠানো হয়েছে। Lifolo টিম এখানেই উত্তর দেবে।'));
  q.refetch();
  nav(id ? `/help/${id}` : '/help', { replace: true });
}
</script>

<template>
  <template v-if="s.ready && s.guest">
    <SubPageBar v-if="mobile" :title="title" :on-back="back" />
    <div class="acct-page">
      <div class="acct-narrow col gap-12">
        <EmptyState icon="help" :title="tr('Log in to do this', 'এর জন্য লগইন করুন')">
          <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
        </EmptyState>
        <nav class="acct-menu">
          <ListRow icon="mail" :title="tr('Contact support', 'সাপোর্টে যোগাযোগ')" :sub="SUPPORT_EMAIL" :href="`mailto:${SUPPORT_EMAIL}`"><template #trailing><span /></template></ListRow>
        </nav>
      </div>
    </div>
  </template>
  <template v-else>
    <SubPageBar v-if="mobile && !hasDetail" :title="title" :on-back="back" />
    <ListDetail :has-detail="hasDetail">
      <div class="list-inner">
        <Button icon="edit_note" class="acct-write" @click="nav(`/help/new${startCat ? `?category=${encodeURIComponent(startCat)}` : ''}`)">{{ tr('Write to us', 'আমাদের লিখুন') }}</Button>
        <h2 class="acct-sec">{{ tr('My messages', 'আমার অনুরোধ') }}</h2>
        <SkeletonCards v-if="q.isLoading" :n="3" :h="92" />
        <ErrorBox v-else-if="q.error && !items.length" :error="q.error.message" :on-retry="() => q.refetch()" />
        <p v-else-if="!items.length" class="t-body-m muted">{{ tr('Nothing yet.', 'এখনো কিছু নেই।') }}</p>
        <template v-else>
          <button
            v-for="t in items"
            :key="t.id"
            type="button"
            :class="cx('card click acct-ticket', selId === t.id && 'sel')"
            :aria-current="selId === t.id ? 'true' : undefined"
            @click="nav(`/help/${t.id}`)"
          >
            <span class="row">
              <span class="t-title-s grow ellipsis">{{ ticketLabel(t.category) }}</span>
              <span :class="cx('t-label-m', hot(t) ? 'c-primary' : 'muted')">{{ ticketStatus(t) }}</span>
            </span>
            <span v-if="lastOf(t)" class="t-body-m clamp2">{{ lastOf(t)!.text }}</span>
            <span class="t-body-s muted">{{ timeAgo(t.updatedAt) }}</span>
          </button>
        </template>
      </div>
      <template #detail>
        <DetailPane v-if="isNew" :title="tr('Write to us', 'আমাদের লিখুন')" back-to="/help">
          <NewTicket :key="startCat ?? ''" :start="startCat" @sent="sent" />
        </DetailPane>
        <DetailPane v-else :title="sel ? ticketLabel(sel.category) : title" back-to="/help">
          <SkeletonCards v-if="q.isLoading" :n="2" :h="64" />
          <Conversation v-else-if="sel" :key="sel.id" :t="sel" @sent="q.refetch()" />
          <EmptyState v-else icon="help" :title="tr('Not found', 'পাওয়া যায়নি')" />
        </DetailPane>
      </template>
      <template #placeholder><Icon name="forum_outline" :size="64" class="acct-ph" /></template>
    </ListDetail>
  </template>
</template>
