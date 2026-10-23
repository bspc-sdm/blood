<script setup lang="ts">
// রোগী: "আমার রসিদ" (my_invoices) — তালিকা, একটা রসিদ (দেখা, ছাপা/PDF), আর "আমার খরচ" (মাসে ধরন অনুযায়ী মোট)।
// কেয়ারগিভার: উপরে কার রসিদ (নিজের / রোগী) — প্রেসক্রিপশনের মতোই asUid যায় (lib/care)।
//   /invoices            তালিকা          /invoices/:id     একটা রসিদ          /invoices/expenses   আমার খরচ
import { computed } from 'vue';
import { Button, EmptyState, ErrorBox, Icon, PillTabs, SkeletonCards, Tag, cx } from '@/ui/kit';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useInfiniteQ } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { formatDayOf } from '@/lib/format';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useNav } from '@/lib/nav';
import { T } from './text';
import { kindIcon, kindText, money, parsePage, type InvPage, type Invoice } from './model';
import CareSwitch from './CareSwitch.vue';
import InvoiceDetail from './InvoiceDetail.vue';
import MyExpenses from './MyExpenses.vue';

const props = defineProps<{ id: string | null; tab: 'list' | 'expenses' }>();
usePageTitle(() => (props.tab === 'expenses' ? T.myExpenses() : T.myReceipts()));
const nav = useNav();
const s = useSession();
const lang = useLang();
const care = useCareMode();
const q = useInfiniteQ<InvPage>(() => ({
  queryKey: ['my_invoices', care.value?.uid ?? null, lang.value, s.uid],
  queryFn: ({ pageParam }: { pageParam: string | null }) => call('my_invoices', pageParam ? { cursor: pageParam } : {}).then(parsePage),
  initialPageParam: null,
  getNextPageParam: (last: InvPage) => last.next ?? undefined,
  enabled: !s.guest && props.tab === 'list',
}));
const items = computed<Invoice[]>(() => ((q.data as any)?.pages ?? []).flatMap((p: InvPage) => p.items));
const fromList = computed(() => (props.id ? items.value.find((i) => i.id === props.id) ?? null : null));
const tabs = computed(() => [
  { key: 'list', label: T.myReceipts(), icon: 'list_alt' },
  { key: 'expenses', label: T.myExpenses(), icon: 'history' },
]);
</script>

<template>
  <ListDetail :has-detail="!!(id && !s.guest)">
    <div class="list-inner">
      <div class="health-tabs">
        <PillTabs :label="T.receipts()" :value="tab" :tabs="tabs" @change="(k) => nav(k === 'expenses' ? '/invoices/expenses' : '/invoices')" />
      </div>
      <EmptyState v-if="s.guest" icon="list_alt" :title="T.myReceipts()" :text="T.noReceiptsText()">
        <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </EmptyState>
      <template v-else>
        <CareSwitch />
        <MyExpenses v-if="tab === 'expenses'" />
        <template v-else>
          <h2 class="t-headline">{{ T.myReceipts() }}</h2>
          <SkeletonCards v-if="q.isPending" :n="3" :h="88" />
          <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
          <EmptyState v-else-if="!items.length" icon="list_alt" :title="T.noReceipts()" :text="T.noReceiptsText()" />
          <template v-else>
            <button
              v-for="i in items"
              :key="i.id"
              type="button"
              :class="cx('card col gap-4 inv-card', i.id === id && 'selected', i.status === 'void' && 'inv-void-row')"
              data-testid="my-invoice"
              @click="nav(`/invoices/${i.id}`)"
            >
              <span class="row gap-8">
                <Icon :name="kindIcon(i.kind)" :size="20" />
                <span class="t-title-s grow ellipsis">{{ i.bizName }}</span>
                <span class="num semi">{{ money(i.total) }}</span>
              </span>
              <span class="row wrap gap-6">
                <span class="t-body-s muted grow">{{ kindText(i.kind) }} · {{ formatDayOf(i.issuedAt) }}</span>
                <Tag v-if="i.status === 'void'" tone="error">{{ T.voided() }}</Tag>
                <Tag v-else-if="i.due > 0" tone="error">{{ T.due() }} {{ money(i.due) }}</Tag>
              </span>
            </button>
            <Button v-if="q.hasNextPage" variant="text" :busy="q.isFetchingNextPage" @click="q.fetchNextPage()">{{ T.loadMore() }}</Button>
          </template>
        </template>
      </template>
    </div>
    <template #detail>
      <InvoiceDetail v-if="id && !s.guest" :id="id" :key="id" :initial="fromList" :back="() => nav('/invoices')" />
    </template>
    <template v-if="!s.guest && tab === 'list'" #placeholder><EmptyState icon="list_alt" :title="T.pickReceipt()" /></template>
  </ListDetail>
</template>
